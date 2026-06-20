# Platform API

Spring Boot 플랫폼은 Web Console과 API를 포트 `8080`에서 함께 제공합니다.

## Authentication

- 사용자 API: `Authorization: Bearer <session-token>`
- Agent 등록: cluster bootstrap token
- Agent heartbeat/evidence: bootstrap token + node token
- Alertmanager: `Authorization: Bearer <webhook-token>` 또는 `X-Webhook-Token`

기본 계정은 `admin/admin`이며 최초 로그인 후 변경해야 합니다.

## Endpoints

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/auth/login` | 로그인 |
| GET | `/api/auth/me` | 현재 사용자 |
| POST | `/api/auth/change-password` | 비밀번호 변경 |
| GET/POST | `/api/clusters` | 클러스터 조회/등록 |
| DELETE | `/api/clusters/{id}` | 클러스터 삭제 |
| GET | `/api/clusters/{id}/install-command` | Agent 설치 명령 |
| GET | `/api/clusters/{id}/agent-manifest` | Agent manifest |
| GET | `/api/clusters/{id}/agents` | 노드 Agent 상태 |
| POST | `/api/clusters/{id}/collection-runs` | 수동 수집 요청 |
| POST | `/api/agents/register` | Agent 등록 |
| POST | `/api/agents/heartbeat` | Agent heartbeat |
| POST | `/api/agents/evidence-requests` | Agent 요청 poll |
| POST | `/api/agents/evidence-responses` | Agent evidence 제출 |
| POST | `/api/webhooks/alertmanager` | Alertmanager webhook |
| GET | `/api/rca/reports` | RCA 보고서 목록 |
| GET | `/api/rca/reports/{id}` | RCA 보고서 상세 |
| GET | `/api/rca/reports/export` | 보고서 JSON export (`ADMIN`, `OPERATOR`) |
| POST | `/api/rca/reports/{id}/actions/{index}/execute` | 허용된 read-only 후속 수집 |
| POST | `/api/rca/action-requests/{id}/approve` | 수동 처리 또는 GitOps 검토 승인 |
| POST | `/api/rca/action-requests/{id}/reject` | 조치 요청 거절 |
| POST | `/api/rca/action-requests/{id}/complete-manual` | 외부 절차 완료 기록 |

`/health`와 `/health/ready`는 인증 없이 사용할 수 있습니다.

운영 metric은 `/actuator/metrics`와 `/actuator/prometheus`에서 제공합니다. 사용자 session은
`ADMIN`, `OPERATOR`, `AUDITOR` 역할이 필요하며 Prometheus scraper는 `RCA_METRICS_TOKEN`을
Bearer token 또는 `X-Metrics-Token` header로 전달할 수 있습니다.

## Action Execution

UI의 실행 버튼은 임의의 shell 명령을 실행하지 않습니다. 현재 자동화 허용 대상은 Rule-based `AUTO_SAFE` read-only evidence 요청뿐입니다.

다음 항목은 실행되지 않습니다.

- LLM이 제안한 모든 조치
- 서비스 재시작, cordon, drain, disk cleanup
- GitOps 검토가 필요한 설정 변경
- reboot, workload 삭제, etcd membership 변경

승인은 Agent 실행 권한을 부여하지 않습니다. 명령 preview와 YAML patch는 runbook 또는
GitOps PR 안내이며, 실제 처리는 플랫폼 밖에서 수행한 후 완료 상태만 기록합니다.
