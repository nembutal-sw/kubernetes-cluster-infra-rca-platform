# Kubernetes Cluster Infra RCA Platform

Kubernetes 장애처럼 보이는 노드/Linux 시스템 장애를 수집하고 분석하는 RCA 플랫폼.

목표:

```text
alert -> evidence request -> node agent collect -> preprocess -> LLM diagnosis -> policy classify -> report
```

LLM은 진단과 설명만 담당한다. 조치 실행 여부는 Policy Engine과 운영자 승인 흐름에서 판단한다.

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

## Components

| Component | Role |
| --- | --- |
| Backend API | 클러스터, 에이전트, 웹훅, RCA job/report 관리 |
| Node Agent | 노드 로그, systemd, kernel, disk, memory, network, runtime 상태 수집 |
| Preprocessor | raw evidence를 LLM 입력 JSON으로 정리 |
| LLM Analyzer | 원인 후보, 근거, 추가 확인 명령 정리 |
| Policy Engine | 권장 조치 위험도 분류 |
| Web Console | 클러스터 등록, 웹훅 설정, 리포트 조회 |

## Stack

- Backend: FastAPI, SQLAlchemy, Alembic
- Web: Spring Boot, JSP, Bootstrap 5, React
- DB: PostgreSQL, MariaDB, SQLite fallback
- Agent: Python node-local collector, DaemonSet
- Test: pytest, Maven test, integration smoke scripts

## Features

- 관리자 승인 기반 회원가입
- session token, role 기반 접근 제어
- cluster 등록 및 agent 설치 명령 조회
- node token 기반 agent 인증
- Alertmanager webhook 수신
- evidence request/response API
- RCA job/report 생성
- LLM provider adapter
- Policy Engine guardrail
- Helm chart / DaemonSet manifest 초안

## Quick Start

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
docker compose up -d mariadb
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
RCA_ADMIN_APPROVAL_TOKEN
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
- [Web Console](docs/web-console.md)
