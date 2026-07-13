# GitOps Change Tracking

승인된 operational catalog override를 GitHub/Gitea draft pull request 또는 GitLab draft merge request로 만들고, PR/MR 및 배포 결과를 Platform에서 추적합니다. Platform과 Node Agent는 Kubernetes 또는 host 설정을 직접 변경하지 않습니다.

## Configuration

```bash
RCA_GITOPS_ENABLED=true
RCA_GITOPS_PROVIDER=github # github | gitlab | gitea
RCA_GITOPS_REPOSITORY=namespace/repository
RCA_GITOPS_BASE_BRANCH=main
RCA_GITOPS_FILE_PATH=ops/catalog/operational-catalog.override.json
RCA_GITOPS_TOKEN=...
RCA_GITOPS_WEBHOOK_SECRET=...
```

Token과 webhook secret은 DB에 저장하지 않습니다. Docker/Kubernetes Secret 또는 외부 secret manager로 주입합니다. Production profile에서는 지원 provider, HTTPS API URL, namespace/repository 경로, token, webhook secret을 모두 검증합니다.

`RCA_GITOPS_API_BASE_URL`을 비우면 GitHub/GitLab provider 기본값을 사용합니다. Gitea는 self-hosted 주소가 환경마다 다르므로 `/api/v1`을 포함한 URL을 반드시 지정합니다.

| Provider | Default API URL | Repository example |
| --- | --- | --- |
| GitHub | `https://api.github.com` | `owner/repository` |
| GitLab | `https://gitlab.com/api/v4` | `group/subgroup/repository` |
| Gitea | 명시적으로 지정 | `owner/repository` |

## GitHub

권장 fine-grained token 권한:

- Contents: Read and write
- Pull requests: Read and write
- Metadata: Read

GitHub webhook URL:

```text
https://rca.example.com/api/webhooks/gitops/github
```

Content type은 `application/json`, secret은 `RCA_GITOPS_WEBHOOK_SECRET`과 동일하게 설정합니다. `Pull requests` event를 구독합니다.

## GitLab

권장 project access token scope는 `api`입니다. 토큰 역할과 만료 정책은 저장소 운영 정책에 맞게 최소화합니다.

GitLab webhook URL:

```text
https://rca.example.com/api/webhooks/gitops/gitlab
```

Secret token은 `RCA_GITOPS_WEBHOOK_SECRET`과 동일하게 설정하고 `Merge request events`를 구독합니다. Platform은 `X-Gitlab-Token`을 상수 시간으로 비교하고 `X-Gitlab-Event-UUID`를 delivery ID로 사용합니다.

## Gitea

Gitea API URL 예시:

```text
https://git.example.com/api/v1
```

Repository write와 pull request 생성 권한이 있는 access token을 사용합니다. Webhook URL은 다음과 같습니다.

```text
https://rca.example.com/api/webhooks/gitops/gitea
```

Secret은 `RCA_GITOPS_WEBHOOK_SECRET`과 동일하게 설정하고 `Pull request events`를 구독합니다. Platform은 raw body의 HMAC-SHA256을 `X-Gitea-Signature`와 비교하고 `X-Gitea-Delivery`를 replay 방지 ID로 사용합니다.

## Workflow

```text
catalog override preview
  -> draft 저장
  -> ADMIN/APPROVER 승인
  -> ADMIN/OPERATOR가 GitOps PR 생성 확인
  -> provider branch, commit, draft PR/MR 생성
  -> webhook으로 open/merged/closed 상태 동기화
  -> merged 이후 배포 시작/성공/실패 기록
  -> 필요 시 rollback reference 기록
```

동일 draft와 provider 조합은 DB unique key로 한 번만 선점합니다. 재호출 또는 동시 호출은 기존 change를 반환하며 추가 PR을 만들지 않습니다. 원격 호출 실패도 `failed` 상태로 보존해 임의 재시도로 중복 PR이 생기지 않게 합니다.

GitHub/Gitea webhook은 raw request body의 HMAC-SHA256 signature를 검증하고 GitLab webhook은 secret token을 검증합니다. 세 provider 모두 constant-time 비교를 사용합니다. delivery ID는 한 번만 저장하며 replay는 `409 Conflict`로 거절하고 audit event를 남깁니다.

## API

| Method | Path | Roles |
| --- | --- | --- |
| `POST` | `/api/v1/catalog/overrides/drafts/{draftId}/gitops-changes` | `ADMIN`, `OPERATOR` |
| `GET` | `/api/v1/gitops/changes` | 모든 Console role |
| `GET` | `/api/v1/gitops/changes/{changeId}` | 모든 Console role |
| `POST` | `/api/v1/gitops/changes/{changeId}/outcome` | `ADMIN`, `OPERATOR` |
| `POST` | `/api/webhooks/gitops/github` | GitHub HMAC signature |
| `POST` | `/api/webhooks/gitops/gitlab` | GitLab secret token |
| `POST` | `/api/webhooks/gitops/gitea` | Gitea HMAC signature |

PR이 `merged`가 되기 전에는 배포 결과를 기록할 수 없습니다. 허용 상태 전이는 다음과 같습니다.

```text
pending -> in_progress -> succeeded | failed -> rolled_back
```

`verification_result`에는 canary 결과와 checksum 확인 내용을, `rollback_reference`에는 rollback PR 또는 변경 티켓을 기록합니다.
