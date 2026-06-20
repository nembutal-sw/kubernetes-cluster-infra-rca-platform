# Testing

## 한국어 요약

테스트는 단순 “빌드가 된다” 수준이 아니라, 이 프로젝트의 핵심 주장인 **안전한 RCA, read-only Agent, manual-only action workflow, 운영 가능성**을 검증해야 합니다.

현재 테스트에서 중요하게 봐야 할 영역은 다음입니다.

- Java/Spring Boot API와 security filter
- Rule-based detector와 RCA scenario
- evidence redaction
- analysis task queue와 concurrency
- RBAC authorization
- demo scenario pipeline
- evidence bundle export
- agent health classification
- impact scope caveat
- notification fallback/redaction
- metrics registration
- production fail-fast
- agent protocol config validation
- Python Agent collector/spool/client behavior
- Helm chart rendering

---

## English Reference

## Java Tests

Run all backend tests:

```bash
cd web-console
mvn test
```

Important groups:

```text
RuleBasedScenarioTests
EvidenceRedactionIntegrationTests
AnalysisTaskConcurrencyTests
DemoScenarioIntegrationTests
AgentHealthServiceTests
ImpactScopeAnalyzerTests
IncidentNotificationServiceTests
RbacAuthorizationTests
RcaMetricsTests
ProductionSecurityValidatorTests
AgentProtocolConfigurationValidatorTests
DatabaseCompatibilityTests
PlatformHttpTests
LlmAnalysisServiceTests
```

## Python Agent Tests

Run:

```bash
python -m compileall node_agent tests
pytest
```

Recommended focus:

- collector parsing
- local collection mode
- backend client JSON handling
- spool retry/acknowledge
- mTLS certificate configuration
- removed action executor regression
- agent protocol version propagation

## Frontend Build

```bash
cd web-console/frontend
npm ci
npm run build
```

The frontend should continue to support:

- report list/detail
- incident timeline
- evidence bundle download
- demo scenario run
- agent health dashboard
- action request lifecycle
- observability/platform info display

## Helm Validation

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform
helm lint charts/cluster-infra-rca-agent
helm template rca-agent charts/cluster-infra-rca-agent
```

Validate both default and optional modes:

```text
default install
demo enabled for local only
observability enabled
ServiceMonitor enabled
eBPF enabled
production profile values
```

## Docker Build Gate

Docker image build should happen only after:

```text
node-agent-test
frontend-build
web-console-test
helm-validate
```

## Security Regression Checklist

- Viewer cannot call mutation APIs.
- Viewer and Approver cannot export reports or evidence bundles.
- Only Admin/Approver can approve or reject action requests.
- Only Admin/Operator can mark manual action requests completed.
- Metrics endpoints require operational role or metrics token.
- Production profile rejects unsafe defaults.
- LLM-origin actions remain non-executable.
- Agent-side action execution remains disabled.

## RCA Regression Checklist

- Normal evidence should not create false actionable signals.
- DiskPressure, MemoryPressure, PIDPressure, NetworkUnavailable, kubelet, runtime, DNS, CNI, etcd, API server, systemd scenarios should still produce expected signals.
- Confidence score should stay in `0..100`.
- Evidence paths should be attached to root cause candidates.
- Impact scope should not overclaim service impact.

## Export Regression Checklist

- Bundle contains `summary.json`, `signals.json`, `timeline.json`, `rca-report.md`, and `evidence/*.json`.
- Sensitive values are redacted.
- Bundle size limit is enforced.
- Export creates audit event.
- Viewer/Approver export attempts are forbidden.

## CI Notes

If GitHub Actions do not appear for a commit, manually check the Actions tab. Connector status may not always show workflow runs immediately.
