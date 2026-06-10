# Backend API MVP

Backend MVP는 클러스터 등록부터 Alertmanager webhook 수신, RCA job 생성, fake evidence 기반 report 생성까지의 기본 흐름을 제공합니다.

현재 구현은 SQLAlchemy 저장소를 사용합니다. `RCA_DATABASE_URL`에 따라 PostgreSQL, MariaDB, 개발용 SQLite를 선택합니다.

## 실행

```powershell
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

서버 기본 주소는 `http://127.0.0.1:8000`입니다.

## 주요 Endpoint

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health` | backend health check |
| `POST` | `/api/clusters` | 클러스터 등록 |
| `GET` | `/api/clusters` | 등록된 클러스터 목록 |
| `GET` | `/api/clusters/{cluster_id}` | 클러스터 상세 |
| `GET` | `/api/clusters/{cluster_id}/install-command` | Agent 설치 명령어 조회 |
| `POST` | `/api/webhooks/alertmanager` | Alertmanager webhook 수신 |
| `GET` | `/api/rca/jobs` | RCA job 목록 |
| `GET` | `/api/rca/jobs/{job_id}` | RCA job 상세 |
| `GET` | `/api/rca/reports` | RCA report 목록 |
| `GET` | `/api/rca/reports/{report_id}` | RCA report 상세 |

## API 흐름

1. `POST /api/clusters`로 클러스터를 등록합니다.
2. 응답의 `cluster_id`를 확인합니다.
3. `GET /api/clusters/{cluster_id}/install-command`로 Agent 설치 명령어를 확인합니다.
4. Alertmanager payload의 `labels.cluster_id`에 등록된 `cluster_id`를 넣어 `/api/webhooks/alertmanager`로 전송합니다.
5. Backend가 RCA job과 RCA report를 생성합니다.
6. `/api/rca/reports/{report_id}`에서 결과를 조회합니다.

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

## 현재 MVP 범위

- 실제 Node Agent 연동 전까지 `FakeEvidenceCollector`가 결정론적 evidence bundle을 생성합니다.
- `RuleBasedRcaAnalyzer`가 alert type에 따라 원인 후보와 confidence를 생성합니다.
- `PolicyEngine`이 권장 조치를 `AUTO_SAFE`, `APPROVAL_REQUIRED`, `GITOPS_PR_ONLY`, `NEVER_AUTO_EXECUTE`, `MANUAL_INVESTIGATION`으로 분류합니다.
- LLM 분석은 아직 연결하지 않았습니다.
- 조치 실행 API는 아직 제공하지 않습니다.
- DB schema migration은 아직 Alembic이 아니라 startup auto-create 방식입니다.
