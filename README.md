# Kubernetes Cluster Infra RCA Platform

Kubernetes 애플리케이션 로그가 아니라 **클러스터 노드와 Linux 시스템 계층의 장애 원인**을 수집하고 분석하는 RCA 플랫폼입니다.

Node Agent가 노드 evidence를 읽기 전용으로 수집하고, Spring Boot Platform이 Rule-based 분석, 선택적 LLM 설명, Policy Engine, incident correlation을 거쳐 RCA 보고서를 만듭니다.

## 진단 범위

- `NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`
- kubelet, containerd, CRI runtime, CNI, DNS, CoreDNS 장애
- API Server와 etcd 지연
- 디스크 용량, inode 고갈, I/O latency, kernel I/O error
- systemd unit 실패와 restart loop
- NIC link flap, MTU 문제, conntrack 고갈, 노드 네트워크 불안정

Pod 상태, HTTP 5xx, Service endpoint, Ingress 오류는 원인 단서와 영향 범위를 보완하는 evidence로 사용합니다.

## 처리 흐름

```text
Alertmanager / Platform Scheduler / Demo Scenario
  -> Evidence Request
  -> Node Agent read-only collection
  -> Durable Analysis Queue
  -> Evidence preprocessing
  -> Rule-based RCA
  -> Optional LLM explanation
  -> Policy Engine
  -> Incident correlation
  -> Report / Timeline / Audit / Manual Action Workflow
```

운영 조치는 자동 실행하지 않습니다. 승인 요청, 승인/거절 기록, 수동 처리 완료, runbook, 검토된 GitOps PR 흐름만 제공합니다. LLM 조치는 항상 `automation_allowed=false`, `executable=false`입니다.

## 구성

| Component | Stack | 역할 |
| --- | --- | --- |
| Platform | Spring Boot 3.5.15, Java 21 | API, 인증, DB, RCA, Policy, LLM, Web Console |
| Web Console | React 19, TypeScript, Vite, Bootstrap 5 | 운영 대시보드와 관리 workflow |
| Node Agent | Python 3.10+ | 노드 evidence와 optional eBPF event 수집 |
| Database | PostgreSQL 16 또는 MariaDB 11.x | 운영 데이터 저장 |
| Migration | Flyway, 19 migrations | 신규 및 기존 schema 관리 |

Web Console은 React SPA 한 종류만 사용합니다. JSP나 별도 Python Backend는 사용하지 않습니다.

## 현재 구현 상태

- 클러스터 등록·삭제, Agent token 회전과 설치 명령 생성
- session 인증, RBAC, audit 검색·필터·export
- typed evidence, Rule-based RCA 품질 gate, LLM provider 추상화
- incident correlation, 장애 전파 timeline, 영향 범위
- manual-only action workflow와 Catalog GitOps 변경 추적
- PostgreSQL/MariaDB 호환 migration과 CI 실행 강제
- Helm, PrometheusRule, AlertmanagerConfig, 공급망 보안 gate
- 영문/한글 locale 저장과 데스크톱/모바일 반응형 Console

남은 실환경 검증은 EKS/AKS/GKE와 OpenShift canary입니다. kubeadm은
Ubuntu 24.04 amd64, Kubernetes 1.33.13, containerd, Flannel 조합에서 검증했습니다.

## Quick Start

### Docker Compose

```bash
cp .env.example .env
```

PowerShell:

```powershell
Copy-Item .env.example .env
```

`.env`에서 최소 설정을 입력합니다.

```dotenv
RCA_DEFAULT_ADMIN_USERNAME=admin
RCA_DEFAULT_ADMIN_PASSWORD=<strong-password>
RCA_WEBHOOK_TOKEN=<random-webhook-token>
```

```bash
docker compose up --build -d
docker compose ps
```

```text
Web Console / API  http://localhost:8080
Readiness          http://localhost:8080/health/ready
```

자주 쓰는 관리 명령:

```bash
docker compose logs -f platform
docker compose restart platform
docker compose stop
docker compose down
```

`docker compose down -v`는 DB volume까지 삭제하므로 테스트 데이터를 모두 버릴 때만 사용합니다.

### Java 로컬 실행

Docker 없이 H2 file DB로 실행할 수 있습니다. Java 21과 Maven 3.9 이상이 필요합니다.

