# Testing

이 문서는 로컬 개발, 운영 smoke, Helm/DaemonSet 검증에서 확인해야 할 테스트 범위를 정리합니다.

## Local Checks

### Web Console / Backend

```bash
cd web-console
mvn test
```

Windows 로컬에서 저장소 내 Maven을 사용할 때:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
.\.dev-tools\apache-maven-3.9.9\bin\mvn.cmd -f web-console/pom.xml test
```

특정 Rule-based RCA 회귀 테스트만 실행:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
.\.dev-tools\apache-maven-3.9.9\bin\mvn.cmd -f web-console/pom.xml `
  '-Dtest=RuleBasedRegressionFixtureTests,RuleBasedScenarioTests,PolicyEngineTests' test
```

### Python Agent

```bash
python -m compileall node_agent scripts
pytest
```

### Frontend

```bash
cd web-console/frontend
npm ci
npm run build
```

### Helm

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform

helm lint charts/cluster-infra-rca-agent --set backendUrl=https://rca.example.com
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=agent-auth
```

### Docker Image

```bash
docker build -f Dockerfile.web-console .
```

## Rule-Based RCA Fixtures

`RuleAnalysisQualityTests`는 fixture 전체의 Precision, Recall, Top-1, Top-3 품질 게이트를 검증하고
`web-console/target/analysis-quality-report.json`을 생성합니다. CI는 이 파일을 artifact로 보존합니다.

Rule-based RCA 회귀 시나리오는 아래 JSON에 둡니다.

```text
web-console/src/test/resources/analysis/rule-based-rca-regression-scenarios.json
```

각 fixture는 다음 항목을 검증합니다.

- 감지되어야 하는 derived signal
- RCA scope에 포함되어야 하는 component
- 최소 root cause confidence score
- summary confidence
- 권장 조치 action key
- 자동 실행되면 안 되는 manual-only action
- 운영자가 확인할 resolution checklist

테스트 클래스:

```text
RuleBasedRegressionFixtureTests
```

새 장애 유형을 추가할 때는 detector만 추가하지 말고 fixture도 같이 추가해야 합니다. 그래야 LLM, UI, 정책 엔진이 바뀌어도 RCA 품질 기준을 유지할 수 있습니다.

## Runtime Smoke

로컬 또는 서버에 플랫폼이 떠 있으면 운영 시나리오 검증 러너를 실행합니다.

```bash
export RCA_BASE_URL=http://127.0.0.1:18080
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='<admin-password>'

python3 scripts/operational_scenario_validation.py \
  --output-dir validation-results/operational-scenarios
```

특정 시나리오만 실행:

```bash
python3 scripts/operational_scenario_validation.py \
  --scenarios disk-pressure,inode-exhaustion,node-not-ready
```

검증 기준:

- Analysis task가 `completed`로 끝나는지
- RCA report에 root cause candidate가 있는지
- evidence path와 derived signal이 비어 있지 않은지
- incident timeline node가 생성되는지
- LLM action은 항상 `automation_allowed=false`인지
- restart, cleanup, cordon, reboot, GitOps PR action이 자동 실행되지 않는지
- evidence bundle ZIP에 필수 entry와 `manifest.json` SHA-256 hash가 있는지
- signature secret이 제공된 경우 HMAC-SHA256 manifest signature가 검증되는지
- bundle export audit event가 생성되는지

결과는 `validation-results/operational-scenarios/<timestamp>/summary.json`에 저장됩니다.

## Web Console Route Smoke

Web Console이 실제 브라우저에서 빈 화면으로 깨지지 않는지 확인합니다.

검증 범위:

- 관리자 로그인
- 주요 메뉴 전체 클릭
- React runtime/page error 확인
- console error 확인
- 데스크톱/모바일 viewport 확인
- 영어/한국어 locale 전환 확인
- 모바일 수평 overflow 확인

로컬 또는 배포 서버를 대상으로 실행합니다.

```bash
cd web-console/frontend
CONSOLE_BASE_URL=http://127.0.0.1:18080 \
CONSOLE_USERNAME=admin \
CONSOLE_PASSWORD='<admin-password>' \
npm run smoke:routes
```

브라우저가 설치되어 있지 않으면 먼저 Playwright Chromium을 설치합니다.

```bash
npx playwright install chromium
```

서버에서 Docker로 실행할 때는 Playwright 공식 이미지를 사용할 수 있습니다.

