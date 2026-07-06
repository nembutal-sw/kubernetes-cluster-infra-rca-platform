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

## Kind E2E

개발용 Kubernetes smoke test:

```bash
bash scripts/kind-smoke.sh
```

검증 범위:

- Platform Helm chart 배포
- Agent Helm chart 배포
- Agent registration/heartbeat
- evidence request/response
- incident 및 RCA report 생성

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
| Notification | `IncidentNotificationServiceTests` |
| Metrics | `RcaMetricsTests` |
| Retention | `RetentionRepositoryTests`, `DatabaseCompatibilityTests` |
| Incident correlation | `IncidentCorrelationServiceTests`, `IncidentCorrelationDatasetTests`, `IncidentTimelineServiceTests` |
| Incident lifecycle | `IncidentLifecycleRepositoryTests`, `DatabaseCompatibilityTests` |
| Cluster topology | `TopologyExtractorTests`, `TopologyServiceTests`, `PlatformHttpTests` |
| Agent mTLS | `AgentMtlsFilterTests` |
| Manifest token/request limits | `PlatformHttpTests` |
| Audit export/token rotation | `PlatformHttpTests`, `RbacAuthorizationTests` |
| LLM fallback | `LlmAnalysisServiceTests` |

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
