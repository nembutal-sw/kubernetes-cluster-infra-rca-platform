# Kubernetes Cluster Infra RCA Platform

Kubernetes 애플리케이션 로그가 아니라 **클러스터 노드와 Linux 시스템 계층의 장애 원인**을 수집하고 분석하는 RCA 플랫폼입니다.

Node Agent가 노드 evidence를 읽기 전용으로 수집하고, Spring Boot Platform이 Rule-based 분석, 선택적 LLM 설명, Policy Engine, incident correlation을 거쳐 RCA 보고서를 만듭니다.

현재 구현 기준과 검증 범위는 [Current State](docs/current-state.md), 문서 전체 목록은
[Documentation Index](docs/README.md)에서 확인합니다.

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
  -> Report + Notification Outbox + Task completion (same DB transaction)
  -> Timeline / Audit / Manual Action Workflow
```

운영 조치는 자동 실행하지 않습니다. 승인 요청, 승인/거절 기록, 수동 처리 완료, runbook, 검토된 GitOps PR 흐름만 제공합니다. LLM 조치는 항상 `automation_allowed=false`, `executable=false`입니다.

## 구성

| Component | Stack | 역할 |
| --- | --- | --- |
| Platform | Spring Boot 3.5.15, Java 21 | API, 인증, DB, RCA, Policy, LLM, Web Console |
| Web Console | React 19, TypeScript, Vite, Bootstrap 5 | 운영 대시보드와 관리 workflow |
| Node Agent | Python 3.10+ | 노드 evidence와 optional eBPF event 수집 |
| Database | PostgreSQL 16 또는 MariaDB 11.x | 운영 데이터 저장 |
| Migration | Flyway V25, 25 migrations | 신규 및 기존 schema 관리 |

Web Console은 React SPA 한 종류만 사용합니다. JSP나 별도 Python Backend는 사용하지 않습니다.

## 현재 구현 상태

- 클러스터 등록·삭제, bootstrap token 또는 Kubernetes TokenReview 기반 Agent 등록과 설치 명령 생성
- session 인증, RBAC, audit 검색·필터·export
- typed evidence와 전처리·규칙·LLM 보강·보고서 조립 단계가 분리된 RCA pipeline
- 비식별 production-like corpus와 입력·정답을 분리한 19개 blind evaluation 품질 gate
- Managed canary collector만 익명화하고 2인 독립 판정으로 봉인하는 blind sample intake
- incident correlation, 장애 전파 timeline, 영향 범위
- Transactional Outbox 기반 Slack·webhook 알림과 dead-letter 재처리
- Evidence 단위 멱등 처리, stale worker fence와 Analysis/Notification lease heartbeat
- manual-only action workflow와 Catalog GitOps 변경 추적·실패 재조정
- PostgreSQL/MariaDB 호환 migration과 CI 실행 강제
- Helm, PrometheusRule, AlertmanagerConfig, 공급망 보안 gate
- 영문/한글 locale 저장과 데스크톱/모바일 반응형 Console

`RuleBasedRcaAnalyzer`는 분석 단계의 실행 순서만 조정합니다. Web Console의 `App.tsx`도 인증과
전역 shell을 담당하고, URL 동기화와 화면 선택은 별도 hook/component에서 처리합니다.

Rule-based 품질은 합성 golden fixture, 저장소 E2E 구조를 비식별화한 13개 production-like 시나리오,
입력과 sealed label을 분리한 19개 blind 시나리오로 검증합니다. Blind evaluator는 모든 detector 실행이
끝난 뒤 label을 로드하며 입력·label SHA-256을 보고서에 기록합니다. 이 결과는 실운영 정확도 수치가
아니며, managed cluster canary와 실제 장애 표본은 별도 검증 단계로 유지합니다.

실제 DaemonSet Evidence 방식의 1시간 Standard와 5시간 Extended Fleet 검증을 완료했습니다. 남은 실환경 검증은
24시간 Production Fleet와 EKS/AKS/GKE/OpenShift canary입니다. kubeadm은 Ubuntu 24.04 amd64,
Kubernetes 1.33.13, containerd, Flannel 조합에서 검증했습니다.

## 운영 검증 상태

실환경 검증은 수동 `Operational Burn-in` workflow로 묶었습니다. Agent 반복 수집 품질, Pod 내부의 read-only 자원/spool 추세, Kubernetes readiness, 플랫폼 compatibility, provider 호출 없는 LLM readiness를 하나의 artifact로 확인할 수 있습니다. 3노드 장시간 검증은 승인형 `Agent Fleet Burn-in`, EKS/AKS/GKE/OpenShift는 플랫폼별 `Managed Cluster Canary` workflow를 사용합니다. 적용형 canary는 선택적으로 익명화 evidence 후보를 만들 수 있지만, 두 명의 독립 판정과 별도 PR 없이는 평가 corpus에 반영되지 않습니다. 자세한 실행 순서는 [Operational Burn-in](docs/operational-burn-in.md)과 [Real Cluster Validation](docs/real-cluster-validation.md)을 참고합니다.

현재 real Agent E2E는 RKE2, K3s, kubeadm에서 완료했습니다. Kind 3노드에서는 각 DaemonSet Agent에 Platform Evidence Request를 보내는 방식으로 1시간 Standard와 5시간 Extended burn-in을 통과했습니다. Extended run `29857828475`는 checkpoint 300/300, Agent Evidence 900/900, target 3/3을 기록했고 수집 성공률과 evidence 품질은 100%, degraded collector와 runtime/spool/quarantine 오류는 0건이었습니다. Standard 대비 compatibility, absolute, regression gate도 모두 통과했습니다. CI는 push마다 3노드 smoke를 실행하고 장시간 Fleet는 별도 승인 workflow로 분리합니다. EKS는 Managed Node Group, Bottlerocket, Auto Mode, Fargate를, AKS는 system/user pool, NAP, Virtual Node, Windows pool을 공식 문서 기반 계약으로 분리해 검사합니다. 두 플랫폼 모두 실제 canary 전까지 `contract_fixture_only`이며 GKE와 OpenShift도 real managed-cluster canary가 남아 있습니다. LLM SLO readiness도 canonical 표본이 목표를 채울 때까지 기존 60초 기준을 유지합니다.

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
mvn -Pfrontend process-resources spring-boot:run
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:RCA_DEFAULT_ADMIN_USERNAME = "admin"
$env:RCA_DEFAULT_ADMIN_PASSWORD = "<strong-password>"
$env:RCA_WEBHOOK_TOKEN = "<random-webhook-token>"
Set-Location web-console
..\.dev-tools\apache-maven-3.9.9\bin\mvn.cmd -Pfrontend process-resources spring-boot:run
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

반복 LLM 검증은 GitHub Actions의 수동 `LLM Burn-in` workflow를 사용합니다. 기본값은 dry-run이고 실제 실행은 provider 호출 예산 1회, 명시적 확인, change reference, `llm-burn-in` Environment 승인을 요구합니다. `RCA_LLM_BURN_IN_HISTORY_RUN_ID` repository variable이 canonical artifact를 자동 연결하며 같은 8시간 구간에서는 추가 호출하지 않습니다. 실패 artifact는 알려진 검증기 오탐만 provider 재호출 없이 재검증하고, 그 밖의 오류는 거부합니다. 로컬 승인 표본은 원본 대신 비민감 planning baseline으로 계획에만 반영하고 SLO readiness에는 포함하지 않습니다. 세부 설정은 [docs/llm-analyzer.md](docs/llm-analyzer.md#manual-burn-in-workflow)에 있습니다.

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

Agent protocol v2는 등록 후 모든 요청을 node-scoped Bearer token으로 인증합니다. 등록 identity는 다음 두 방식 중 하나를 사용합니다.

- `bootstrap-token`: 기본 호환 모드입니다. 등록 전용 token은 기본 30분 후 만료되며 회전·폐기할 수 있습니다.
- `kubernetes-token-review`: Agent projected token은 `cluster-infra-rca-agent-enrollment` 같은 전용 audience를 사용합니다. Platform의 별도 Kubernetes API audience reviewer credential이 TokenReview와 Pod 조회를 수행하며, ServiceAccount UID, Running Pod, cluster label, DaemonSet UID, image digest까지 일치해야 등록됩니다.

TokenReview profile은 배포 전 staged 상태로 저장한 뒤 ServiceAccount/DaemonSet UID와 image digest를
바인딩하는 2단계 방식입니다. profile이 바뀌면 기존 node token을 폐기하고, 활성 node identity는
명시적 revoke 없이 다른 Pod가 덮어쓸 수 없습니다. 설정 순서는 [Agent Enrollment](docs/agent-enrollment.md),
권한과 canary 절차는 [Agent Helm Chart](docs/helm-agent-chart.md)를 확인합니다.

Agent audience와 Kubernetes API audience가 같으면 profile 저장과 운영 기동, Agent Helm 렌더링이
거부됩니다. Platform Helm upgrade는 기본 `audit` pre-upgrade hook으로 기존 DB의 위험 profile을
먼저 검사하며, 발견하면 배포를 중단합니다. Apply는 Helm values가 아니라
`render-agent-enrollment-migration-job.py`로 생성한 one-shot Job에서 cluster별로 수행합니다.
모든 unsafe profile이 사라진 최종 audit 후에만 Platform을 upgrade합니다. V24 이전 profile 미결합
node token은 기본적으로 거부하며, 필요한 cluster에만 Web Console에서 최대 30일의 재등록 유예를
설정할 수 있습니다. 자세한 절차는
[Agent Enrollment Upgrade](docs/agent-enrollment-upgrade.md)를 확인합니다.

Node token은 기본 30일마다 자동 교체합니다. 새 token은 로컬 state에 원자적으로 보관하고 heartbeat 인증이 성공한 뒤 활성화합니다. 재시작이나 일시적 통신 실패가 발생해도 이전 token으로 복구하며, 주기와 재시도 간격은 `nodeTokenRotationDays`, `nodeTokenRotationRetrySeconds`로 조정합니다.

기존 protocol v1의 body credential은 rolling upgrade를 위해 임시 호환됩니다.

## Platform Helm 설치

개발용 기본 예시:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --create-namespace \
  --values charts/cluster-infra-rca-platform/values-dev.yaml \
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
| Token pepper 회전 | [Opaque Token Pepper Rotation](docs/opaque-token-key-rotation.md) |

기본 image repository는 예시 값입니다. 실제 registry로 `platform.image.repository`, `platform.image.tag`, Agent의 `image.repository`, `image.tag`를 지정해야 합니다. 운영 secret은 CLI `--set`보다 기존 Secret 또는 External Secrets를 권장합니다.

운영 배포는 `values-production.yaml`을 사용합니다. 이 overlay는 외부 DB와 기존 Secret, Platform 2 replicas, NetworkPolicy, topology spread, read-only root filesystem을 강제합니다. 이미지 digest를 생략하면 Helm render 단계에서 실패합니다.

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --create-namespace \
  --values charts/cluster-infra-rca-platform/values-production.yaml \
  --set-string platform.image.repository=ghcr.io/<org>/cluster-infra-rca-web-console \
  --set-string platform.image.digest=sha256:<64-hex-digest>
```

