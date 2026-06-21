# Kubernetes Cluster Infra RCA Platform

Kubernetes 클러스터의 노드와 Linux 시스템 장애를 진단하는 RCA 플랫폼입니다.

노드 에이전트가 커널, systemd, 디스크, inode, 메모리, PID, 네트워크, conntrack, 컨테이너 런타임, kubelet, CNI, DNS 정보를 수집합니다. 중앙 플랫폼은 증거를 전처리하고 Rule-based 분석, 선택적 LLM 분석, Policy Engine 분류를 거쳐 RCA 보고서를 생성합니다.

LLM은 진단과 설명만 담당합니다. LLM이 제안한 조치는 항상 `automation_allowed=false`이며 직접 실행되지 않습니다.

Prometheus나 Alertmanager가 없는 환경에서는 `RCA_MONITORING_ENABLED=true`로 플랫폼 주기 수집을 활성화할 수 있습니다. 정상 evidence는 저장만 하고, 장애 signal이 감지될 때만 RCA 보고서를 생성합니다.

동일 노드에서 시간상 인접하고 causal rule로 연결되는 storage, runtime, kubelet, network,
DNS, control-plane 신호는 하나의 incident로 묶습니다. 더 상위 원인 evidence가 뒤늦게
수집되면 canonical root cause를 승격합니다. 조치 요청, 승인, 거절, 로그인, 클러스터 변경은
audit event로 기록됩니다.

Web Console에서는 장애 전파 타임라인, 후보별 신뢰도 점수, Agent 상태 분류와 수집된 pod/workload 영향 범위를 확인할 수 있습니다. 보고서 또는 incident의 분석 근거는 redaction된 ZIP bundle로 내려받을 수 있습니다.

운영 metric은 Micrometer/Actuator로 제공하며 Prometheus는 선택 사항입니다. Agent offline,
heartbeat lag, analysis queue/dead-letter, evidence 수집, report 생성 시간, LLM과 알림 결과를
확인할 수 있습니다. 자세한 내용은 [Observability And SLO](docs/observability.md)를 참고합니다.

운영 데이터는 설정된 보존 기간에 따라 작은 배치로 정리합니다. 열린 incident와 승인 처리 중인
조치는 삭제하지 않으며, 참조가 남은 evidence도 보존합니다. 자세한 기준은
[Retention Policy](docs/retention-policy.md)를 참고합니다.

## Architecture

```text
Alertmanager 또는 수동 수집 요청
  -> Node Agent 증거 수집
  -> DB 기반 durable analysis queue
  -> 전처리 및 Rule-based RCA
  -> 선택적 Spring AI 분석
  -> Policy Engine 검증
  -> 다중 신호 Incident Correlation
  -> RCA 보고서, causal timeline, 승인 기반 조치
```

| Component | Stack | Role |
| --- | --- | --- |
| Platform | Spring Boot 3.5.15, Java 21 | API, 인증, DB, RCA, Policy, LLM, Web Console |
| Web Console | React 19, TypeScript, Vite, Bootstrap 5 | 클러스터, 에이전트, 증거, 보고서 관리 |
| Node Agent | Python 3.10+ | Linux/Kubernetes 증거 수집, 선택적 eBPF 트레이싱 |
| Database | PostgreSQL 또는 MariaDB | 플랫폼 데이터 저장 |
| Migration | Flyway | 공통 DB 스키마 관리 |

## Main Targets

- `NodeNotReady`, `DiskPressure`, `MemoryPressure`, `PIDPressure`, `NetworkUnavailable`
- kubelet, containerd 및 CRI runtime 장애
- CNI, DNS, CoreDNS, API Server, etcd 지연
- 디스크 용량, inode, I/O latency, kernel I/O error
- systemd unit 실패 및 재시작 반복
- NIC link flap, conntrack 고갈, 노드 네트워크 장애

`CrashLoopBackOff`, `ImagePullBackOff`, Pod `OOMKilled`, HTTP 5xx 등은 보조 증거로 취급합니다.

## Demo Scenario

개발 환경에서는 `RCA_DEMO_ENABLED=true`로 10개 대표 장애 시나리오를 실행할 수 있습니다. 생성된 evidence는 실제 Agent evidence와 같은 queue, Rule-based 분석, incident/report 흐름을 사용합니다. 운영 profile에서는 Demo Mode가 활성화되면 시작 단계에서 차단됩니다.

주요 설정:

```text
RCA_DEMO_ENABLED=false
RCA_EXPORT_MAX_BUNDLE_BYTES=10485760
RCA_AGENT_EXPECTED_VERSION=
```

중요 incident를 Slack으로 알리려면 다음 값을 설정합니다. 알림 실패는 보고서 생성을 실패시키지 않으며 audit event로 남습니다.

```text
RCA_NOTIFICATION_ENABLED=true
RCA_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
RCA_NOTIFICATION_MINIMUM_SEVERITY=critical
```

## Quick Start

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

```text
Web/API: http://localhost:8080
Username: admin
Password: admin
```

최초 로그인 후 기본 비밀번호를 변경해야 합니다.

Docker 없이 실행할 때는 Java 21과 Maven 3.9 이상이 필요합니다.

```powershell
cd web-console
mvn spring-boot:run
```

