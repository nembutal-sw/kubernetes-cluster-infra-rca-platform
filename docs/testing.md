# Testing

테스트는 빌드 성공 여부만 확인하지 않습니다. 이 프로젝트의 핵심인 read-only evidence 수집, Rule-based RCA, 수동 승인 workflow, audit, export 권한, 운영 배포 가능성을 함께 검증합니다.

## Local Checks

Java/Spring Boot:

```bash
cd web-console
mvn test
```

Python Agent:

```bash
python -m compileall node_agent scripts
pytest
```

Frontend:

```bash
cd web-console/frontend
npm ci
npm run build
```

Helm:

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform
helm lint charts/cluster-infra-rca-agent --set backendUrl=https://rca.example.com
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=agent-auth
```

Backup/restore:

```bash
bash scripts/validate-database-backup.sh
```

Docker image:

```bash
docker build -f Dockerfile.web-console .
```

## Runtime Smoke

로컬 또는 서버에 플랫폼이 떠 있으면 운영 시나리오 검증 러너를 실행합니다.

```bash
export RCA_BASE_URL=http://127.0.0.1:18080
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='<admin-password>'

python3 scripts/operational_scenario_validation.py \
  --output-dir validation-results/operational-scenarios
```

`--cluster-id`를 지정하지 않으면 시나리오마다 격리된 validation cluster를 생성합니다. 이렇게 해야 기존 incident correlation 결과가 다른 시나리오 검증에 섞이지 않습니다.

특정 시나리오만 실행할 수도 있습니다.

```bash
python3 scripts/operational_scenario_validation.py \
  --scenarios disk-pressure,inode-exhaustion,node-not-ready
```

검증 기준:

- Analysis task가 `completed`인지 확인
- RCA report에 root cause candidate가 있는지 확인
- evidence path와 derived signal이 비어 있지 않은지 확인
- incident timeline node가 생성되는지 확인
- LLM action은 항상 `automation_allowed=false`
- restart, cleanup, cordon, reboot, GitOps PR action은 자동 실행 불가

결과는 `validation-results/operational-scenarios/<timestamp>/summary.json`에 저장됩니다.

## DaemonSet Smoke

실제 Kubernetes 클러스터에 Agent를 배포한 뒤 read-only 검증을 실행합니다.

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

이 스크립트는 리소스를 만들거나 삭제하지 않습니다. `kubectl get`, `kubectl auth can-i`, `kubectl logs`만 사용합니다.

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
| RCA scenarios | `RuleBasedScenarioTests`, `DemoScenarioIntegrationTests` |
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

CI job은 다음처럼 분리합니다.

```text
node-agent-test
frontend-build
web-console-test
helm-validate
docker-build
```

이미지 빌드는 test와 chart validation 이후에 실행합니다.

## Manual Smoke Test

1. Platform health check 확인
2. 관리자 로그인
3. 테스트 클러스터 등록
4. Agent install command/manifest 생성
5. Demo scenario 또는 real evidence collection 실행
6. Analysis task 완료 확인
7. RCA report의 원인 후보, evidence, timeline, action policy 확인
8. evidence bundle export 권한 확인
9. approval workflow가 실행이 아니라 수동 처리 기록으로 끝나는지 확인
10. metrics endpoint와 audit event 확인
