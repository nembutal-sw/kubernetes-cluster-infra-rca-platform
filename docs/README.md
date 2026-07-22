# Documentation Index

Kubernetes Cluster Infra RCA Platform의 설계, API, 보안, 배포와 운영 검증 문서입니다. 처음 설치하는 사용자는 아래 권장 순서부터 확인하면 됩니다.

## Start Here

| 순서 | 문서 | 확인 내용 |
| --- | --- | --- |
| 1 | [architecture.md](architecture.md) | 전체 컴포넌트와 데이터 흐름 |
| 2 | [deployment.md](deployment.md) | 실행 환경과 배포 방식 |
| 3 | [install-flow.md](install-flow.md) | 클러스터 등록과 Agent 설치 순서 |
| 4 | [node-agent.md](node-agent.md) | Agent 동작, collector, spool |
| 5 | [web-console.md](web-console.md) | 화면과 운영자 workflow |
| 6 | [security.md](security.md) | 인증, production 설정, secret |
| 7 | [testing.md](testing.md) | 로컬, E2E, Helm, 실환경 검증 |

## Architecture And Scope

| 문서 | 내용 |
| --- | --- |
| [rca-scope.md](rca-scope.md) | 주요 진단 대상과 보조 evidence 범위 |
| [architecture.md](architecture.md) | Platform, Agent, DB, 분석 pipeline 구조 |
| [agent-design.md](agent-design.md) | Node Agent 내부 설계 |
| [durable-analysis-pipeline.md](durable-analysis-pipeline.md) | analysis task queue, lease, retry, dead letter |
| [incident-correlation.md](incident-correlation.md) | multi-signal correlation과 recurrence |
| [retention-policy.md](retention-policy.md) | 데이터 보존 기간과 FK-safe cleanup |
| [performance-tuning.md](performance-tuning.md) | DB pool, worker, polling, 수집량 조정 |

## Evidence And RCA

| 문서 | 내용 |
| --- | --- |
| [collector-output-contract.md](collector-output-contract.md) | Collector envelope과 degraded 상태 |
| [agent-evidence-fields.md](agent-evidence-fields.md) | Collector별 수집 필드 |
| [evidence-api.md](evidence-api.md) | Evidence API와 lifecycle |
| [evidence-preprocessing.md](evidence-preprocessing.md) | 정규화, 필터링, LLM 입력 payload |
| [evidence-schema-and-quality.md](evidence-schema-and-quality.md) | Typed Evidence와 RCA 품질 gate |
| [report-schema.md](report-schema.md) | Report, candidate, action, timeline schema |
| [rule-engine.md](rule-engine.md) | Rule Engine 구조 |
| [rca-analysis-rules.md](rca-analysis-rules.md) | 장애 유형별 detector와 action 분류 |
| [rca-scenario-matrix.md](rca-scenario-matrix.md) | 필수 장애 시나리오와 fixture 기준 |
| [threshold-overrides.md](threshold-overrides.md) | 기본 threshold와 클러스터별 override |
| [llm-analyzer.md](llm-analyzer.md) | Provider 설정, fallback, diagnostic-only 원칙 |
| [policy-engine.md](policy-engine.md) | 정책 등급과 manual-only guardrail |

## API And Security

| 문서 | 내용 |
| --- | --- |
| [backend-api.md](backend-api.md) | Platform API 전체 개요 |
| [agent-api.md](agent-api.md) | Agent 등록, heartbeat, evidence 계약 |
| [api-security-contract.md](api-security-contract.md) | Endpoint별 인증 필터와 CI guard |
| [rbac-matrix.md](rbac-matrix.md) | 역할별 조회, 변경, export 권한 |
| [audit-and-actions.md](audit-and-actions.md) | Audit와 승인/거절/수동 완료 lifecycle |
| [threat-model.md](threat-model.md) | 자산, 신뢰 경계, abuse case |
| [security.md](security.md) | Production fail-fast와 secret 관리 |
| [supply-chain.md](supply-chain.md) | SBOM, secret scan, image scan, signing |

## Deployment And Operations

| 문서 | 내용 |
| --- | --- |
| [deployment.md](deployment.md) | Docker, Kubernetes 배포 구성 |
| [helm-platform-chart.md](helm-platform-chart.md) | Platform chart와 DB/LLM/monitoring option |
| [helm-agent-chart.md](helm-agent-chart.md) | Agent chart, mode, canary option |
| [agent-permission-model.md](agent-permission-model.md) | `safe`, `node-diagnostics`, `ebpf` 권한 |
| [daemonset-operations-checklist.md](daemonset-operations-checklist.md) | 운영 배포 전 체크리스트 |
| [daemonset-production-validation.md](daemonset-production-validation.md) | read-only canary rollout |
| [database.md](database.md) | PostgreSQL/MariaDB migration과 backup |
| [operations.md](operations.md) | HA, backup, restore, credential rotation |
| [observability.md](observability.md) | Actuator, Prometheus, SLO metric |
| [catalogs.md](catalogs.md) | Collector, action, rule Catalog override |
| [gitops.md](gitops.md) | GitHub/GitLab/Gitea PR과 배포 결과 추적 |
| [pagination.md](pagination.md) | Cursor pagination과 검색·필터 |

