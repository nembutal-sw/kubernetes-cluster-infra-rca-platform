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
  "username": "platform-admin",
  "password": "<configured-password>"
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

### Account management

```text
POST /api/auth/change-login-id
POST /api/auth/change-password
POST /api/auth/logout
```

`change-login-id` and `change-password` require the current password. If the bootstrap admin account
changes its login ID, later application restarts do not recreate the original default login.

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
  "minimum_supported_agent_version": "0.1.0",
  "export_security": {
    "max_bundle_bytes": 10485760,
    "hash_algorithm": "SHA-256",
    "bundle_signature_enabled": true,
    "bundle_signature_algorithm": "HMAC-SHA256",
    "bundle_signature_key_id": "default",
    "offline_verifier": "scripts/verify_evidence_bundle.py"
  }
}
```

`export_security` reports export integrity settings only. It never returns signing secrets.

## Cluster APIs

```text
GET    /api/clusters
POST   /api/clusters
GET    /api/clusters/{cluster_id}
DELETE /api/clusters/{cluster_id}
GET    /api/clusters/{cluster_id}/install-command
GET    /api/clusters/{cluster_id}/agent-manifest
POST   /api/clusters/{cluster_id}/agent-token/rotate
GET    /api/clusters/{cluster_id}/topology/history
GET    /api/clusters/{cluster_id}/topology/compare
GET    /api/clusters/{cluster_id}/agent-health
GET    /api/clusters/{cluster_id}/topology
```

`agent-manifest` is guarded by manifest access credentials. Agent health classifies agents as:

The install-command response uses a short-lived, single-use `manifest_token`. The cluster
bootstrap token is not accepted as a manifest query parameter. Production manifest URLs must use
HTTPS.

Evidence request lists support `node_name`, `status`, `before`, and `limit` filters.
The default limit is 100 and the maximum is 200.

Audit users can export JSON or CSV:

```text
GET /api/audit/events/export?format=json
GET /api/audit/events/export?format=csv
```

```text
healthy
stale
offline
unauthorized
version_mismatch
collector_degraded
```

The topology response merges recent Node, Pod, workload, Service, and EndpointSlice observations.
Successful node-local Pod snapshots replace only that node's previous Pods. A complete elected-agent
snapshot replaces cluster-wide Service and Endpoint relationships. Partial or failed snapshots are
merged without expiring previously confirmed resources.

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
manifest.json
```

Sensitive values are redacted before export. `manifest.json` contains bundle metadata and SHA-256 hashes for exported files except the manifest itself. If `RCA_EXPORT_SIGNATURE_SECRET` is configured, the manifest also includes an HMAC-SHA256 signature over the manifest metadata and entry hashes.

## Incident APIs

```text
GET /api/rca/incidents
GET /api/rca/incidents/{incident_id}
GET /api/rca/incidents/{incident_id}/timeline
GET /api/rca/incidents/{incident_id}/bundle
POST /api/rca/incidents/{incident_id}/resolve
POST /api/rca/incidents/{incident_id}/reopen
```

Timeline is an RCA analysis flow, not an audit trail. Each node exposes `signal_family`; each edge
exposes `rule_id`, `relationship`, `confidence`, and `inferred`. Audit events record user/system
actions, including `incident.root_cause_promoted`.

Incident responses also expose resolution metadata and recurrence lineage. Alertmanager `resolved`
events may close a matching incident, while the lifecycle scheduler resolves inactive incidents that
have no pending approval or manual-completion work.

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
413 request body or export bundle too large
422 unsupported format or invalid status
```
