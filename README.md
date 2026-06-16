# Kubernetes Cluster Infra RCA Platform

Kubernetes 클러스터의 노드/Linux 시스템 레벨 장애 원인을 수집·분석하는 AI 기반 RCA 플랫폼입니다.

증상은 대부분 앱 레벨처럼 보이지만 실제 원인은 그 아래에 있습니다. 이 플랫폼은 `NodeNotReady`, `DiskPressure`, `MemoryPressure`, kubelet/runtime 장애, CNI/DNS 문제, kernel error, conntrack 고갈, inode 고갈 같은 노드 레벨 신호를 먼저 봅니다. `CrashLoopBackOff`, `OOMKilled`, HTTP 5xx는 근거로 참고하되 원인으로 보지 않습니다.

## 흐름

```
alert / 수동 요청
  → evidence request
  → node agent 수집
  → 전처리
  → rule-based RCA
  → (선택) LLM 진단
  → policy 분류
  → RCA report
```

LLM은 진단 설명만 합니다. 클러스터를 직접 건드리지 않습니다. 권장 조치는 Policy Engine이 분류하고, UI에 등급과 위험 사유를 함께 표시합니다.

Prometheus/Alertmanager 없이도 동작합니다. Backend가 직접 evidence request를 만들고, agent가 제출한 host evidence로 RCA report를 생성합니다.

## 구성 요소

| 컴포넌트 | 역할 |
| --- | --- |
| Backend API | 클러스터, agent, evidence, RCA job/report, auth, webhook 관리 (FastAPI) |
| Node Agent | Linux host + Kubernetes node evidence 수집 (Python DaemonSet / local) |
| Preprocessor | raw evidence, log를 RCA 분석용 compact JSON으로 정리 |
| Rule Analyzer | node pressure, runtime, kernel, network, CNI, DNS, inode, conntrack rule 분석 |
| LLM Analyzer | GPT, Gemini, Claude, OpenAI-compatible local 모델 연동 (선택) |
| Policy Engine | 권장 조치를 안전/승인 필요/PR 전용/자동 실행 금지로 분류 |
| Web Console | Spring Boot JSP + Bootstrap 5 + React 관리 콘솔 |
| Helm Charts | agent와 backend/web-console 배포용 chart |

## 주요 기능

- session token 기반 API 인증
- 클러스터 등록 + agent 설치 명령 자동 생성
- agent bootstrap token / node token 검증
- DaemonSet 환경 file-mode systemd/journal 수집
- kernel, disk, inode, memory, process, network, conntrack, runtime, kubelet, CNI, DNS, Kubernetes node 상태 수집
- Alertmanager webhook 수신
- rule-based RCA + 선택형 LLM 보강
- Policy Engine (안전/승인 필요/PR 전용/자동 실행 금지)
- PostgreSQL, MariaDB, SQLite 지원

## 스택

- Backend: FastAPI, SQLAlchemy, Alembic
- Agent: Python 3.11+
- Web Console: Spring Boot, JSP, Bootstrap 5, React
- DB: PostgreSQL, MariaDB (개발용 SQLite fallback)
- 배포: Docker Compose, Kubernetes manifests, Helm
- 테스트: pytest, Maven, smoke scripts

## 빠른 시작

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

```
Web Console: http://localhost:8080
Backend API: http://localhost:8000

기본 계정: admin / admin  ← 운영 환경에서는 로그인 후 즉시 변경
```

## 개발 환경

**Backend**

```powershell
.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

**Web Console**

```powershell
cd web-console
mvn spring-boot:run
```

주요 환경 변수: `RCA_API_BASE_URL`, `RCA_PUBLIC_API_BASE_URL`

## 데이터베이스

```powershell
# PostgreSQL
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"

# MariaDB
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"

# SQLite (개발용)
$env:RCA_DATABASE_URL = "sqlite:///./data/rca-dev.db"
```

Schema는 Alembic migration으로 관리합니다.

## Node Agent

**로컬 수집**

```powershell
.venv\Scripts\python.exe -m node_agent.main --collect-local --output evidence.json
```

**DaemonSet 환경**

DaemonSet에서는 host DBus나 `journalctl` 접근이 불안정할 수 있어 file mode가 기본입니다.

```
SYSTEMD_COLLECTOR_MODE=file
```

collector는 `/host/var/log`, `/host/proc`, `/host/sys`, `/host/etc`, `/host/run` 등 mount path에서 파일 기반 evidence를 수집합니다.

## Kubernetes 배포

```bash
# Agent
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-xxx \
  --set secret.agentToken=token-xxx

# Platform (기본: PostgreSQL 포함)
helm template rca charts/cluster-infra-rca-platform

# MariaDB 사용
helm template rca charts/cluster-infra-rca-platform \
  --set database.type=mariadb

# 외부 DB 사용
helm template rca charts/cluster-infra-rca-platform \
  --set database.enabled=false \
  --set-string backend.secret.databaseUrl='postgresql+psycopg://rca:password@postgresql.example:5432/rca'

# LLM API key
  --set-string backend.secret.llmApiKey='...'
```

## 검증

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-dev-check.ps1 -Validate
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-integration-smoke.ps1
```

```bash
# Linux
scripts/linux-dev-check.sh --full
scripts/linux-integration-smoke.sh
```

확인된 상태: Windows full dev check, SUSE Linux Python 3.11 pytest, Helm template (agent/PostgreSQL/MariaDB/external DB/LLM key), agent local collect (`SYSTEMD_COLLECTOR_MODE=file`) 모두 통과.

## 디렉토리 구조

```
backend/        FastAPI backend
node_agent/     node-local collector
web-console/    Spring Boot JSP console
charts/         Helm charts
manifests/      Kubernetes manifests
migrations/     Alembic migrations
tests/          Python tests
docs/           설계 및 운영 문서
scripts/        개발 및 smoke 스크립트
examples/       샘플 페이로드
```

## 문서

- [Architecture](docs/architecture.md)
- [Backend API](docs/backend-api.md)
- [Agent API](docs/agent-api.md)
- [Evidence API](docs/evidence-api.md)
- [Evidence Preprocessing](docs/evidence-preprocessing.md)
- [LLM Analyzer](docs/llm-analyzer.md)
- [Policy Engine](docs/policy-engine.md)
- [Database](docs/database.md)
- [Deployment](docs/deployment.md)
- [Platform Helm Chart](docs/helm-platform-chart.md)
- [Web Console](docs/web-console.md)
