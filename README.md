# Kubernetes Cluster Infra RCA Platform

Kubernetes 장애처럼 보이는 노드/Linux 시스템 장애를 수집하고 분석하는 RCA 플랫폼.

운영 중 처음 보이는 증상은 대부분 비슷하다. `NodeNotReady`, `Pod Pending`, CoreDNS 불안정, API Server 지연처럼 보이지만 실제 원인은 disk I/O, containerd hang, kubelet 문제, conntrack 고갈, NIC flap 같은 노드 레벨에 있을 수 있다.

이 프로젝트는 운영자가 노드에 접속해서 확인하던 초기 RCA 절차를 자동화한다. Alertmanager 알림을 기준으로 관련 노드와 시간대의 증거를 모으고, LLM이 읽기 쉬운 JSON으로 정리한 뒤 RCA report로 남긴다.

목표:

```text
alert -> evidence request -> node agent collect -> preprocess -> LLM diagnosis -> policy classify -> report
```

LLM은 진단과 설명만 담당한다. 조치 실행 여부는 Policy Engine과 운영자 승인 흐름에서 판단한다.
Prometheus/Alertmanager는 선택 사항이다. 모니터링 도구가 없는 환경에서는 backend가 등록된 node agent에 read-only collection을 요청하고, agent가 제출한 evidence로 동일한 RCA 분석을 수행한다.

## Scope

주요 대상:

- `NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`
- kubelet, containerd, CNI, CoreDNS
- etcd latency, API Server 지연
- disk I/O, inode, conntrack 고갈
- kernel log error, systemd unit 장애
- NIC link flap, DNS, MTU 문제

보조 신호:

- `CrashLoopBackOff`, `ImagePullBackOff`, Pod `OOMKilled`
- HTTP 5xx, Service endpoint 없음, Ingress 설정 오류

보조 신호는 애플리케이션 장애로 단정하지 않는다. 노드나 네트워크 장애가 위쪽 레이어에서 위 증상으로 보일 수 있기 때문에 RCA 근거로 함께 본다.

## Components

| Component | Role |
| --- | --- |
| Backend API | 클러스터, 에이전트, 웹훅, RCA job/report 관리 |
| Node Agent | 노드 로그, systemd, kernel, disk, memory, network, runtime 상태 수집 |
| Preprocessor | raw evidence를 LLM 입력 JSON으로 정리 |
| LLM Analyzer | 원인 후보, 근거, 추가 확인 명령 정리 |
| Policy Engine | 권장 조치 위험도 분류 |
| Web Console | 클러스터 등록, 웹훅 설정, 리포트 조회 |

사용자 Web UI는 Spring Boot Web Console 하나만 사용한다. FastAPI는 API, Swagger, agent/webhook 연동만 담당한다.

Node Agent는 노드를 수정하지 않는다. 수집 가능한 정보를 읽고, 권한 부족이나 명령어 부재로 실패한 항목은 evidence 안에 오류로 남긴다.

Preprocessor는 로그를 그대로 LLM에 넘기지 않는다. 반복 로그, 낮은 가치의 필드, 형식이 다른 웹/시스템 로그를 정리해서 주요 항목 중심의 JSON으로 만든다.

Policy Engine은 LLM 결과를 그대로 신뢰하지 않는다. 권장 조치를 안전한 조치, 승인 필요 조치, PR 제안 수준, 자동 실행 금지 항목으로 분류한다.

## Stack

- Backend: FastAPI, SQLAlchemy, Alembic
- Web: Spring Boot, JSP, Bootstrap 5, React
- DB: PostgreSQL, MariaDB, SQLite fallback
- Agent: Python node-local collector, DaemonSet
- Test: pytest, Maven test, integration smoke scripts

## Features

- 기본 관리자 계정 `admin/admin`
- session token 기반 로그인 및 API 접근 제어
- 로그인 후 관리자 비밀번호 변경
- cluster 등록 및 agent 설치 명령 조회
- node token 기반 agent 인증
- Alertmanager webhook 수신
- Prometheus 없이 backend-initiated evidence collection
- evidence request/response API
- RCA job/report 생성
- LLM provider adapter
- Policy Engine guardrail
- Helm chart / DaemonSet manifest 초안

## Quick Start

Docker Compose:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

접속:

```text
Web Console: http://localhost:8080
Backend API: http://localhost:8000
```

개발 모드:

Backend:

```powershell
.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

Web Console:

```powershell
cd web-console
mvn spring-boot:run
```

Local DB:

```powershell
docker compose up -d postgres
docker compose --profile mariadb up -d mariadb
```

## Environment

PostgreSQL:

```powershell
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"
```

MariaDB:

```powershell
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"
```

Web Console:

```text
RCA_API_BASE_URL
RCA_PUBLIC_API_BASE_URL
RCA_DEFAULT_ADMIN_USERNAME
RCA_DEFAULT_ADMIN_PASSWORD
RCA_AGENT_OFFLINE_AFTER_SECONDS
```

## Validation

Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-dev-check.ps1 -Validate
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-integration-smoke.ps1
```

Linux:

```bash
scripts/linux-dev-check.sh --full
scripts/linux-integration-smoke.sh
```

## Node Agent

Local collect:

```powershell
.venv\Scripts\python.exe -m node_agent.main --collect-local --output evidence.json
```

DaemonSet manifest:

```text
GET /api/clusters/{cluster_id}/agent-manifest
```

Helm chart:

```text
charts/cluster-infra-rca-agent
charts/cluster-infra-rca-platform
```

## Layout

```text
backend/        FastAPI backend
node_agent/     node-local collector
web-console/    Spring Boot JSP console
charts/         Helm chart
manifests/      Kubernetes manifests
migrations/     Alembic migrations
tests/          Python tests
docs/           design docs
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