```bash
export RCA_DEFAULT_ADMIN_USERNAME=admin
export RCA_DEFAULT_ADMIN_PASSWORD='<strong-password>'
export RCA_WEBHOOK_TOKEN='<random-webhook-token>'
cd web-console
mvn spring-boot:run
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:RCA_DEFAULT_ADMIN_USERNAME = "admin"
$env:RCA_DEFAULT_ADMIN_PASSWORD = "<strong-password>"
$env:RCA_WEBHOOK_TOKEN = "<random-webhook-token>"
Set-Location web-console
..\.dev-tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

초기 계정은 코드에 고정되어 있지 않습니다. 첫 로그인 후 Settings에서 로그인 ID와 비밀번호를 변경할 수 있습니다.

## 처음 사용하는 순서

1. Web Console에 로그인합니다.
2. **Clusters**에서 클러스터 이름과 환경을 등록합니다.
3. 생성된 Agent 설치 명령을 대상 클러스터에서 실행합니다.
4. Agent가 `healthy`로 표시되는지 확인합니다.
5. 수동 수집, Alertmanager webhook 또는 Platform Scheduler로 evidence를 수집합니다.
6. **Reports**에서 원인 후보, evidence, 확인 명령, 권장 조치와 정책 등급을 확인합니다.

## 자주 쓰는 옵션

### Database

| 방식 | 설정 |
| --- | --- |
| H2 file | 설정 없음. 로컬 개발용 |
| PostgreSQL | `RCA_JDBC_URL=jdbc:postgresql://host:5432/rca` |
| MariaDB | `RCA_JDBC_URL=jdbc:mariadb://host:3306/rca` |

외부 DB:

```bash
export RCA_JDBC_URL='jdbc:postgresql://postgres.example:5432/rca'
export RCA_DB_USERNAME='rca'
export RCA_DB_PASSWORD='<database-password>'
```

Docker Compose에서 MariaDB 사용:

```dotenv
RCA_JDBC_URL=jdbc:mariadb://mariadb:3306/rca
RCA_DB_USERNAME=rca
RCA_DB_PASSWORD=<database-password>
MARIADB_PASSWORD=<database-password>
MARIADB_ROOT_PASSWORD=<root-password>
```

```bash
docker compose --profile mariadb up -d mariadb
docker compose build platform
docker compose --profile mariadb up -d --no-deps platform
```

### 기능 토글

| 목적 | 환경 변수 |
| --- | --- |
| Demo UI와 RCA workflow | `RCA_DEMO_ENABLED=true` |
| Prometheus 없는 자체 수집 | `RCA_MONITORING_ENABLED=true` |
| Prometheus metrics | `RCA_OBSERVABILITY_ENABLED=true` |
| Slack 또는 일반 webhook | `RCA_NOTIFICATION_ENABLED=true` |
| Catalog GitOps PR/MR | `RCA_GITOPS_ENABLED=true` |
| LLM 분석 보조 | `RCA_LLM_ENABLED=true` |

자체 수집 기본 예시:

```dotenv
RCA_MONITORING_ENABLED=true
RCA_MONITORING_INTERVAL_MS=60000
RCA_MONITORING_HEALTHY_INTERVAL_MINUTES=15
RCA_MONITORING_DEGRADED_INTERVAL_MINUTES=5
RCA_MONITORING_STALE_INTERVAL_MINUTES=2
```

### LLM

| Provider | Provider 값 | Chat model | Docker Compose credential |
| --- | --- | --- | --- |
| OpenAI | `openai` | `openai-sdk` | `OPENAI_API_KEY` |
| Anthropic | `anthropic` | `anthropic` | `ANTHROPIC_API_KEY` |
| Gemini | `gemini` | `google-genai` | `GEMINI_API_KEY` |
| Ollama | `ollama` | `ollama` | `OLLAMA_BASE_URL` |
| OpenAI-compatible | `openai_compatible` | `openai-sdk` | `OPENAI_API_KEY`, `OPENAI_BASE_URL` |
| Self-hosted | `self_hosted` | `openai-sdk` | `OPENAI_BASE_URL` |

Gemini 예시:

```dotenv
RCA_LLM_ENABLED=true
RCA_LLM_PROVIDER=gemini
RCA_LLM_MODEL=gemini-3.1-flash-lite
RCA_SPRING_AI_CHAT_MODEL=google-genai
GEMINI_API_KEY=<api-key>
```

