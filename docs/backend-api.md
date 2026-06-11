# Backend API MVP

Backend MVP는 클러스터 등록부터 Alertmanager webhook 수신, Agent evidence request 생성, fake evidence 기반 RCA report 생성까지의 기본 흐름을 제공합니다.

현재 구현은 SQLAlchemy 저장소를 사용합니다. `RCA_DATABASE_URL`에 따라 PostgreSQL, MariaDB, 개발용 SQLite를 선택합니다.

## 실행

```powershell
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

서버 기본 주소는 `http://127.0.0.1:8000`입니다.

## 주요 Endpoint

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health` | backend health check |
| `GET` | `/health/ready` | database 연결을 포함한 readiness check |
| `GET` | `/` | 관리자 콘솔 Web UI |
| `POST` | `/api/auth/signup` | 회원가입 승인 요청 생성 |
| `POST` | `/api/auth/login` | 승인된 사용자 로그인 및 Bearer 세션 발급 |
| `GET` | `/api/auth/me` | 현재 로그인 사용자 조회 |
| `POST` | `/api/auth/logout` | 현재 세션 폐기 |
| `GET` | `/api/admin/users` | 관리자 사용자 목록/승인 대기열 조회 |
| `POST` | `/api/admin/users/{user_id}/approval` | 회원가입 요청 승인 또는 거절 |
| `POST` | `/api/clusters` | 클러스터 등록 |
| `GET` | `/api/clusters` | 등록된 클러스터 목록 |
| `GET` | `/api/clusters/{cluster_id}` | 클러스터 상세 |
| `GET` | `/api/clusters/{cluster_id}/install-command` | Agent 설치 명령어 조회 |
| `GET` | `/api/clusters/{cluster_id}/agent-manifest` | 클러스터별 Agent DaemonSet manifest 생성 |
| `GET` | `/api/clusters/{cluster_id}/agents` | 클러스터 Node Agent 목록 |
| `GET` | `/api/clusters/{cluster_id}/agents/{node_name}` | 특정 Node Agent 상세 |
| `POST` | `/api/agents/register` | Node Agent 등록 |
| `POST` | `/api/agents/heartbeat` | Node Agent heartbeat |
| `POST` | `/api/evidence/requests` | Backend evidence 수집 요청 생성 |
| `GET` | `/api/clusters/{cluster_id}/evidence-requests` | 클러스터 evidence request 목록 |
| `GET` | `/api/evidence/requests/{request_id}` | evidence request 상세 |
| `GET` | `/api/evidence/{evidence_id}` | 저장된 evidence bundle 조회 |
| `POST` | `/api/agents/evidence-requests` | Agent pending evidence request 조회 |
| `POST` | `/api/agents/evidence-responses` | Agent evidence 수집 결과 제출 |
| `POST` | `/api/webhooks/alertmanager` | Alertmanager webhook 수신 |
| `GET` | `/api/rca/jobs` | RCA job 목록 |
| `GET` | `/api/rca/jobs/{job_id}` | RCA job 상세 |
| `GET` | `/api/rca/reports` | RCA report 목록 |
| `GET` | `/api/rca/reports/{report_id}` | RCA report 상세 |

## 인증 요약

승인된 사용자는 로그인 후 Bearer token을 사용합니다.

```text
Authorization: Bearer <access_token>
```

역할별 접근:

- `admin`: 사용자 승인/거절, 클러스터 등록, Agent 설치 명령어, evidence request 생성, 조회 API
- `operator`: 클러스터 등록, Agent 설치 명령어, evidence request 생성, 조회 API
- `viewer`: 클러스터, agent, evidence, RCA job/report 조회 API

초기 관리자 계정이 아직 없을 때를 위해 bootstrap admin token도 유지합니다. 아래 header는 `admin` 권한과 같은 fallback으로 처리합니다. 세션이 만료되었거나 권한이 부족해도 같은 요청에 올바른 bootstrap admin token이 있으면 admin token을 우선 신뢰합니다.

```text
X-Admin-Token: <RCA_ADMIN_APPROVAL_TOKEN>
```

현재 적용 대상:

- `GET /api/admin/users`
- `POST /api/admin/users/{user_id}/approval`
- `POST /api/clusters`
- `GET /api/clusters/{cluster_id}/install-command`
- `POST /api/evidence/requests`

`GET /api/clusters`와 `GET /api/clusters/{cluster_id}`는 `bootstrap_token`을 응답하지 않습니다.
Agent 설치용 bootstrap token은 cluster 생성 응답과 admin token이 있는 install-command 응답에서만 확인합니다.

로그인 세션은 `user_sessions`에 token hash와 만료 시간을 저장합니다. raw access token은 응답으로 한 번만 내려가며 DB에는 저장하지 않습니다.

Agent API는 두 단계 token을 사용합니다.

1. `POST /api/agents/register`는 cluster `bootstrap_token`을 `agent_token`으로 검증합니다.
2. 등록 성공 시 node별 `node_token`을 발급합니다.
3. Heartbeat, evidence poll, evidence submit은 `agent_token + node_token + node_name`이 모두 맞아야 합니다.

Alertmanager webhook은 사용자 세션이나 admin token을 쓰지 않고 별도 webhook token을 검증합니다.

```text
Authorization: Bearer <RCA_WEBHOOK_TOKEN>
```

일반 HTTP 클라이언트나 proxy 연동에서는 아래 header도 사용할 수 있습니다.

```text
X-Webhook-Token: <RCA_WEBHOOK_TOKEN>
```

## API 흐름

1. 사용자가 `POST /api/auth/signup` 또는 Web UI에서 가입 요청을 만듭니다.
2. 관리자가 `RCA_ADMIN_APPROVAL_TOKEN` 또는 admin 세션으로 `/api/admin/users`에서 승인 대기열을 조회합니다.
3. 관리자가 `/api/admin/users/{user_id}/approval`로 가입 요청을 승인하거나 거절합니다.
4. 승인된 사용자가 `/api/auth/login`으로 로그인하고 Bearer token을 받습니다.
5. `admin` 또는 `operator`가 `POST /api/clusters`로 클러스터를 등록합니다.
6. 응답의 `cluster_id`를 확인합니다.
7. `admin` 또는 `operator`가 `GET /api/clusters/{cluster_id}/install-command`로 Agent 설치 명령어를 확인합니다.
8. 운영 환경에서는 `backend_url`, `image`, `namespace` query parameter를 넣어 클러스터별 manifest URL을 생성합니다.
9. Agent가 `/api/agents/register`로 자신을 등록하고 node별 `node_token`을 받습니다.
10. Agent가 `agent_token + node_token`으로 `/api/agents/heartbeat` 상태를 갱신합니다.
11. Backend가 권한 검증 후 `/api/evidence/requests`로 특정 노드 수집 요청을 만듭니다.
12. Agent가 `agent_token + node_token`으로 `/api/agents/evidence-requests` pending request를 조회합니다.
13. Agent가 `agent_token + node_token`으로 `/api/agents/evidence-responses` 수집 결과를 제출합니다.
14. Alertmanager payload의 `labels.cluster_id`에 등록된 `cluster_id`를 넣고 `Authorization: Bearer <RCA_WEBHOOK_TOKEN>` header와 함께 `/api/webhooks/alertmanager`로 전송합니다.
15. 해당 노드 Agent가 등록되어 있으면 Backend가 pending evidence request를 생성합니다.
16. Agent가 evidence request를 poll하고 수집 결과를 제출합니다.
17. evidence submit이 `completed`이면 Backend가 RCA job과 report를 생성합니다.
18. 아직 Agent가 없는 노드는 기존 MVP 흐름대로 fake evidence 기반 RCA job과 report를 생성합니다.
19. `/api/rca/reports/{report_id}`에서 결과를 조회합니다.

## Agent manifest 생성

```text
GET /api/clusters/{cluster_id}/agent-manifest?backend_url=https://rca.example.com&image=ghcr.io/acme/cluster-infra-rca-agent:v1&namespace=rca-system
```

응답은 `kubectl apply -f`로 적용할 수 있는 Kubernetes JSON `List`입니다. Secret은 manifest에 포함하지 않습니다. 설치 명령어가 별도로 `cluster-id`와 `agent-token` Secret을 생성합니다.

검증:

- `backend_url`은 `http` 또는 `https` absolute URL이어야 합니다.
- `namespace`는 Kubernetes DNS label이어야 합니다.
- `image`는 공백 없는 container image reference여야 합니다.
- timeout 값은 허용 범위 밖이면 `422`를 반환합니다.

## 예시 요청

```json
{
  "name": "prod-cluster",
  "environment": "prod",
  "description": "Production Kubernetes cluster"
}
```

Alertmanager webhook payload는 [examples/alertmanager-webhook.json](../examples/alertmanager-webhook.json)을 참고합니다.

DB 설정은 [docs/database.md](database.md)를 참고합니다.
Agent API 계약은 [docs/agent-api.md](agent-api.md)를 참고합니다.
Evidence API 계약은 [docs/evidence-api.md](evidence-api.md)를 참고합니다.
Web UI 구성은 [docs/web-console.md](web-console.md)를 참고합니다.

## 현재 MVP 범위

- Alertmanager webhook은 등록된 Agent가 있는 노드에 대해 evidence request를 생성합니다.
- Web UI는 FastAPI 정적 자산으로 `/`에서 제공됩니다.
- 회원가입 요청은 `pending_approval`로 저장되고, 관리자 승인 후 `active`가 됩니다.
- 실제 Node Agent가 없는 노드는 `FakeEvidenceCollector`가 결정론적 evidence bundle을 생성합니다.
- Node Agent 등록과 heartbeat API는 구현되어 있습니다.
- Agent evidence request/response API는 구현되어 있습니다.
- Agent가 completed evidence를 제출하면 RCA job과 report가 자동 생성됩니다.
- RCA report와 RCA job은 같은 저장소 호출 안에서 함께 저장되어 report/job 짝이 어긋나지 않도록 처리합니다.
- 클러스터별 Agent manifest 생성 API가 구현되어 있습니다.
- `Evidence Preprocessor`가 raw collector output을 LLM 입력용 JSON으로 정리하고 evidence quality, incident focus, component health, log summary를 추가합니다.
- `LLM Analyzer` adapter는 `RCA_LLM_PROVIDER` 설정에 따라 OpenAI, Claude, Gemini, OpenAI-compatible, self-hosted endpoint를 선택할 수 있습니다. 기본값은 비활성화입니다.
- LLM 분석이 활성화되면 provider 응답은 RCA report의 `llm_analysis` section에 들어가며, action suggestion은 다시 `PolicyEngine`을 통과합니다.
- `RuleBasedRcaAnalyzer`가 evidence field에서 derived signal을 추출하고 원인 후보, confidence, resolution checklist를 생성합니다.
- `PolicyEngine`이 권장 조치를 `AUTO_SAFE`, `APPROVAL_REQUIRED`, `GITOPS_PR_ONLY`, `NEVER_AUTO_EXECUTE`, `MANUAL_INVESTIGATION`으로 분류하고 `automation_allowed`, `automation_mode`, `guardrails`, `risk_factors`를 함께 제공합니다.
- 조치 실행 API는 아직 제공하지 않습니다.
- DB schema 변경은 Alembic migration으로 관리합니다.