## Validation And Compatibility

| 문서 | 내용 |
| --- | --- |
| [testing.md](testing.md) | 전체 테스트 명령과 검증 범위 |
| [linux-node-collector-validation.md](linux-node-collector-validation.md) | 실제 Linux collector 검증 |
| [real-cluster-validation.md](real-cluster-validation.md) | 실제 Kubernetes canary 절차 |
| [runtime-compatibility.md](runtime-compatibility.md) | 배포판, runtime, CNI compatibility matrix |
| [operational-burn-in.md](operational-burn-in.md) | Agent 장시간 안정성, cluster readiness, LLM 상태 통합 검증 |
| [release-readiness.md](release-readiness.md) | Release gate와 완료 조건 |

## Planning And History

| 문서 | 내용 |
| --- | --- |
| [roadmap.md](roadmap.md) | 완료 기능과 다음 실환경 우선순위 |
| [stabilization-roadmap.md](stabilization-roadmap.md) | 구조 안정화 phase 상태 |
| [code-review-action-plan-2026-07-10.md](code-review-action-plan-2026-07-10.md) | 코드 리뷰 후속 작업 |
| [enterprise-improvement-plan.md](enterprise-improvement-plan.md) | 엔터프라이즈 고도화 기준 |
| [phase1-structure-stabilization.md](phase1-structure-stabilization.md) | 초기 구조 안정화 기록 |
| [phase3-testing-ci.md](phase3-testing-ci.md) | 테스트와 CI 구축 기록 |

## Command Index

### 개발 환경 확인

Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts\windows-dev-check.ps1 -BootstrapMaven -Validate
```

Linux:

```bash
bash scripts/linux-dev-check.sh --full
```

### Platform과 Agent 검증

```bash
mvn -f web-console/pom.xml verify
cd web-console/frontend && npm ci && npm test && npm run build
mvn -f web-console/pom.xml -Pfrontend -DskipTests package
python -m pytest -q
python3 scripts/release-readiness-check.py
```

첫 Maven 명령은 Java 전용 검증입니다. React 정적 자산이 포함된 JAR은 `frontend` profile로만
생성하며 Frontend test/build는 npm lifecycle에서 독립 실행합니다.

### DB 호환성

```bash
mvn -f web-console/pom.xml -Dtest=DatabaseCompatibilityTests test
python3 scripts/verify_database_compatibility_report.py
```

### Helm

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca charts/cluster-infra-rca-platform

helm lint charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com
helm template rca-agent charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=agent-auth
```

### 운영 시나리오

```bash
export RCA_BASE_URL=https://rca.example.com
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='<admin-password>'

python3 scripts/operational_scenario_validation.py \
  --scenarios disk-pressure,inode-exhaustion,node-not-ready
```

### DaemonSet read-only 점검

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

### LLM과 Alertmanager

```bash
python3 scripts/llm-staging-smoke.py \
  --scenario disk-pressure \
  --expected-llm-status completed

python3 scripts/llm-burn-in-campaign.py \
  --base-url https://rca.example.com \
  --history validation-results/llm-staging-smoke/approved-history \
  --provider-call-budget 1 \
  --output-dir validation-results/llm-staging-smoke/campaign

python3 scripts/llm-burn-in-report.py \
  validation-results/llm-staging-smoke \
  --output validation-results/llm-staging-smoke/burn-in-report.json \
  --minimum-time-buckets 3 \
  --time-bucket-hours 8

python3 scripts/llm-burn-in-history.py \
  validation-results/llm-staging-smoke \
  --output-dir validation-results/llm-staging-smoke/portable-history

python3 scripts/llm-burn-in-planning-baseline.py \
  validation-results/llm-staging-smoke/approved-history \
  --output config/llm-burn-in-planning-baseline.json

python3 scripts/llm_prometheus_rule_test.py \
  --helm helm \
  --promtool promtool

python3 scripts/alertmanager_delivery_test.py \
  --helm helm \
  --prometheus prometheus \
  --alertmanager alertmanager
```

반복 provider 검증은 GitHub Actions의 수동 `LLM Burn-in` workflow로도 실행할 수 있습니다. 기본 dry-run, 실행당 최대 1회 호출, 8시간 구간 중복 방지, 변경 참조값, Environment 승인, 완료된 run의 누적 artifact 연결을 지원합니다. 실패 artifact는 allowlist에 있는 검증기 오탐만 오프라인으로 재검증합니다. 비민감 planning baseline은 호출 계획에만 사용하며 readiness 표본에는 포함하지 않습니다.

명령별 요구 도구와 추가 option은 [testing.md](testing.md)에 정리되어 있습니다.

## Safety Position

- Agent는 read-only evidence collection만 수행합니다.
- Agent-side action execution은 비활성화되어 있습니다.
- 승인 workflow는 직접 실행이 아니라 기록, 수동 처리, runbook, GitOps PR로 동작합니다.
- LLM action은 `automation_allowed=false`, `executable=false`입니다.
- Report와 evidence export는 역할 기반으로 제한하고 audit event를 남깁니다.
- Production profile은 기본 계정, 약한 secret, Demo Mode, insecure URL을 차단합니다.