Java 직접 실행 시 credential은 `SPRING_AI_OPENAI_SDK_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`, `SPRING_AI_GOOGLE_GENAI_API_KEY`, `SPRING_AI_OLLAMA_BASE_URL`을 사용합니다. 상세 예시는 [web-console/README.md](web-console/README.md)에 있습니다.

반복 LLM 검증은 GitHub Actions의 수동 `LLM Burn-in` workflow를 사용합니다. 기본값은 dry-run이고 실제 실행은 최대 3회 호출 예산, 명시적 확인, change reference를 요구합니다. 이전 성공 run의 artifact를 연결하면 표본 history를 누적할 수 있습니다. 세부 설정은 [docs/llm-analyzer.md](docs/llm-analyzer.md#manual-burn-in-workflow)에 있습니다.

## Node Agent 설치

Web Console이 생성한 설치 명령을 사용하는 방법이 가장 간단합니다. 수동 설치 시 Agent Secret을 먼저 만듭니다.

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id='<cluster-id>' \
  --from-literal=agent-token='<agent-token>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set mode=node-diagnostics \
  --set systemdCollectorMode=file
```

Mode별 추가 옵션:

| Mode | Helm option | 용도 |
| --- | --- | --- |
| Safe | `--set mode=safe` | hostPath 없는 제한 수집 |
| Node diagnostics | `--set mode=node-diagnostics` | Linux 노드 evidence 수집 |
| eBPF | `--set mode=ebpf --set ebpf.enabled=true` | 실시간 kernel/network event |
| Canary | `--set nodeSelector.cluster-infra-rca\.io/agent-canary=true` | label된 노드만 배포 |
| mTLS | `--set tls.enabled=true --set tls.existingSecret=<tls-secret>` | Agent client 인증서 사용 |

자세한 권한과 canary 절차는 [docs/helm-agent-chart.md](docs/helm-agent-chart.md)를 확인합니다.

## Platform Helm 설치

개발용 기본 예시:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --create-namespace \
  --set-string platform.secret.defaultAdminUsername=admin \
  --set-string platform.secret.defaultAdminPassword='<strong-password>' \
  --set-string platform.secret.webhookToken='<webhook-token>'
```

선택 옵션:

| 목적 | Helm option |
| --- | --- |
| MariaDB | `--set database.type=mariadb` |
| 외부 DB | `--set database.enabled=false`와 `platform.secret.jdbcUrl` |
| ServiceMonitor | `--set platform.serviceMonitor.enabled=true` |
| LLM PrometheusRule | `--set platform.prometheusRule.enabled=true` |
| AlertmanagerConfig | `--set platform.alertmanagerConfig.enabled=true`와 `clusterId` |
| Demo | `--set platform.config.demoEnabled=true` |

기본 image repository는 예시 값입니다. 실제 registry로 `platform.image.repository`, `platform.image.tag`, Agent의 `image.repository`, `image.tag`를 지정해야 합니다. 운영 secret은 CLI `--set`보다 기존 Secret 또는 External Secrets를 권장합니다.

## 검증 명령

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts\windows-dev-check.ps1 -BootstrapMaven -Validate
```

```bash
bash scripts/linux-dev-check.sh --full
```

컴포넌트별 실행:

```bash
python -m pytest -q
mvn -f web-console/pom.xml verify
python3 scripts/release-readiness-check.py
```

DB 호환 테스트가 실제 실행됐는지 확인:

```bash
mvn -f web-console/pom.xml -Dtest=DatabaseCompatibilityTests test
python3 scripts/verify_database_compatibility_report.py
```

두 번째 명령은 Docker 미탐지로 DB 테스트가 skip된 경우에도 실패합니다. 브라우저 E2E, Helm, Alertmanager와 실클러스터 검증 명령은 [docs/README.md](docs/README.md)와 [docs/testing.md](docs/testing.md)에 정리되어 있습니다.

## 저장소 구조

```text
web-console/  Spring Boot Platform과 React Web Console
node_agent/   Python Node Agent
charts/       Platform과 Agent Helm chart
manifests/    Agent 예제 manifest
scripts/      개발 및 운영 검증 도구
tests/        Python과 운영 스크립트 테스트
docs/         설계, 보안, 운영 문서
examples/     webhook과 report 예제
```

API 인증 경계는 [docs/api-security-contract.md](docs/api-security-contract.md), 역할별 권한은 [docs/rbac-matrix.md](docs/rbac-matrix.md), 전체 문서 목록은 [docs/README.md](docs/README.md)를 참고합니다.