기본 개발 DB는 H2 파일 DB입니다. PostgreSQL 또는 MariaDB를 사용할 때는 JDBC 설정을 지정합니다.

```powershell
$env:RCA_JDBC_URL = "jdbc:postgresql://localhost:5432/rca"
$env:RCA_DB_USERNAME = "rca"
$env:RCA_DB_PASSWORD = "rca_password"
```

```powershell
$env:RCA_JDBC_URL = "jdbc:mariadb://localhost:3306/rca"
```

## Spring AI

기본값은 비활성화입니다. 지원 대상은 OpenAI SDK, Anthropic, Google GenAI, Ollama입니다.

```text
RCA_LLM_ENABLED=true
RCA_LLM_PROVIDER=openai
RCA_LLM_MODEL=gpt-5-mini
RCA_SPRING_AI_CHAT_MODEL=openai-sdk
SPRING_AI_OPENAI_SDK_API_KEY=...
```

다른 provider는 `anthropic`, `google-genai`, `ollama`를 `RCA_SPRING_AI_CHAT_MODEL`에 지정합니다.

## Node Agent

로컬 증거 수집:

```powershell
.venv\Scripts\python.exe -m node_agent.main `
  --collect-local `
  --output evidence.json
```

DaemonSet Agent는 다음 안전장치를 사용합니다.

- host 디렉터리는 상태 저장 경로를 제외하고 read-only mount
- systemd와 journal은 기본적으로 file mode 수집
- node token을 노드별 상태 디렉터리에 저장
- 전송 실패 evidence를 디스크에 spool한 뒤 재전송
- 지수 backoff 및 선택적 CA bundle/mTLS 지원
- eBPF는 기본 비활성
- 승인 조치는 Agent에서 실행하지 않고 수동 runbook 또는 GitOps PR로 처리

Agent 등록과 heartbeat에는 `agent_protocol_version`이 포함됩니다. 누락된 기존 Agent는
protocol `1`로 처리하며, 지원 범위를 벗어나거나 최소 Agent 버전보다 낮으면 Web Console에서
`version_mismatch`로 분류합니다. 현재 호환성 정보는 인증 후
`GET /api/v1/platform/info`에서 확인할 수 있습니다.

주요 설정:

- `RCA_AGENT_PROTOCOL_VERSION`: Platform이 지원하는 최신 protocol
- `RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION`: 지원하는 최소 protocol
- `RCA_AGENT_MINIMUM_SUPPORTED_VERSION`: 지원하는 최소 Agent 버전
- `RCA_PLATFORM_VERSION`: Platform 표시 버전

eBPF 실시간 수집을 활성화하면 OOM kill, TCP retransmit, DNS 지연 이벤트가 Evidence로 전송됩니다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set ebpf.enabled=true
```

Linux 5.8 이전 커널처럼 legacy BPF 권한이 필요한 환경에서만
`--set ebpf.legacySysAdmin=true`를 추가합니다.

승인 workflow는 요청, 승인/거절, audit, 수동 처리 완료 기록으로 구성됩니다.
명령과 YAML은 runbook 또는 GitOps PR 안내로만 제공되며 Platform과 Agent가 직접 실행하지 않습니다.
LLM 제안은 항상 `automation_allowed=false`입니다.

## Helm

중앙 플랫폼과 DB:

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

Agent:

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-xxx \
  --set secret.agentToken=token-xxx
```

Helm 이미지 repository 값은 예시 placeholder입니다. 실제 배포 시 사용하는 registry로 지정해야 합니다.

## Validation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts\windows-dev-check.ps1 -BootstrapMaven -Validate
```

```bash
bash scripts/linux-dev-check.sh --full
```

검증 범위:

- Node Agent pytest 및 Python compile
- React/TypeScript/Vite production build
- Spring Boot API/UI 통합 테스트
- PostgreSQL/MariaDB Testcontainers 호환성 및 기존 Alembic DB 승계
- Ubuntu, Debian, Rocky Linux, openSUSE Agent 수집 호환성
- Helm HA, NetworkPolicy, External Secrets, DB backup 렌더링
- 인증, 클러스터, 에이전트, evidence, RCA report 흐름
- 분석 queue lease, retry, dead-letter 및 수동 재처리
- retention cleanup의 FK-safe 삭제 순서와 활성 incident/action 보존

## Production Security

운영 환경은 `SPRING_PROFILES_ACTIVE=prod`로 실행합니다. 기본 admin 비밀번호, 개발용
webhook token, 예제 DB 비밀번호, HTTP public URL, 빈 encryption secret 등이 남아 있으면
애플리케이션이 시작되지 않습니다.

필수 설정과 역할별 권한은 [docs/security.md](docs/security.md)에 정리되어 있습니다.

CI 검증 구조와 동시성, spool, redaction 테스트는
[docs/phase3-testing-ci.md](docs/phase3-testing-ci.md)에 정리되어 있습니다.

## Repository

```text
web-console/  Spring Boot platform and Web Console
node_agent/   Python node evidence collector
charts/       Platform and Agent Helm charts
manifests/    Agent example manifest
tests/        Node Agent tests
docs/         Design and operation documents
scripts/      Local validation scripts
examples/     Sample webhook and report payloads
```

세부 문서는 [docs](docs/)에서 확인할 수 있습니다.
