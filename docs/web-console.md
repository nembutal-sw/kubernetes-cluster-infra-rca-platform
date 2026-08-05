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
- API별 loading/error/stale 상태와 마지막 갱신 시각
- 일부 API 실패 시 마지막 정상 데이터 유지와 재시도 배너

UI 메시지는 “자동 처리”보다 “근거 기반 RCA와 안전한 수동 운영 절차”를 강조해야 합니다.

---

## English Reference

## Frontend Shape

The Web Console is bundled inside the Spring Boot platform. It uses:

- Spring Boot static asset shell
- React 19.2.7 UI with React Router 8.3.0
- TypeScript 6.0.3 and Vite 8.2.0
- Bootstrap 5.3.8 styling
- same-origin API calls

There is no separate frontend server or API proxy.

## Data Reliability UX

Each console data source keeps independent `loading`, `error`, `loadedAt`, and `stale` state.
An API failure does not replace the last successful data with an empty array. The console shows
the failed source, HTTP status, error code, trace ID, last complete refresh time, and a retry action.

The console refreshes every 30 seconds while the browser tab is visible. It pauses background
polling for hidden tabs. Session expiry is handled centrally and returns the user to the login page.

Dashboard Agent Health uses `GET /api/v1/agent-health` instead of issuing one request per cluster.

## URL Routing

The browser URL is the source of truth for the active view and selected resource. Supported routes are:

```text
/overview
/clusters
/clusters/:clusterId
/reports
/reports/:reportId
/incidents
/incidents/:incidentId
/pipeline
/audit
/webhooks
/settings
```

`/` and `/console` are normalized to `/overview`. Detail URLs support direct entry, refresh,
bookmarking, and browser history. After authentication, the console resumes the URL the user
requested. A route outside the user's role is redirected to `/overview`; a missing detail resource
keeps its URL and shows an explicit not-found notice with a return-to-list action.

## Workflow E2E

Playwright workflow tests run against an isolated Spring Boot process with an in-memory H2 database.
They cover protected URL login/session expiry, cluster onboarding and install command generation,
Demo Evidence to RCA report processing, approval/rejection/manual completion, Viewer UI restrictions,
structured partial API failures with stale-data recovery, all Agent connection states, mobile overflow,
and keyboard confirmation. The test environment never connects to an operational database or cluster.

CI runs this suite as the separate `console-workflow-e2e` job. Failed runs retain the HTML report,
trace, screenshot, and video as a seven-day artifact.

## Main Views

| View | Purpose |
| --- | --- |
| Clusters | register clusters, inspect fleet-wide Agent health, filter connection states, view install command |
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
- provider-grounded supporting evidence IDs
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

The report detail view should also show:

- manifest entry count and evidence count
- SHA-256 hash algorithm
- signature enabled/disabled status
- ZIP/raw payload size
- offline verifier command
- a short list of manifest entry hashes

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
