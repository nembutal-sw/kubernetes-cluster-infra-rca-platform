# Documentation Index

이 디렉터리는 Kubernetes Cluster Infra RCA Platform의 설계, API, 보안, 운영 절차를 정리합니다.

문서 작성 기준:

- 설명은 한글로 간결하게 작성합니다.
- API, 설정 키, 명령어, 스키마 이름은 원문 그대로 둡니다.
- 운영 절차는 재현 가능한 명령과 검증 기준을 함께 남깁니다.
- Agent와 Platform은 운영 변경을 직접 실행하지 않는다는 안전 원칙을 유지합니다.

## Documents

| Document | Purpose |
| --- | --- |
| `agent-api.md` | Agent registration, heartbeat, evidence, protocol contract |
| `backend-api.md` | Platform API, auth boundaries, reports, actions, demo, metrics |
| `node-agent.md` | Python Agent runtime, collectors, spool, safety boundary |
| `security.md` | 인증 필터, RBAC, production validation, export 제한 |
| `threat-model.md` | 자산, 신뢰 경계, abuse case, mitigation |
| `agent-permission-model.md` | `safe`, `node-diagnostics`, `ebpf` 권한 모델 |
| `daemonset-operations-checklist.md` | Agent DaemonSet 운영 배포 체크리스트 |
| `testing.md` | 개발/운영 검증 명령과 회귀 테스트 기준 |
| `operations.md` | 백업, 복구, HA, credential rotation |
| `policy-engine.md` | 조치 분류, guardrail, manual-only workflow |
| `audit-and-actions.md` | audit event와 수동 action request lifecycle |
| `report-schema.md` | RCA report 필드, confidence, impact scope, bundle |
| `incident-correlation.md` | multi-signal correlation, root-cause promotion, timeline |
| `observability.md` | Metrics, Actuator, ServiceMonitor, operational gauges |
| `retention-policy.md` | retention period, FK-safe cleanup |
| `helm-platform-chart.md` | Platform Helm values와 production note |
| `helm-agent-chart.md` | Agent Helm values와 배포 예시 |
| `web-console.md` | UI view와 운영 콘솔 방향 |
| `roadmap.md` | 완료된 단계와 다음 우선순위 |

## Current Architecture Summary

```text
Alertmanager / Platform Scheduler / Demo Scenario
  -> Platform API
  -> Evidence Request or Demo Evidence
  -> Node Agent read-only collection
  -> Durable Analysis Task
  -> Rule-based Detector Engine
  -> Multi-signal Incident Correlation
  -> Optional LLM explanation
  -> Policy Engine
  -> RCA Report
  -> Timeline / Confidence / Impact Scope
  -> Manual Action Workflow / Audit / Notification
```

## Current Safety Position

- Agent는 read-only evidence collection을 수행합니다.
- eBPF realtime event는 선택 기능입니다.
- Agent-side action execution은 제거되었습니다.
- Approval은 자동 실행이 아니라 수동 처리 기록입니다.
- LLM 제안은 진단 보조이며 자동화할 수 없습니다.
- Report/evidence bundle export는 역할 기반으로 제한하고 audit을 남깁니다.
- Retention cleanup은 open incident, active action workflow, 참조 중인 evidence를 보존합니다.
- Production profile은 위험한 기본 설정을 fail-fast로 차단합니다.

## Suggested Reading Order

1. 루트 [README.md](../README.md)
2. `docs/node-agent.md`
3. `docs/backend-api.md`
4. `docs/report-schema.md`
5. `docs/policy-engine.md`
6. `docs/security.md`
7. `docs/daemonset-operations-checklist.md`
8. `docs/testing.md`
9. `docs/observability.md`
10. `docs/retention-policy.md`
11. `docs/roadmap.md`
