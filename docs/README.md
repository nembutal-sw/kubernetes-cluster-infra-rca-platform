# Documentation Index

## 한국어 요약

이 디렉터리는 Kubernetes Cluster Infra RCA Platform의 현재 설계와 운영 방식을 설명합니다.

문서 구성 원칙은 다음과 같습니다.

- 각 문서 앞부분은 한국어 요약으로 작성합니다.
- 구현/API/운영 기준은 English reference로 작성합니다.
- 포트폴리오와 면접에서는 한국어 요약을 활용합니다.
- 실제 개발과 운영에서는 English reference를 기준으로 봅니다.

현재 프로젝트의 핵심 메시지는 다음입니다.

> Node/Linux level RCA는 자동화하지만, 운영 변경은 Platform이나 Agent가 직접 수행하지 않습니다. 조치는 manual approval, audit, runbook, GitOps review 흐름으로 처리합니다.

---

## Documents

| Document | Purpose |
| --- | --- |
| `agent-api.md` | Node Agent registration, heartbeat, evidence, protocol contract |
| `backend-api.md` | Platform API, auth boundaries, reports, actions, demo, metrics |
| `node-agent.md` | Python agent runtime, collectors, spool, protocol, safety boundary |
| `security.md` | authentication filters, RBAC, production validation, export safety |
| `policy-engine.md` | action classification, guardrails, manual-only workflow |
| `audit-and-actions.md` | audit events and manual action request lifecycle |
| `report-schema.md` | RCA report fields, confidence score, impact scope, bundles |
| `observability.md` | metrics, Actuator, ServiceMonitor, operational gauges |
| `helm-platform-chart.md` | platform Helm values, production notes, ServiceMonitor |
| `web-console.md` | UI views and product/portfolio messaging |
| `testing.md` | validation commands and regression focus areas |
| `roadmap.md` | completed phases and next priorities |

## Current Architecture Summary

```text
Alertmanager or Demo Scenario
  -> Platform API
  -> Evidence Request or Demo Evidence
  -> Node Agent read-only collection
  -> Durable Analysis Task
  -> Rule-based Detector Engine
  -> Optional LLM explanation
  -> Policy Engine
  -> RCA Report
  -> Timeline / Confidence / Impact Scope
  -> Manual Action Workflow / Audit / Notification
```

## Current Safety Position

- Agent performs read-only evidence collection.
- eBPF realtime events are optional.
- Agent-side action execution has been removed.
- Approval records manual handling, not automatic operation.
- LLM suggestions remain diagnostic.
- Report and evidence bundle export are role-restricted and audited.
- Production profile performs fail-fast validation.

## Suggested Reading Order

1. `README.md` at repository root
2. `docs/README.md`
3. `docs/node-agent.md`
4. `docs/backend-api.md`
5. `docs/report-schema.md`
6. `docs/policy-engine.md`
7. `docs/security.md`
8. `docs/observability.md`
9. `docs/testing.md`
10. `docs/roadmap.md`