```bash
docker run --rm --network host \
  -v "$PWD/web-console/frontend:/workspace" \
  -w /workspace \
  -e CONSOLE_BASE_URL=http://127.0.0.1:18080 \
  -e CONSOLE_USERNAME=admin \
  -e CONSOLE_PASSWORD='<admin-password>' \
  mcr.microsoft.com/playwright:v1.57.0-noble \
  sh -lc 'npm ci --no-audit --no-fund && npm run smoke:routes'
```

## Web Console Workflow E2E

Workflow E2E는 전용 Spring Boot 프로세스와 메모리 H2 DB를 자동으로 실행합니다. 운영 DB나
Kubernetes 클러스터는 사용하지 않습니다.

```bash
cd web-console
mvn --batch-mode --no-transfer-progress -DskipTests package

cd frontend
npx playwright install chromium
npm run e2e
```

설치된 Chrome을 사용할 때:

```powershell
$env:PLAYWRIGHT_CHANNEL = "chrome"
$env:PLAYWRIGHT_VIDEO = "false"
npm run e2e
```

검증 범위:

- 보호된 상세 URL 로그인 복원과 세션 만료
- Cluster 생성, 설치 명령, 상세 새로 고침, 삭제
- Demo Evidence, analysis task, RCA report 생성
- 조치 요청 승인, 거절, 수동 처리 완료
- Viewer 변경/export 제한
- 부분 API 503 오류, stale 데이터 유지, 오류 code/trace ID, 재시도 복구
- Agent 연결 상태 6종과 상태 필터
- 모바일 수평 overflow와 keyboard confirmation

실패 시 `playwright-report/`와 `test-results/`에 HTML report, trace, screenshot, video가 남습니다.
GitHub Actions는 이를 `console-workflow-e2e` artifact로 7일간 보존합니다.

## Evidence Bundle Verification

다운로드한 evidence bundle ZIP은 서버 없이 오프라인에서 검증할 수 있습니다.

```bash
python3 scripts/verify_evidence_bundle.py incident-123.zip
```

서명된 bundle:

```bash
python3 scripts/verify_evidence_bundle.py incident-123.zip \
  --signature-secret "$RCA_EXPORT_SIGNATURE_SECRET" \
  --signature-key-id default \
  --require-signature
```

검증 항목:

- ZIP 필수 entry 존재 여부
- `manifest.json`의 SHA-256 hash와 실제 entry 내용 일치 여부
- `--signature-secret` 제공 시 HMAC-SHA256 manifest signature

## GitHub Actions Operational Smoke

운영 서버를 대상으로 하는 smoke 검증은 GitHub Actions에서 수동 실행할 수 있습니다.

Workflow:

```text
Operational Smoke
```

Repository secret:

- `RCA_SMOKE_PASSWORD`
- `RCA_BUNDLE_SIGNATURE_SECRET`
- `TAILSCALE_AUTHKEY`

Repository variable:

- `RCA_SMOKE_BASE_URL`
- `RCA_SMOKE_USERNAME`
- `RCA_BUNDLE_SIGNATURE_KEY_ID`

수동 실행 시 `base_url`, `username`, `scenarios`, `use_tailscale`, `skip_audit_check`를 입력할 수 있습니다.
LLM staging 검증까지 같이 실행하려면 `run_llm_smoke=true`, `llm_scenario=disk-pressure`,
`llm_expected_status=completed`를 지정합니다.
결과는 `operational-smoke-results` artifact로 저장됩니다.

## DaemonSet Smoke

실제 Kubernetes 클러스터에 Agent를 배포한 뒤 read-only 검증을 실행합니다.

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

이 스크립트는 리소스를 생성하거나 삭제하지 않습니다. `kubectl get`, `kubectl auth can-i`, `kubectl logs`만 사용합니다.

## LLM Staging Smoke

LLM provider API key는 Platform 환경 변수 또는 Kubernetes Secret으로 주입하고, smoke script에는 관리자 계정만 전달합니다.

```bash
export RCA_BASE_URL=https://rca.example.com
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='...'

python3 scripts/llm-staging-smoke.py \
  --scenario disk-pressure \
  --expected-llm-status completed
```

검증 결과는 `validation-results/llm-staging-smoke/<run-id>/llm-staging-smoke-result.json`에 저장됩니다.
LLM이 비활성화된 baseline 환경을 확인할 때만 `--allow-disabled --expected-llm-status skipped`를 사용합니다.

여러 실행 결과를 합쳐 SLO 조정 가능 여부를 확인합니다. 이 명령은 provider를 호출하지 않습니다.

```bash
python3 scripts/llm-burn-in-report.py \
  validation-results/llm-staging-smoke \
  --output validation-results/llm-staging-smoke/burn-in-report.json
```

