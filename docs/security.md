# Security

## 한국어 요약

이 프로젝트의 보안 방향은 “장애 진단은 자동화하되, 위험 조치는 자동 실행하지 않는다”입니다.

현재 보안 경계는 다음처럼 나뉩니다.

- 사용자 API: bearer token 기반 stateless session
- Agent API: `AgentAuthenticationFilter`에서 bootstrap token과 node token 검증
- Webhook API: webhook token 검증
- Manifest API: manifest access token 검증
- Metrics API: 운영 role 또는 metrics token 검증
- Production profile: 기본 secret, 빈 token, insecure URL, demo enabled 등을 fail-fast로 차단
- LLM: diagnostic helper로만 사용, action 자동 실행 불가
- Action workflow: approval/manual completion/audit only

---

## English Reference

## Authentication Layers

Spring Security filter chain includes:

```text
PlatformAuthenticationFilter
AgentAuthenticationFilter
WebhookAuthenticationFilter
ManifestAccessFilter
MetricsAuthenticationFilter
SameOriginMutationFilter
```

These filters protect the main external input boundaries before controller logic runs.

## User Authentication

Platform users authenticate through `/api/auth/login` and receive a bearer token. API roles include:

```text
ADMIN
OPERATOR
VIEWER
APPROVER
AUDITOR
METRICS
```

## Agent Authentication

Agent endpoints are `permitAll` at the URL layer but authenticated by `AgentAuthenticationFilter`.

Register:

```text
cluster_id + agent_token
```

Heartbeat/evidence/realtime:

```text
cluster_id + node_name + agent_token + node_token
```

Authentication failures are recorded as audit events when possible.

## Webhook Authentication

Alertmanager webhook ingestion requires the configured webhook token. Production mode rejects empty or development tokens.

## Metrics Authentication

Metrics endpoints require a platform role or `RCA_METRICS_TOKEN`.

Accepted token forms:

```text
X-Metrics-Token: <token>
Authorization: Bearer <token>
```

Metrics token must be non-default in production when observability is enabled.

## Production Fail-fast

Production profile is activated by:

```text
prod
production
```

Unsafe configuration causes startup failure. Examples:

```text
RCA_DEFAULT_ADMIN_PASSWORD=admin
RCA_WEBHOOK_TOKEN=
RCA_WEBHOOK_TOKEN=dev-webhook-token
RCA_DB_PASSWORD=rca_password
RCA_PUBLIC_API_BASE_URL=http://...
RCA_DEMO_ENABLED=true
RCA_AUDIT_ENABLED=false
RCA_ENCRYPTION_SECRET=
RCA_METRICS_TOKEN=, when observability is enabled
RCA_SLACK_WEBHOOK_URL=http://..., when notification is enabled
```

## LLM Safety

LLM is optional and disabled by default. It may summarize evidence or explain RCA results, but it must not:

```text
execute actions
claim remediation was performed
bypass Policy Engine
mark automation_allowed=true
invent unsupported facts
```

LLM-origin actions are always diagnostic suggestions.

## Action Safety

The platform no longer executes host mutation commands through the agent.

Disabled behavior:

```text
systemctl restart from agent
kubectl delete/drain from agent
node reboot
shell command execution
host filesystem mutation
```

Current behavior:

```text
read-only evidence collection
action request creation
approval/rejection audit
manual completion tracking
GitOps PR guidance
```

## Export Security

Report export and evidence bundle export are restricted to:

```text
ADMIN
OPERATOR
```

Exported bundles are redacted and use `Cache-Control: no-store`.

## mTLS Readiness

The Python Agent supports optional mTLS client certificate configuration:

```text
AGENT_CA_BUNDLE
AGENT_CLIENT_CERT
AGENT_CLIENT_KEY
```

Both client certificate and key must be provided together.

## Future Enterprise Hardening

Planned or recommended future items:

```text
OIDC/SAML SSO
advanced tenant-aware RBAC
agent token rotation
strict agent protocol enforcement mode
mTLS-required agent mode
KMS/Vault secret management
immutable audit export
SIEM forwarding
retention cleanup job
image signing and SBOM validation
```
