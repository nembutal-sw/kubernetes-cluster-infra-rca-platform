# Kubernetes Cluster Infra RCA Platform

Kubernetes 애플리케이션 장애가 아니라, **클러스터 노드와 Linux 시스템 레벨 장애**를 진단하는 RCA 플랫폼입니다.

Node Agent가 커널 로그, systemd, 디스크, inode, 메모리, PID, 네트워크, conntrack, container runtime, kubelet, CNI, DNS evidence를 수집합니다. Platform은 evidence를 전처리한 뒤 Rule-based RCA, 선택적 LLM 설명, Policy Engine, incident correlation을 거쳐 보고서를 생성합니다.

LLM은 진단과 설명만 담당합니다. LLM이 제안한 조치는 기본적으로 `automation_allowed=false`이며 Platform과 Agent가 호스트 변경 명령을 자동 실행하지 않습니다.

## Scope

주요 진단 대상:

- `NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`
- kubelet, containerd, CRI runtime 장애
- CNI, DNS, CoreDNS, API Server, etcd latency
- 디스크 용량, inode 고갈, I/O latency, kernel I/O error
- systemd unit 실패와 restart loop
- NIC link flap, conntrack 고갈, 노드 네트워크 장애

보조 evidence:

- `CrashLoopBackOff`, `ImagePullBackOff`, Pod `OOMKilled`
- HTTP 5xx 증가
- Service endpoint 없음
- Ingress 설정 오류

## Architecture

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
  -> RCA Report / Timeline / Audit / Manual Action Workflow
```

| Component | Stack | Role |
| --- | --- | --- |
| Platform | Spring Boot 3.5.x, Java 21 | API, 인증, DB, RCA, Policy, LLM, Web Console |
| Web Console | React 19, TypeScript, Vite, Bootstrap 5 | 클러스터, Agent, evidence, report 관리 |
| Node Agent | Python 3.10+ | 노드 evidence 수집, optional eBPF event |
| Database | PostgreSQL 또는 MariaDB | 운영 데이터 저장 |
| Migration | Flyway | DB 스키마 관리 |

## Quick Start

```powershell
Copy-Item .env.example .env
$env:RCA_DEFAULT_ADMIN_USERNAME = "platform-admin"
$env:RCA_DEFAULT_ADMIN_PASSWORD = "change-this-password"
$env:RCA_WEBHOOK_TOKEN = "change-this-webhook-token"
docker compose up --build -d
```

```text
Web/API: http://localhost:8080
```

기본 관리자 계정은 코드에 고정하지 않습니다. `.env` 또는 배포 Secret으로 초기 관리자 계정을 명시적으로 주입해야 합니다.

Docker 없이 실행하려면 Java 21과 Maven 3.9 이상이 필요합니다.

```powershell
cd web-console
mvn spring-boot:run
```

## Database

개발 기본값은 H2 file DB입니다. 운영 검증은 PostgreSQL과 MariaDB를 모두 지원하도록 유지합니다.

PostgreSQL:

```powershell
$env:RCA_JDBC_URL = "jdbc:postgresql://localhost:5432/rca"
$env:RCA_DB_USERNAME = "rca"
$env:RCA_DB_PASSWORD = "rca_password"
```

MariaDB:

```powershell
$env:RCA_JDBC_URL = "jdbc:mariadb://localhost:3306/rca"
$env:RCA_DB_USERNAME = "rca"
$env:RCA_DB_PASSWORD = "rca_password"
```

## Spring AI / LLM

LLM은 선택 기능이며 기본값은 비활성화입니다.

```text
RCA_LLM_ENABLED=true
RCA_LLM_PROVIDER=openai
RCA_LLM_MODEL=gpt-5-mini
RCA_SPRING_AI_CHAT_MODEL=openai-sdk
SPRING_AI_OPENAI_SDK_API_KEY=...
```

Provider별 설정:

- OpenAI/OpenAI-compatible: `SPRING_AI_OPENAI_SDK_API_KEY`
- OpenAI-compatible/self-hosted base URL: `SPRING_AI_OPENAI_SDK_BASE_URL`
- Anthropic Claude: `SPRING_AI_ANTHROPIC_API_KEY`
- Google Gemini: `SPRING_AI_GOOGLE_GENAI_API_KEY`
- Ollama/local model: `SPRING_AI_OLLAMA_BASE_URL`

Web Console의 Settings 화면에서는 provider, model, key 설정 여부만 확인합니다. API key 값은 브라우저로 내려주지 않습니다.

LLM 호출이 실패해도 RCA report 생성은 실패하지 않습니다. Rule-based 분석은 계속 동작합니다.

## Backend Scheduled Monitoring

Prometheus나 Alertmanager를 쓰지 않는 환경에서는 Platform 자체 스케줄러가 등록된 Agent에 read-only collection request를 만들 수 있습니다.

```text
RCA_MONITORING_ENABLED=true
RCA_MONITORING_INTERVAL_MS=60000
RCA_MONITORING_COLLECT_HEALTHY_AGENTS=true
RCA_MONITORING_HEALTHY_INTERVAL_MINUTES=15
RCA_MONITORING_DEGRADED_INTERVAL_MINUTES=5
RCA_MONITORING_STALE_INTERVAL_MINUTES=2
```

기본 동작:

- pending request가 있는 노드는 새 요청을 만들지 않습니다.
- 같은 노드와 같은 상태의 최근 request가 있으면 건너뜁니다.
- healthy Agent는 baseline collector만 실행합니다.
- stale 또는 degraded 상태는 systemd, kernel, kubelet을 포함한 deep collector를 실행합니다.
- scheduled monitoring으로 수집된 healthy evidence는 RCA report 생성을 skip합니다.

## Node Agent

로컬 수집:

```powershell
python -m node_agent.main --collect-local --output evidence.json
```

DaemonSet 운영 기준:

- 기본 `safe` mode는 hostPath를 사용하지 않습니다.
- `node-diagnostics` mode는 `/proc`, `/sys`, `/run`, `/var/log`, `/etc`를 read-only로 mount합니다.
- systemd/journal 직접 접근보다 `systemdCollectorMode=file`을 우선 권장합니다.
- evidence 전송 실패 시 spool 후 재전송합니다.
- eBPF event 수집은 선택 기능입니다.
- Agent-side action execution은 사용하지 않습니다.

Agent Helm 예시:

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=agent-auth \
  --set mode=node-diagnostics \
  --set systemdCollectorMode=file
```

