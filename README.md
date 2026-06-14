# Kubernetes Cluster Infra RCA Platform

Kubernetes 애플리케이션 장애가 아니라, 클러스터 노드와 Linux 시스템 레벨 장애의 원인을 수집하고 분석하는 AI 기반 RCA 플랫폼입니다.

운영 중 처음 보이는 증상은 대부분 Kubernetes 문제처럼 보입니다. `NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`, kubelet/runtime 장애, CNI/DNS 문제, API Server 지연, 디스크 I/O 병목, inode 고갈, conntrack 고갈, kernel error, systemd unit 장애, NIC link flap 같은 신호를 중심으로 노드 레벨 증거를 수집합니다.

`CrashLoopBackOff`, `ImagePullBackOff`, Pod `OOMKilled`, HTTP 5xx, Service endpoint 없음, Ingress 설정 오류 같은 애플리케이션 레벨 신호는 보조 근거로 봅니다. 이 신호들은 원인이라기보다 노드나 네트워크 장애가 위쪽 레이어에서 드러난 증상일 수 있습니다.

## How It Works

```text
alert or manual request
  -> evidence request
  -> node agent collection
  -> evidence preprocessing
  -> rule-based RCA
  -> optional LLM diagnosis
  -> policy classification
  -> RCA report
```

LLM은 진단과 설명만 담당합니다. 클러스터를 직접 수정하지 않습니다. 권장 조치는 Policy Engine이 먼저 분류하고, UI는 해당 정책 등급과 위험 사유를 함께 보여줍니다.

Prometheus와 Alertmanager는 선택 사항입니다. 모니터링 도구가 없는 환경에서도 backend가 등록된 node agent에 evidence request를 만들고, agent가 제출한 host evidence로 RCA report를 생성할 수 있습니다.

## Components

| Component | Role |
| --- | --- |
| Backend API | 클러스터, agent, evidence, RCA job/report, auth, webhook을 관리하는 FastAPI 서비스 |
| Node Agent | Linux host와 Kubernetes node evidence를 수집하는 Python DaemonSet/local collector |
| Preprocessor | raw evidence와 log를 RCA 분석용 compact JSON으로 정리 |
| Rule Analyzer | node pressure, runtime, kernel, network, CNI, DNS, inode, conntrack 문제를 rule 기반으로 분석 |
| LLM Analyzer | GPT, Gemini, Claude, OpenAI-compatible local model을 붙일 수 있는 선택형 분석기 |
| Policy Engine | 권장 조치를 정책 등급으로 분류하고 위험한 자동화를 차단 |
| Web Console | Spring Boot JSP 기반 관리자 콘솔. Bootstrap 5와 React로 동적 화면 구성 |
| Helm Charts | agent와 backend/web-console platform을 Kubernetes에 배포하기 위한 chart |

## Current Features

- 기본 관리자 계정: `admin/admin`
- session token 기반 backend API 보호
- 로그인 후 기본 계정 비밀번호 변경
- 클러스터 등록과 agent 설치 명령 생성
- 인증이 필요한 `/api/clusters/{cluster_id}/agent-manifest`
- agent bootstrap token과 node token 검증
- Node agent local collection과 DaemonSet collection
- DaemonSet 환경을 고려한 file-mode systemd/journal collection
- kernel, disk, inode, memory, process, network, conntrack, runtime, kubelet, CNI, DNS, Kubernetes node 상태 수집
- Alertmanager webhook 수신
- Prometheus 없이 backend 주도 evidence request 생성
- rule-based RCA report 생성
- 선택형 LLM RCA 보강
- 안전 조치, 승인 필요 조치, PR 전용 조치, 자동 실행 금지 조치를 구분하는 Policy Engine
- cluster, agent, webhook, evidence, RCA report, policy 결과, 언어 설정을 보는 Web UI
- PostgreSQL, MariaDB, SQLite 개발 환경 지원
- PostgreSQL 또는 MariaDB를 chart 내부에 함께 배포할 수 있는 platform Helm chart

