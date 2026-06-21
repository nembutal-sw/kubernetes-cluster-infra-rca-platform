# Web Console

## 한국어 요약

Web Console은 RCA Platform을 시각적으로 보여주는 포트폴리오 핵심 화면입니다. 단순 CRUD UI가 아니라, 장애 분석 흐름과 운영 판단 과정을 보여주는 콘솔입니다.

현재 UI에서 강조해야 할 기능은 다음입니다.

- Cluster 등록과 Agent install manifest 확인
- RCA report 목록과 상세 조회
- causal incident timeline with signal family, rule ID, and confidence
- RCA confidence score
- Impact scope
- Agent health dashboard
- Evidence bundle download
- Demo Scenario Mode
- Action request / manual approval workflow
- Audit event 조회
- Observability metrics link

UI 메시지는 “자동 처리”보다 “근거 기반 RCA와 안전한 수동 운영 절차”를 강조해야 합니다.

---

## English Reference

## Frontend Shape

The Web Console is bundled inside the Spring Boot platform. It uses:

- JSP shell
- React UI
- Bootstrap 5 styling
- same-origin API calls

There is no separate frontend server or API proxy.

## Main Views

| View | Purpose |
| --- | --- |
| Clusters | register clusters, inspect status, view agent install command |
| Reports | inspect RCA reports and confidence scores |
| Incidents | inspect correlation and timeline |
| Pipeline | inspect analysis task queue |
| Agent Health | classify agent freshness, version, and collector state |
| Demo Scenarios | run fixture-based RCA demos |
| Audit | review security and workflow events |
| Actions | review manual action requests |

## RCA Report UX

Report detail should show:

- symptom
- most likely cause
- confidence
- root cause candidates
- confidence score
- supporting evidence
- evidence paths
- derived signals
- impact scope
- observed services vs confirmed affected services
- recommended actions
- policy classification
- guardrails

## Evidence Bundle UX

Evidence bundle download should be visible only to authorized operational roles. The UI should describe the bundle as a redacted diagnostic package.

Recommended label:

```text
Download redacted evidence bundle
```

## Demo Scenario UX

Demo Scenario Mode should clearly indicate that it uses generated evidence fixtures.

Recommended copy:

```text
Demo scenarios use fixture evidence and run through the same RCA pipeline as agent-submitted evidence.
```

Demo mode must be disabled in production.

## Manual Action UX

Action UI should make the manual workflow clear.

Recommended copy:

```text
Approval records manual handling. Complete the request after the external runbook or review process is done.
```

For GitOps-style actions, show YAML previews as review material only.

## Agent Health UX

Statuses:

```text
healthy
stale
offline
unauthorized
version_mismatch
collector_degraded
```

Show:

- node name
- agent version
- agent protocol version
- platform protocol range
- last heartbeat
- heartbeat age
- supported collectors
- health reasons

## Observability UX

The UI can link to metrics documentation or show operational summaries, but raw actuator endpoints should remain protected.

## Portfolio Message

> Web Console은 장애의 결론만 보여주는 화면이 아니라, evidence, signal, confidence, timeline, impact scope, approval, audit까지 이어지는 운영 판단 과정을 보여주는 콘솔입니다.
