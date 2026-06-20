# Web Console

## 한국어 요약

Web Console은 RCA Platform을 시각적으로 보여주는 포트폴리오 핵심 화면입니다. 단순 CRUD UI가 아니라, 장애 분석 흐름과 운영 판단 과정을 보여주는 콘솔입니다.

현재 UI에서 강조해야 할 기능은 다음입니다.

- Cluster 등록 및 Agent 설치 안내
- Agent Health Dashboard
- RCA Reports
- Incident Timeline
- Evidence Bundle Download
- Demo Scenario Mode
- Root Cause Candidate confidence score
- Impact Scope
- Observed Services와 service impact caveat
- Manual-only Action Request Workflow
- Audit Events
- Platform/Agent protocol info
- Observability metrics 접근 안내

---

## English Reference

## Architecture

The Web Console is served by the same Spring Boot application as the API.

```text
Spring Boot
  ├── API controllers
  ├── JSP shell
  └── React + Bootstrap UI assets
```

There is no separate frontend server or API proxy in the current deployment model.

## Main Views

### Dashboard

Recommended content:

- cluster count
- active incidents
- queued/dead-letter analysis tasks
- offline/stale agents
- latest reports
- recent audit events

### Clusters

Cluster pages should show:

- cluster metadata
- install command
- agent manifest access
- registered agents
- agent health classification
- last heartbeat age
- agent version/protocol version
- collector health summary

### RCA Reports

Report detail should show:

- summary
- most likely cause
- confidence level
- root cause candidates
- confidence score per candidate
- matched evidence paths
- derived signals
- recommended actions
- policy/guardrail labels
- impact scope
- evidence bundle download button

### Incidents

Incident detail should show:

- correlated reports
- first/last seen time
- severity/symptom
- affected node
- timeline events
- bundle export

Timeline is RCA flow, not audit history.

### Demo Scenarios

Demo Mode allows reproducible portfolio demos without damaging a real cluster.

Supported scenario types include:

```text
Disk Pressure
Memory Pressure
Kubelet Failure
Container Runtime Failure
CoreDNS Latency
CNI MTU Mismatch
Conntrack Exhaustion
Etcd Latency
API Server Latency
Systemd Restart Loop
```

Demo execution is available only when `RCA_DEMO_ENABLED=true` and is forbidden in production.

### Action Requests

The UI must communicate that approval is manual-only.

Good wording:

```text
Approval records authorization for a human-operated runbook. The platform and node agent will not execute this command.
```

Action request states:

```text
pending_approval
approved_manual
rejected
completed_manual
blocked
accepted
```

### Evidence Bundle Export

Export buttons should be visible only to roles allowed by the backend:

```text
ADMIN
OPERATOR
```

The download returns a redacted ZIP bundle.

### Impact Scope

Use conservative labels:

```text
Affected Pods
Affected Namespaces
Affected Workloads
Observed Services
Service Impact Assessment
```

Avoid claiming a service is affected unless endpoint/selector/traffic correlation is implemented.

## Platform Info

The UI can call:

```text
GET /api/v1/platform/info
```

and display:

```text
platform version
API version
agent protocol version
minimum supported agent protocol version
minimum supported agent version
```

## RBAC UX

Recommended UI behavior:

```text
VIEWER    hide mutation/export buttons
APPROVER  show approval/rejection, hide export
OPERATOR  show operation and export controls
AUDITOR   show audit/metrics focused views
ADMIN     show all configuration controls
```

Backend authorization remains the source of truth. UI hiding is only a usability layer.

## Development

```bash
cd web-console/frontend
npm ci
npm run build
```

The Spring Boot build should package the generated frontend assets.

## UX Principle

The console should make the safety model obvious:

> Explain the cause, show the evidence, preserve the audit trail, and keep risky remediation under human control.
