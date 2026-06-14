# Kubernetes Cluster Infra RCA Platform

AI-assisted RCA platform for Kubernetes node and Linux system failures.

This project focuses on infrastructure symptoms that often appear as Kubernetes issues:
`NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`,
kubelet/runtime failures, CNI/DNS problems, API server latency, disk I/O pressure,
inode exhaustion, conntrack exhaustion, kernel errors, systemd failures, and node
network instability.

Application-level signals such as `CrashLoopBackOff`, `ImagePullBackOff`, pod
`OOMKilled`, HTTP 5xx, missing Service endpoints, or Ingress mistakes are treated as
supporting context unless they point back to node or system-level evidence.

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

The LLM only explains and diagnoses. It does not directly change the cluster.
Recommended actions are classified by the Policy Engine before the UI exposes them.

Prometheus and Alertmanager are optional. The backend can also create evidence requests
directly, then use node agents to collect host data and generate RCA reports.

## Components

| Component | Role |
| --- | --- |
| Backend API | FastAPI service for clusters, agents, evidence, RCA jobs, reports, auth, and webhooks |
| Node Agent | Python DaemonSet/local collector for host Linux and Kubernetes node evidence |
| Preprocessor | Converts raw evidence and logs into compact JSON for RCA analysis |
| Rule Analyzer | Deterministic RCA signals for node pressure, runtime, kernel, network, CNI, DNS, inode, and conntrack issues |
| LLM Analyzer | Optional provider adapter for GPT, Gemini, Claude, or OpenAI-compatible local models |
| Policy Engine | Classifies recommended actions and blocks unsafe automation |
| Web Console | Spring Boot JSP console with Bootstrap 5 and React-powered dynamic views |
| Helm Charts | Agent chart and platform chart for Kubernetes deployment |

## Current Features

- Default admin login: `admin/admin`
- Session-token protected backend APIs
- Password change flow for the default account
- Cluster registration and install command generation
- Authenticated `/api/clusters/{cluster_id}/agent-manifest`
- Agent bootstrap token and per-node token validation
- Node agent local collection and DaemonSet collection
- File-mode systemd/journal collection for DaemonSet safety
- Host evidence collection for kernel, disk, inode, memory, process, network, conntrack, runtime, kubelet, CNI, DNS, and Kubernetes node state
- Alertmanager webhook ingestion
- Backend-initiated evidence requests without Prometheus
- Rule-based RCA report generation
- Optional LLM RCA enrichment
- Policy guardrails for safe, approval-required, PR-only, and never-auto-execute actions
- Web UI for clusters, agents, webhooks, evidence, RCA reports, policy results, and language preference
- PostgreSQL, MariaDB, and SQLite development support
- Platform Helm chart with optional in-cluster PostgreSQL or MariaDB

## Stack

- Backend: FastAPI, SQLAlchemy, Alembic
- Agent: Python 3.11+
- Web Console: Spring Boot, JSP, Bootstrap 5, React
- Database: PostgreSQL, MariaDB, SQLite for local development
- Deployment: Docker Compose, Kubernetes manifests, Helm
- Tests: pytest, Maven test, smoke scripts

## Quick Start

Docker Compose:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

Default local endpoints:

```text
Web Console: http://localhost:8080
Backend API: http://localhost:8000
```

Login:

```text
username: admin
password: admin
```

## Backend Development

```powershell
.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

## Web Console Development

```powershell
cd web-console
mvn spring-boot:run
```

The web console uses:

```text
RCA_API_BASE_URL
RCA_PUBLIC_API_BASE_URL
```

## Database

PostgreSQL:

```powershell
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"
```

MariaDB:

```powershell
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"
```

SQLite fallback:

```powershell
$env:RCA_DATABASE_URL = "sqlite:///./data/rca-dev.db"
```

## Node Agent

Local collection:

```powershell
.venv\Scripts\python.exe -m node_agent.main --collect-local --output evidence.json
```

DaemonSet mode defaults to file-based systemd/journal handling:

```text
SYSTEMD_COLLECTOR_MODE=file
```

This avoids relying on host DBus or `journalctl` access from inside the DaemonSet.
The collector reads host files and log excerpts from mounted paths such as
`/host/var/log`, `/host/proc`, `/host/sys`, `/host/etc`, and `/host/run`.

## Kubernetes Deployment

Agent chart:

```bash
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-xxx \
  --set secret.agentToken=token-xxx
```

Platform chart with default PostgreSQL:

```bash
helm template rca charts/cluster-infra-rca-platform
```

Platform chart with MariaDB:

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.type=mariadb
```

Platform chart with external DB:

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.enabled=false \
  --set-string backend.secret.databaseUrl='postgresql+psycopg://rca:password@postgresql.example:5432/rca'
```

LLM API key:

```bash
--set-string backend.secret.llmApiKey='...'
```

The chart exposes it to the backend as `RCA_LLM_API_KEY`.

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

Recent validation status:

- Windows full dev check: passed
- suse Linux Python 3.11 full pytest: passed
- suse Helm template checks: agent, PostgreSQL, MariaDB, external DB, LLM key passed
- suse agent local collect with `SYSTEMD_COLLECTOR_MODE=file`: passed

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