## Stack

- Backend: FastAPI, SQLAlchemy, Alembic
- Agent: Python 3.11+
- Web Console: Spring Boot, JSP, Bootstrap 5, React
- Database: PostgreSQL, MariaDB, SQLite 개발용 fallback
- Deployment: Docker Compose, Kubernetes manifests, Helm
- Tests: pytest, Maven test, smoke scripts

## Quick Start

Docker Compose로 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

기본 접속 주소입니다.

```text
Web Console: http://localhost:8080
Backend API: http://localhost:8000
```

기본 로그인 계정입니다.

```text
username: admin
password: admin
```

처음 로그인한 뒤 운영 환경에서는 비밀번호를 변경해야 합니다.

## Backend Development

Backend 개발 서버 실행 예시입니다.

```powershell
.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

## Web Console Development

Web Console은 Spring Boot 기반으로 실행합니다.

```powershell
cd web-console
mvn spring-boot:run
```

Web Console에서 사용하는 주요 환경 변수입니다.

```text
RCA_API_BASE_URL
RCA_PUBLIC_API_BASE_URL
```

## Database

PostgreSQL 예시입니다.

```powershell
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"
```

MariaDB 예시입니다.

```powershell
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"
```

개발용 SQLite fallback 예시입니다.

```powershell
$env:RCA_DATABASE_URL = "sqlite:///./data/rca-dev.db"
```

운영 환경에서는 Alembic migration을 기준으로 schema를 관리합니다.

## Node Agent

로컬에서 host evidence를 수집할 수 있습니다.

```powershell
.venv\Scripts\python.exe -m node_agent.main --collect-local --output evidence.json
```

DaemonSet 배포에서는 systemd/journal 접근을 기본적으로 file mode로 처리합니다.

```text
SYSTEMD_COLLECTOR_MODE=file
```

이 설정은 DaemonSet 내부에서 host DBus나 `journalctl` 접근이 안정적으로 되지 않는 환경을 고려한 기본값입니다. collector는 `/host/var/log`, `/host/proc`, `/host/sys`, `/host/etc`, `/host/run` 같은 mount path에서 읽을 수 있는 파일 기반 evidence를 우선 수집합니다.

## Kubernetes Deployment

Agent chart 렌더링 예시입니다.

```bash
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-xxx \
  --set secret.agentToken=token-xxx
```

기본 platform chart는 PostgreSQL을 함께 렌더링합니다.

```bash
helm template rca charts/cluster-infra-rca-platform
```

MariaDB를 사용하려면 `database.type`을 변경합니다.

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.type=mariadb
```

외부 DB를 사용할 수도 있습니다.

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.enabled=false \
  --set-string backend.secret.databaseUrl='postgresql+psycopg://rca:password@postgresql.example:5432/rca'
```

LLM API key는 다음 값으로 전달합니다.

```bash
--set-string backend.secret.llmApiKey='...'
```

chart는 이 값을 backend가 읽는 `RCA_LLM_API_KEY`로 노출합니다.

## Validation

Windows 검증 명령입니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-dev-check.ps1 -Validate
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-integration-smoke.ps1
```

Linux 검증 명령입니다.

```bash
scripts/linux-dev-check.sh --full
scripts/linux-integration-smoke.sh
```

최근 확인한 상태입니다.

- Windows full dev check: 통과
- suse Linux Python 3.11 full pytest: 통과
- suse Helm template checks: agent, PostgreSQL, MariaDB, external DB, LLM key 통과
- suse agent local collect with `SYSTEMD_COLLECTOR_MODE=file`: 통과

## Repository Layout

```text
backend/        FastAPI backend
node_agent/     node-local collector
web-console/    Spring Boot JSP console
charts/         Helm charts
manifests/      Kubernetes manifests
migrations/     Alembic migrations
tests/          Python tests
docs/           design and operation docs
scripts/        dev and smoke scripts
examples/       sample payloads
```

## Docs

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