기본 gate는 20개 표본, 5개 장애 시나리오, 8시간 구간 3개, p95 60초입니다. 모든 smoke가 통과하고 LLM-origin action의 자동 실행 가능 건수가 0이어야 합니다. report의 `scenario_statistics`에서 장애 유형별 성공률, latency와 token 편차도 확인합니다.

호출량이 제한된 환경에서는 campaign runner로 실행 횟수를 고정합니다.

```bash
RCA_ADMIN_PASSWORD='...' python3 scripts/llm-burn-in-campaign.py \
  --base-url https://rca.example.com \
  --history validation-results/llm-staging-smoke/approved-history \
  --planning-baseline config/llm-burn-in-planning-baseline.json \
  --provider-call-budget 1 \
  --target-time-buckets 3 \
  --time-bucket-hours 8 \
  --require-new-time-bucket \
  --output-dir validation-results/llm-staging-smoke/campaign
```

실행 전에는 같은 인자에 `--dry-run`을 추가해 계획을 확인합니다. API key는 campaign 또는 smoke 명령에 전달하지 않습니다.

원본 표본을 저장소에 올리지 않고 호출 계획만 이어받으려면 `scripts/llm-burn-in-planning-baseline.py`로 `config/llm-burn-in-planning-baseline.json`을 갱신합니다. 생성 결과에는 hash, 시나리오, timestamp, action 안전성만 있어야 하며 `readiness_eligible=false`인지 확인합니다. Planning baseline은 SLO readiness 표본으로 계산되지 않습니다.

