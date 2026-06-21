# Platform API

## 한국어 요약

Platform API는 Spring Boot Web Console과 Node Agent, Alertmanager webhook, RCA pipeline을 하나로 묶는 중앙 API입니다.

현재 API의 핵심 특징은 다음입니다.

- 사용자 API는 bearer token 기반 stateless session으로 보호합니다.
- Agent/Webhook/Manifest/Metrics 인증은 Spring Security filter chain에서 먼저 처리합니다.
- Agent는 read-only evidence와 realtime event만 제출합니다.
- 승인 조치 실행은 manual workflow로 제한됩니다.
- Report/evidence bundle export는 `ADMIN`, `OPERATOR`에게만 허용합니다.
- `/api/v1/platform/info`로 platform/API/agent protocol 정보를 제공합니다.

---

## English Reference

## Base URL

Default local URL:

```text
http://localhost:8080
```

The UI and API are served from the same Spring Boot application.

## Authentication

### User login

```text
POST /api/auth/login
```

Request:

```json
{
  "username": "admin",
  "password": "admin"
}
```

Response:

```json
{
  "access_token": "...",
  "token_type": "Bearer",
  "expires_at": "2026-06-21T00:00:00Z",
  "user": {
    "email": "admin@example.com",
    "role": "admin"
  }
}
```

User API calls use:

```text
Authorization: Bearer <access_token>
```

## Platform Info

```text
GET /api/platform/info
GET /api/v1/platform/info
```

Requires an authenticated platform user.

```json
{
  "platform_version": "0.1.0",
  "api_version": "v1",
  "agent_protocol_version": "1",
  "minimum_supported_agent_protocol_version": "1",
  "minimum_supported_agent_version": "0.1.0"
}
```

## Cluster APIs

```text
GET    /api/clusters
POST   /api/clusters
GET    /api/clusters/{cluster_id}
DELETE /api/clusters/{cluster_id}
GET    /api/clusters/{cluster_id}/install-command
GET    /api/clusters/{cluster_id}/agent-manifest
GET    /api/clusters/{cluster_id}/agent-health
```

`agent-manifest` is guarded by manifest access credentials. Agent health classifies agents as:

```text
healthy
stale
offline
unauthorized
version_mismatch
collector_degraded
```

## Evidence APIs

```text
POST /api/evidence/requests
GET  /api/evidence/requests/{request_id}
GET  /api/evidence/{evidence_id}
```

`POST /api/evidence/requests` creates read-only evidence collection work for a registered node agent.

## RCA Report APIs

```text
GET  /api/rca/reports
GET  /api/rca/reports/{report_id}
GET  /api/rca/reports/export
GET  /api/rca/reports/{report_id}/export
GET  /api/rca/reports/{report_id}/bundle
```

Export endpoints are restricted to `ADMIN` and `OPERATOR`.

Report bundle export returns `application/zip` and includes:

```text
summary.json
evidence/*.json
signals.json
timeline.json
rca-report.md
```

Sensitive values are redacted before export.

## Incident APIs

```text
GET /api/rca/incidents
GET /api/rca/incidents/{incident_id}
GET /api/rca/incidents/{incident_id}/timeline
GET /api/rca/incidents/{incident_id}/bundle
```

Timeline is an RCA analysis flow, not an audit trail. Each node exposes `signal_family`; each edge
exposes `rule_id`, `relationship`, `confidence`, and `inferred`. Audit events record user/system
actions, including `incident.root_cause_promoted`.

## Analysis Task APIs

```text
GET  /api/rca/analysis-tasks
POST /api/rca/analysis-tasks/{task_id}/requeue
```

Analysis tasks are durable queue records. Workers claim tasks by lease and update status through queued, processing, retry_wait, completed, skipped, failed, or dead_letter states.

## Action Request APIs

```text
POST /api/rca/reports/{report_id}/actions/{action_index}/execute
GET  /api/rca/action-requests
POST /api/rca/action-requests/{action_request_id}/approve
POST /api/rca/action-requests/{action_request_id}/reject
POST /api/rca/action-requests/{action_request_id}/complete-manual
```

Important: the endpoint name `execute` is legacy wording. The platform does not execute host mutation commands. It either creates a read-only evidence request or records a manual approval workflow.

## Demo Scenario APIs

```text
GET  /api/demo/scenarios
POST /api/demo/scenarios/{scenario_key}/run
```

Demo mode is disabled by default and must be disabled in production. Running a demo scenario creates demo evidence and queues a normal RCA analysis task.

## Metrics APIs

```text
GET /actuator/metrics
GET /actuator/prometheus
```

Allowed roles:

```text
ADMIN, OPERATOR, AUDITOR, METRICS
```

If `RCA_METRICS_TOKEN` is configured, Prometheus can authenticate with either:

```text
X-Metrics-Token: <token>
Authorization: Bearer <token>
```

## Error Conventions

Common response codes:

```text
400 bad request
401 login or agent credentials required
403 insufficient role or wrong agent assignment
404 resource not found
409 state conflict
410 deprecated endpoint disabled
413 export bundle too large
422 unsupported format or invalid status
```
