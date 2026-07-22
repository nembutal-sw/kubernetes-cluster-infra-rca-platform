# API Security Contract

Web Console과 Backend API의 기본 보안 계약을 정리한다. 실제 정적 검증은 `scripts/verify-api-contract.py`가 담당하고, CI와 release readiness gate에서 실행한다.

## Default Rules

- `/api/**`는 기본적으로 로그인 세션 인증이 필요하다.
- 사용자 API는 controller method 단위로 `@PreAuthorize`를 명시한다.
- Agent, Webhook, Manifest처럼 로그인 세션을 쓰지 않는 API는 전용 인증 필터를 사용한다.
- Export, evidence bundle, agent token rotation, cluster delete 같은 민감 API는 역할 범위를 좁게 유지한다.
- 신규 API는 `/api/v1/...` 경로를 우선 제공하고, 기존 경로는 호환 목적으로만 유지한다.
- 역할별 권한은 [rbac-matrix.md](rbac-matrix.md)를 기준으로 검증한다.

## Custom Guarded Endpoints

아래 endpoint는 `permitAll` 목록에 있어도 공개 API가 아니다. Spring Security 기본 세션 인증 대신 전용 필터가 먼저 인증한다.

| Endpoint | Guard |
| --- | --- |
| `/api/agents/register` | `AgentAuthenticationFilter` |
| `/api/agents/heartbeat` | `AgentAuthenticationFilter` |
| `/api/agents/evidence-requests` | `AgentAuthenticationFilter` |
| `/api/agents/evidence-responses` | `AgentAuthenticationFilter` |
| `/api/agents/realtime-events` | `AgentAuthenticationFilter` |
| `/api/agents/action-executions` | `AgentAuthenticationFilter` |
| `/api/agents/action-results` | `AgentAuthenticationFilter` |
| `/api/webhooks/alertmanager` | `WebhookAuthenticationFilter` |
| `/api/clusters/{clusterId}/agent-manifest` | `ManifestAccessFilter` |

## Session-only Endpoints

아래 endpoint는 로그인한 사용자의 자기 계정 작업이다. 별도 역할보다 세션 인증 자체가 기본 계약이다.

| Endpoint | Purpose |
| --- | --- |
| `/api/auth/me` | 현재 사용자 조회 |
| `/api/auth/logout` | 현재 세션 로그아웃 |
| `/api/auth/change-password` | 현재 사용자 비밀번호 변경 |
| `/api/auth/change-login-id` | 현재 사용자 로그인 ID 변경 |

## Sensitive Role Rules

- `VIEWER`는 변경 API에 접근할 수 없다.
- `VIEWER`, `APPROVER`는 report export와 evidence bundle download 권한을 갖지 않는다.
- Agent bootstrap token rotation/revocation과 node token revocation은 `ADMIN` 전용이다.
- Agent enrollment profile 변경은 `ADMIN` 전용이고 조회는 `ADMIN`, `OPERATOR`, `VIEWER`만 가능하다.
- Cluster delete는 `ADMIN` 전용이다.
- Action workflow는 agent 자동 실행이 아니라 승인, 거절, 감사, 수동 처리 완료 기록을 중심으로 유지한다.

## CI Gate

로컬 실행:

```bash
python3 scripts/verify-api-contract.py
```

검증 항목:

- controller route inventory 생성
- `/api/**` route의 `@PreAuthorize` 누락 감지
- Agent route와 `AgentAuthenticationFilter` coverage 일치 확인
- Webhook route와 `WebhookAuthenticationFilter` coverage 일치 확인
- Manifest endpoint와 `ManifestAccessFilter` coverage 확인
- custom guarded endpoint의 `SecurityConfig` permit entry 확인
- 민감 export role 범위 감지
- versioned platform info endpoint 유지 확인

통합 회귀 테스트:

- `SecurityBoundaryRegressionTests`
- Webhook token header/Bearer 인증과 실패 감사로그
- protocol v2의 등록 전용 bootstrap Bearer와 node-scoped Bearer 검증
- Kubernetes TokenReview의 audience, ServiceAccount, Pod UID, node binding 검증과 bootstrap fallback 차단
- protocol v1 body credential 호환 및 header/body 충돌 거부
- Agent endpoint 인증 선차단
- Agent manifest의 사용자 인증/1회성 manifest token 검증
- manifest token query value redaction 확인