기존 Secret `cluster-infra-rca-platform`에는 최소한 `RCA_JDBC_URL`, `RCA_DB_USERNAME`, `RCA_DB_PASSWORD`, `RCA_DEFAULT_ADMIN_USERNAME`, `RCA_DEFAULT_ADMIN_PASSWORD`, `RCA_WEBHOOK_TOKEN`, `RCA_ENCRYPTION_SECRET`, `RCA_OPAQUE_TOKEN_PEPPER`를 준비합니다. 회전 중에는 `RCA_OPAQUE_TOKEN_PREVIOUS_KEYS`도 같은 Secret에 둡니다. Pepper는 32자 이상의 별도 난수로 만들고 암호화 키와 같은 값을 사용하지 않습니다. 무중단 교체는 [회전 runbook](docs/opaque-token-key-rotation.md)의 reader 준비, writer 전환, lazy rehash 순서를 따릅니다.

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
cd web-console/frontend && npm ci && npm test && npm run build
mvn -f web-console/pom.xml -Pfrontend -DskipTests package
python3 scripts/verify-documentation.py
python3 scripts/release-readiness-check.py
```

기본 `mvn verify`는 Java Backend만 검증하며 Node.js나 npm registry를 사용하지 않습니다. React가
포함된 실행 JAR과 Docker image는 명시적으로 `frontend` Maven profile을 사용합니다.

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
