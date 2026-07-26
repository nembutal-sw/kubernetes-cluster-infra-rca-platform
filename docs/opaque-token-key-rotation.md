# Opaque Token Pepper Rotation

Platform은 bootstrap/node token 원문을 저장하지 않고 HMAC-SHA-256으로 검증합니다. 키 링을
사용하면 이전 pepper로 만든 hash를 읽으면서 새 pepper로 기록할 수 있습니다.

저장 형식:

- `hmac_sha256$v1$<digest>`: 기존 형식, key id 없음
- `hmac_sha256$v2$<key-id>$<digest>`: 현재 형식, 검증 키를 바로 선택

사용자 비밀번호는 이 설정과 무관하며 계속 PBKDF2를 사용합니다.

## Configuration

| 환경변수 | 설명 | 기본값 |
| --- | --- | --- |
| `RCA_OPAQUE_TOKEN_PEPPER` | 현재 기록 키 | 개발 전용 값 |
| `RCA_OPAQUE_TOKEN_KEY_ID` | 현재 키 식별자 | `legacy` |
| `RCA_OPAQUE_TOKEN_PREVIOUS_KEYS` | `key-id=pepper`를 쉼표로 연결한 검증 키 | 빈 값 |
| `RCA_OPAQUE_TOKEN_WRITE_VERSION` | 새 hash 기록 형식, `v1` 또는 `v2` | `v1` |
| `RCA_OPAQUE_TOKEN_REHASH_ON_AUTHENTICATION` | 인증 성공 시 현재 v2 key로 조건부 재기록 | `false` |

키 식별자는 최대 64자이며 영문, 숫자, `.`, `_`, `-`만 사용합니다. 이전 키는 최대 8개입니다.
각 pepper는 32자 이상의 서로 다른 난수로 만들고 `RCA_ENCRYPTION_SECRET`과 분리합니다.
쉼표를 포함하지 않는 Base64 또는 Base64URL 값을 권장합니다. 모든 pepper는 Secret 또는 외부
secret manager에만 저장하며 로그, Helm CLI 인자, Git에 넣지 않습니다.

## Rolling Rotation

예시는 `key-old`에서 `key-2026-07`로 교체하는 경우입니다. 각 단계에서 모든 Pod가 Ready가
될 때까지 기다린 다음 다음 단계로 이동합니다.

### 1. Reader 준비

먼저 모든 replica를 새 코드로 교체하되 기존 키와 v1 기록을 유지합니다.

```dotenv
RCA_OPAQUE_TOKEN_KEY_ID=key-old
RCA_OPAQUE_TOKEN_PEPPER=<old-pepper>
RCA_OPAQUE_TOKEN_PREVIOUS_KEYS=key-2026-07=<new-pepper>
RCA_OPAQUE_TOKEN_WRITE_VERSION=v1
RCA_OPAQUE_TOKEN_REHASH_ON_AUTHENTICATION=false
```

새 코드는 기존 v1과 새 key id의 v2를 모두 읽습니다. 아직 구버전 바이너리가 남아 있으므로
v2 기록과 재해시는 켜지 않습니다.

### 2. Writer 전환

모든 replica가 새 코드를 실행하는지 확인한 뒤 현재 키와 기록 형식을 바꿉니다.

```dotenv
RCA_OPAQUE_TOKEN_KEY_ID=key-2026-07
RCA_OPAQUE_TOKEN_PEPPER=<new-pepper>
RCA_OPAQUE_TOKEN_PREVIOUS_KEYS=key-old=<old-pepper>
RCA_OPAQUE_TOKEN_WRITE_VERSION=v2
RCA_OPAQUE_TOKEN_REHASH_ON_AUTHENTICATION=false
```

rolling 중 이전 설정의 replica는 새 key를 검증 키로 가지고 있고, 새 설정의 replica는 이전
key를 검증 키로 가집니다. 양쪽 모두 v1/v2를 읽으며 hash를 반대 방향으로 다시 쓰지 않습니다.

### 3. Lazy Rehash 활성화

모든 replica가 `key-2026-07 + v2` 설정인지 확인한 뒤 다음 값을 켭니다.

```dotenv
RCA_OPAQUE_TOKEN_REHASH_ON_AUTHENTICATION=true
```

이후 성공한 bootstrap/node 인증은 DB의 기존 값이 그대로일 때만 현재 v2 hash로 바뀝니다.
동시에 token 회전 또는 폐기가 먼저 완료되면 조건부 UPDATE가 실패하고 최신 credential을 다시
검증하므로 오래된 token이 되살아나지 않습니다.

## Helm

Secret 생성 방식과 관계없이 각 단계에서 revision 값을 함께 바꿔 rolling restart를 시작합니다.
Pod annotation에는 Secret hash를 넣지 않으므로 secret 값의 확인 oracle을 만들지 않습니다.

```yaml
platform:
  config:
    opaqueTokenKeyId: key-2026-07
    opaqueTokenWriteVersion: v2
    opaqueTokenRehashOnAuthentication: false
    opaqueTokenKeyRingRevision: activate-2026-07
```

Secret에는 `RCA_OPAQUE_TOKEN_PEPPER`와 `RCA_OPAQUE_TOKEN_PREVIOUS_KEYS`를 저장합니다. revision은
secret이 아니며 `prepare-2026-07`, `activate-2026-07`, `rehash-2026-07`처럼 단계마다 변경합니다.

```bash
kubectl -n rca-system rollout status deployment/rca-cluster-infra-rca-platform
```

## Retirement And Rollback

이전 key를 제거하기 전에 활성 bootstrap/node token이 모두 현재 v2 key로 전환됐는지 확인합니다.
v1 또는 이전 key hash가 남아 있다면 해당 credential은 아직 인증되지 않았거나 비활성 상태입니다.
필요한 node token을 명시적으로 회전하거나 재등록한 후 이전 key를 제거합니다.

2단계 이후에는 v2를 읽지 못하는 구버전 바이너리로 바로 rollback하지 않습니다. 문제가 생기면
새 코드를 유지한 채 양쪽 key를 검증 키 링에 남기고 현재 writer만 이전 key로 되돌립니다.

이 절차는 새 key를 write에 먼저 사용하고 old key를 read에 유지하는 점진 회전 방식을 따릅니다.
참고: [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html).