GitHub Actions에서는 `LLM Burn-in` workflow를 사용합니다. 최초에는 `dry_run=true`로 실행하고, 실제 호출 시에만 `dry_run=false`, `confirm_live_calls=true`, `provider_call_budget=1`, `change_reference`를 입력합니다. 실제 실행은 required reviewer가 설정된 `llm-burn-in` Environment와 저장소 기본 branch에서만 허용됩니다. 최신 누적 표본은 `RCA_LLM_BURN_IN_HISTORY_RUN_ID` repository variable로 자동 연결하고, 임시 검증 시에는 `history_run_id` 입력값으로 덮어쓸 수 있습니다. 최초 live 표본만 `initialize_history=true`를 사용하며 canonical history가 있는데 이 옵션을 사용하면 거부됩니다. 같은 8시간 구간에서는 추가 호출하지 않습니다. 실패 run은 sibling report가 있고 알려진 검증기 오탐만 남은 경우에만 현재 검증기로 오프라인 재검증하며 provider를 다시 호출하지 않습니다. 상세한 승인 조건과 artifact 취급 기준은 [llm-analyzer.md](llm-analyzer.md#manual-burn-in-workflow)에 있습니다.

## Operational Burn-in

반복 Agent 수집, 프로세스와 spool 증가 추세, 실제 클러스터 readiness, LLM readiness 상태를 한 artifact로 묶는 절차는 [operational-burn-in.md](operational-burn-in.md)를 사용합니다.

짧은 로컬 검증:

```bash
python3 scripts/agent-soak-validation.py \
  --profile smoke \
  --discover-agent-pod \
  --require-runtime-observation \
  --output-dir validation-results/operational-burn-in/agent-soak
```

GitHub Actions의 수동 `Operational Burn-in` workflow는 `rca-demo` runner에서만 실행하며 provider 호출 예산을 0으로 고정합니다. `smoke`, `standard`, `extended` 순서로 확장하고 24시간 `production` profile은 승인된 Linux 운영 세션에서 실행합니다.

다중 노드 fleet 검증:

```bash
python3 scripts/agent-soak-validation.py \
  --profile smoke \
  --discover-agent-pods \
  --minimum-agent-pods 3 \
  --require-runtime-observation \
  --output-dir validation-results/operational-burn-in/agent-fleet
```

장시간 3노드 검증은 GitHub Actions의 `Agent Fleet Burn-in` workflow를 사용합니다. `standard`는 `RUN-STANDARD-FLEET`, `extended`는 `RUN-EXTENDED-FLEET` 확인 문자열과 change reference가 필요합니다. push CI에는 장시간 profile을 넣지 않습니다.

Managed Kubernetes 검증은 `Managed Cluster Canary` workflow에서 먼저 `apply=false` preflight를 실행합니다. 실제 lifecycle은 플랫폼별 Environment 승인, 전용 runner label, environment-scoped kubeconfig, change reference와 `RUN-<PLATFORM>-CANARY` 확인 문자열이 모두 있어야 합니다. 결과 artifact는 비식별 attestation만 포함하며 compatibility matrix는 자동 수정하지 않습니다.

관련 로컬 회귀 테스트:

```bash
python3 -m pytest \
  tests/test_managed_canary_attestation.py \
  tests/test_canary_workflows.py
python3 scripts/release-readiness-check.py
```

## Kind E2E

개발용 Kubernetes smoke test는 1 control-plane과 2 worker로 구성된 Kind cluster에서 실행합니다.

```bash
bash scripts/kind-smoke.sh
```

검증 범위:

- Platform Helm chart 배포
- Agent Helm chart 배포
- Agent registration/heartbeat
- evidence request/response
- incident 및 RCA report 생성

## Real Cluster Agent E2E

실제 Kubernetes 노드 한 대에 read-only canary Agent를 배포해 등록부터 RCA 보고서와 evidence
bundle 생성까지 검증합니다. 기본 실행은 preflight만 수행하며 리소스를 만들지 않습니다.

```bash
bash scripts/real-cluster-agent-e2e.sh \
  --base-url https://rca.example.com \
  --node worker-1
```

실제 수명주기 검증은 비밀번호를 명령행 인자가 아닌 환경 변수로 전달하고 `--apply`를 지정합니다.

```bash
RCA_E2E_PASSWORD='...' bash scripts/real-cluster-agent-e2e.sh \
  --apply \
  --base-url https://rca.example.com \
  --username admin \
  --node worker-1
```

canary는 고유 namespace와 Platform 테스트 cluster만 생성하고, host path는 read-only로 마운트하며
한 노드에만 배포됩니다. 종료 시 소유권 label을 확인한 리소스만 삭제합니다. Kubernetes API discovery
오류 등으로 namespace 삭제가 지연되면 강제로 finalizer를 제거하지 않고 `cleanup-warning.txt`와
`namespace-pending.json`을 결과 디렉터리에 저장합니다.

## Important Test Classes

| Area | Example |
| --- | --- |
| RCA rule regression | `RuleBasedRegressionFixtureTests`, `RuleBasedScenarioTests` |
| Demo pipeline | `DemoScenarioIntegrationTests` |
| Redaction | `EvidenceRedactionIntegrationTests` |
| Queue/concurrency | `AnalysisTaskConcurrencyTests` |
| Auth/RBAC | `RbacAuthorizationTests`, `PlatformHttpTests` |
| Production config | `ProductionSecurityValidatorTests` |
| Agent protocol config | `AgentProtocolConfigurationValidatorTests` |
| Agent health | `AgentHealthServiceTests` |
| Impact scope | `ImpactScopeAnalyzerTests` |
| Notification payload·HTTP 분류 | `IncidentNotificationServiceTests` |
| Notification outbox claim·lease·retry | `NotificationOutboxRepositoryTests`, `NotificationOutboxWorkerTests` |
| Incident/outbox 원자적 rollback | `NotificationOutboxTransactionTests` |
| Metrics | `RcaMetricsTests` |
| Retention | `RetentionRepositoryTests`, `DatabaseCompatibilityTests` |
| Incident correlation | `IncidentCorrelationServiceTests`, `IncidentCorrelationDatasetTests`, `IncidentTimelineServiceTests` |
| Incident lifecycle | `IncidentLifecycleRepositoryTests`, `DatabaseCompatibilityTests` |
| Cluster topology | `TopologyExtractorTests`, `TopologyServiceTests`, `PlatformHttpTests` |
| Agent mTLS | `AgentMtlsFilterTests` |
| Manifest token/request limits | `PlatformHttpTests` |
| Audit export/token rotation | `PlatformHttpTests`, `RbacAuthorizationTests` |
| LLM fallback | `LlmAnalysisServiceTests`, `RuleBasedLlmFallbackTests` |

## CI Intent

CI는 다음 단계로 분리합니다.

```text
node-agent-test
frontend-build
web-console-test
helm-validate
docker-build
```

이미지 빌드는 test와 chart validation 이후 실행합니다.

## Manual Smoke Test

1. Platform health check 확인
2. 관리자 로그인
3. 테스트 클러스터 등록
4. Agent install command/manifest 생성
5. Demo scenario 또는 real evidence collection 실행
6. Analysis task 완료 확인
7. RCA report의 원인 후보, evidence, timeline, action policy 확인
8. evidence bundle export 권한 확인
9. approval workflow가 직접 실행이 아니라 수동 처리 기록으로 끝나는지 확인
10. metrics endpoint와 audit event 확인