eBPF 옵션:

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=agent-auth \
  --set mode=ebpf \
  --set ebpf.enabled=true
```

## Helm

Platform과 DB:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform
```

MariaDB:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set database.type=mariadb
```

외부 DB:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set database.enabled=false \
  --set-string platform.secret.jdbcUrl='jdbc:postgresql://postgres.example:5432/rca' \
  --set-string platform.secret.databaseUsername='rca' \
  --set-string platform.secret.databasePassword='change-me'
```

이미지 repository 값은 placeholder입니다. 실제 배포 전 사용하는 registry로 변경해야 합니다.

## Validation

개발 검증:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts\windows-dev-check.ps1 -BootstrapMaven -Validate
```

```bash
bash scripts/linux-dev-check.sh --full
```

운영 시나리오 검증:

```bash
export RCA_BASE_URL=http://127.0.0.1:18080
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='<admin-password>'

python3 scripts/operational_scenario_validation.py
```

DaemonSet read-only 검증:

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

플랫폼 호환성 catalog 검증:

```bash
python3 scripts/cluster_compatibility.py --validate-catalog
```

자세한 기준은 [docs/testing.md](docs/testing.md), [docs/runtime-compatibility.md](docs/runtime-compatibility.md),
[docs/daemonset-operations-checklist.md](docs/daemonset-operations-checklist.md)를 참고합니다.

## Security Position

- API 로그인은 session token 기반입니다.
- Agent는 cluster token과 node token을 함께 검증합니다.
- Webhook, manifest, metrics endpoint는 별도 인증 경계를 가집니다.
- report/evidence export는 운영 역할로 제한하고 audit을 남깁니다.
- 승인 workflow는 자동 실행이 아니라 승인, 거절, 수동 처리 완료 기록, runbook, GitOps PR 안내로 동작합니다.
- production profile은 기본 비밀번호, 빈 webhook token, 개발용 secret을 fail-fast로 차단합니다.

## Repository

```text
web-console/  Spring Boot platform and React Web Console
node_agent/   Python node evidence collector
charts/       Platform and Agent Helm charts
manifests/    Agent example manifest
tests/        Node Agent tests
docs/         Design and operation documents
scripts/      Local and operational validation scripts
examples/     Sample webhook and report payloads
```

상세 문서는 [docs](docs/)에서 확인합니다.
