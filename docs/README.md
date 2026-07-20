# Documentation Index

Kubernetes Cluster Infra RCA Platform의 설계, API, 보안, 운영 검증 문서를 모아둔 인덱스입니다.

## Documents

Typed Evidence 계약과 정량 RCA 품질 게이트는 `evidence-schema-and-quality.md`를 참고합니다.

| Document | Purpose |
| --- | --- |
| `architecture.md` | 전체 아키텍처와 주요 컴포넌트 |
| `agent-api.md` | Agent 등록, heartbeat, evidence API 계약 |
| `collector-output-contract.md` | Collector 출력 envelope, 안정 필드, degraded 상태 |
| `evidence-schema-and-quality.md` | Collector typed evidence 계약과 정량 RCA 품질 게이트 |
| `node-agent.md` | Python Node Agent runtime, collectors, spool, safety boundary |
| `agent-evidence-fields.md` | Collector별 evidence field 참고 |
| `agent-permission-model.md` | `safe`, `node-diagnostics`, `ebpf` 권한 모델 |
| `backend-api.md` | Platform API, 인증 경계, report/action/demo/metrics |
| `report-schema.md` | RCA report, quality gate, timeline, bundle schema |
| `rca-analysis-rules.md` | Rule-based detector와 action classification 기준 |
| `rca-scenario-matrix.md` | 필수 RCA 장애 시나리오와 fixture 검증 기준 |
| `incident-correlation.md` | multi-signal correlation, root-cause promotion, timeline |
| `evidence-preprocessing.md` | LLM 입력용 evidence 전처리 payload |
| `llm-analyzer.md` | LLM provider, fallback, diagnostic-only 원칙 |
| `policy-engine.md` | 조치 등급, guardrail, manual-only workflow |
| `audit-and-actions.md` | Audit event와 human-in-the-loop action request lifecycle |
| `gitops.md` | 승인된 catalog 변경의 GitHub/Gitea PR 및 GitLab MR 생성과 배포 결과 추적 |
| `pagination.md` | Report, Incident, Analysis Task cursor pagination과 검색·필터 계약 |
| `web-console.md` | Web Console 화면 구성과 운영자 workflow |
| `security.md` | 인증 필터, RBAC, production validation, export 제한 |
| `threat-model.md` | 자산, 신뢰 경계, abuse case, mitigation |
| `observability.md` | Metrics, Actuator, ServiceMonitor, SLO 지표 |
| `database.md` | PostgreSQL/MariaDB 호환, migration, backup |
| `retention-policy.md` | 보존 기간과 FK-safe cleanup |
| `daemonset-operations-checklist.md` | Agent DaemonSet 운영 배포 체크리스트 |
| `daemonset-production-validation.md` | read-only canary rollout과 운영 검증 절차 |
| `runtime-compatibility.md` | 배포판/runtime/CNI fingerprint와 실검증 compatibility matrix |
| `release-readiness.md` | Helm, container, smoke, Agent, Kubernetes canary release gate |
| `helm-platform-chart.md` | Platform Helm chart values와 production note |
| `helm-agent-chart.md` | Agent Helm chart values와 배포 예시 |
| `testing.md` | 로컬/운영/Helm/DaemonSet 검증 명령 |
| `operations.md` | 백업, 복구, HA, credential rotation |
| `roadmap.md` | 완료 단계와 다음 우선순위 |
| `code-review-action-plan-2026-07-10.md` | 최신 코드 리뷰 기반 P0 실행 계획과 완료 기준 |

## Architecture Flow

```text
Alertmanager / Platform Scheduler / Demo Scenario
  -> Platform API
  -> Evidence Request or Demo Evidence
  -> Node Agent read-only collection
  -> Durable Analysis Task
  -> Rule-based Detector Engine
  -> Evidence Quality / Report Quality Gate
  -> Multi-signal Incident Correlation
  -> Optional LLM explanation
  -> Policy Engine
  -> RCA Report
  -> Timeline / Confidence / Impact Scope
  -> Manual Action Workflow / GitOps PR / Audit / Notification
```

## Safety Position

- Agent는 read-only evidence collection만 수행합니다.
- Agent-side action execution은 사용하지 않습니다.
- 승인 workflow는 직접 실행이 아니라 승인/거절 기록, 수동 처리 완료, runbook 또는 reviewed GitOps PR입니다.
- LLM 제안은 진단 보조이며 `automation_allowed=false`를 유지합니다.
- Report/export는 역할 기반으로 제한하고 audit event를 남깁니다.
- Production profile은 위험한 기본 설정을 fail-fast로 차단합니다.

## Suggested Reading Order

1. `architecture.md`
2. `node-agent.md`
3. `collector-output-contract.md`
4. `backend-api.md`
5. `report-schema.md`
6. `rca-scenario-matrix.md`
7. `policy-engine.md`
8. `security.md`
9. `daemonset-production-validation.md`
10. `testing.md`
