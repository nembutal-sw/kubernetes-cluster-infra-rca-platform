# Testing

## 한국어 요약

테스트는 단순 빌드 확인이 아니라, 현재 프로젝트의 핵심 주장인 안전한 RCA, read-only Agent, manual-only workflow, 운영 가능성을 검증하기 위한 기준입니다.

중요 검증 영역은 다음입니다.

- Java/Spring Boot API
- security filter
- rule-based detector
- RCA scenario
- evidence redaction
- analysis task queue
- RBAC authorization
- demo scenario pipeline
- evidence bundle export
- agent health classification
- impact scope caveat
- notification fallback
- metrics registration
- retention cleanup and FK-safe deletion order
- multi-signal incident correlation and causal timeline
- incident inactivity resolution, approval-work protection, and recurrence lineage
- curated correlation false-positive dataset
- production configuration validation
- agent protocol configuration

---

## English Reference

## Test Commands

Java backend:

```text
cd web-console
mvn test
```

Python agent:

```text
python -m compileall node_agent
pytest
```

Frontend:

```text
cd web-console/frontend
npm ci
npm run build
```

Helm charts:

```text
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform
helm lint charts/cluster-infra-rca-agent
helm template rca-agent charts/cluster-infra-rca-agent
```

Docker image:

```text
docker build -f Dockerfile.web-console .
```

## Important Test Classes

| Area | Example |
| --- | --- |
| RCA scenarios | `RuleBasedScenarioTests` |
| Redaction | `EvidenceRedactionIntegrationTests` |
| Queue/concurrency | `AnalysisTaskConcurrencyTests` |
| Auth/RBAC | `RbacAuthorizationTests`, `PlatformHttpTests` |
| Production config | `ProductionSecurityValidatorTests` |
| Agent protocol config | `AgentProtocolConfigurationValidatorTests` |
| Demo pipeline | `DemoScenarioIntegrationTests` |
| Agent health | `AgentHealthServiceTests` |
| Impact scope | `ImpactScopeAnalyzerTests` |
| Notification | `IncidentNotificationServiceTests` |
| Metrics | `RcaMetricsTests` |
| Retention | `RetentionRepositoryTests`, `DatabaseCompatibilityTests` |
| Incident correlation | `IncidentCorrelationServiceTests`, `IncidentCorrelationDatasetTests`, `IncidentTimelineServiceTests` |
| Incident lifecycle | `IncidentLifecycleRepositoryTests`, `DatabaseCompatibilityTests` |
| LLM fallback | `LlmAnalysisServiceTests` |

## CI Intent

CI should keep validation separated:

```text
node-agent-test
frontend-build
web-console-test
helm-validate
docker-build
```

The image build should depend on test and chart validation jobs.

## Manual Smoke Test

1. Start the local stack.
2. Login as administrator.
3. Register a demo or test cluster.
4. Run a demo scenario.
5. Process an analysis task.
6. Inspect confidence score, timeline, and impact scope.
7. Download evidence bundle as an operational role.
8. Verify lower-privilege roles have limited access.
9. Create an action request and verify the manual workflow.
10. Check metrics endpoint with an authorized identity.
11. Verify retention is disabled or uses non-destructive test cutoffs during smoke tests.
