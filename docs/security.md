# Security

## Request Authentication

외부 입력 경계의 인증은 Spring Security filter chain에서 먼저 처리합니다.

| Endpoint | Authentication |
| --- | --- |
| `/api/agents/register` | cluster bootstrap token |
| `/api/agents/**` | cluster bootstrap token + node token |
| `/api/webhooks/alertmanager` | `X-Webhook-Token` 또는 Bearer token |
| `/api/clusters/{id}/agent-manifest` | 로그인 세션 또는 cluster bootstrap token |

Agent 요청 본문은 필터에서 한 번 캐시한 뒤 컨트롤러에 그대로 전달합니다. 인증 실패 감사
로그에는 endpoint, cluster, node, 실패 사유만 기록하며 token 값은 기록하지 않습니다.

## RBAC

| Role | Scope |
| --- | --- |
| `ADMIN` | 전체 관리 및 승인 |
| `OPERATOR` | 클러스터 운영, 수집, incident 상태 변경 |
| `VIEWER` | 일반 조회 |
| `APPROVER` | RCA 보고서와 승인 대기 조치 조회, 승인 또는 거절 |
| `AUDITOR` | audit event 조회 |

Mutation API는 `VIEWER`에게 허용하지 않습니다. 조치 승인 API는 `ADMIN`과 `APPROVER`,
audit 조회 API는 `ADMIN`과 `AUDITOR`만 호출할 수 있습니다.

보고서 JSON과 evidence bundle export는 `ADMIN`과 `OPERATOR`만 허용합니다.
`VIEWER`와 `APPROVER`는 화면 조회는 가능하지만 다운로드 또는 원문 복사 기능을 사용할 수
없습니다. 승인 완료 후 실제 처리는 `ADMIN` 또는 `OPERATOR`가 외부 runbook/GitOps 절차로
수행하고 수동 완료를 기록합니다.

## Production Fail-fast

`prod` 또는 `production` profile에서는 다음 설정이 안전하지 않으면 애플리케이션 시작을
중단합니다.

- 기본 admin 비밀번호
- 비어 있거나 개발용인 webhook token
- 비어 있거나 예제값인 DB 비밀번호
- 1시간 미만 또는 24시간 초과 session TTL
- HTTPS가 아닌 public API URL
- 활성화된 LLM의 provider, model, Spring AI model 또는 API key 누락
- demo mode 활성화 또는 audit 비활성화
- 비어 있거나 개발용인 encryption secret

최소 운영 설정 예:

```text
SPRING_PROFILES_ACTIVE=prod
RCA_PUBLIC_API_BASE_URL=https://rca.example.com
RCA_DEFAULT_ADMIN_PASSWORD=<secret>
RCA_WEBHOOK_TOKEN=<secret>
RCA_DB_PASSWORD=<secret>
RCA_ENCRYPTION_SECRET=<secret>
RCA_AUDIT_ENABLED=true
RCA_DEMO_ENABLED=false
```

Secret 값은 저장소나 Helm values 파일에 직접 넣지 말고 Kubernetes Secret 또는 External
Secrets를 통해 주입합니다.

Metrics endpoint는 사용자 session 또는 `RCA_METRICS_TOKEN`으로 인증합니다. 운영 profile에서
observability가 활성화된 경우 metrics token이 비어 있거나 예제값이면 시작을 차단합니다.
