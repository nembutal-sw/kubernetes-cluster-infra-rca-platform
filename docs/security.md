# Security

## 한국어 요약

이 프로젝트의 보안 방향은 “장애 진단은 자동화하되, 운영 변경은 사람이 승인하고 처리한다”입니다.

현재 보안 경계는 다음처럼 나뉩니다.

- 사용자 API: platform session + RBAC
- Agent API: bootstrap 또는 Kubernetes identity + node credential
- Webhook API: shared webhook credential
- Manifest API: manifest access filter
- Metrics API: operational role or metrics credential
- Export API: operational role only
- Action workflow: manual approval and audit only

Node Agent는 evidence collection 역할에 집중합니다. 운영 환경 변경 작업은 Platform이나 Agent가 직접 수행하지 않습니다.

---

## English Reference

## Request Authentication

Authentication is handled at the Spring Security filter chain boundary.

| Boundary | Filter |
| --- | --- |
| User API | `PlatformAuthenticationFilter` |
| Agent API | `AgentAuthenticationFilter` |
| Webhook API | `WebhookAuthenticationFilter` |
| Manifest access | `ManifestAccessFilter` |
| Agent mTLS | `AgentMtlsFilter` |
| Request size | `RequestBodyLimitFilter` |
| Metrics access | `MetricsAuthenticationFilter` |
| Browser mutation guard | `SameOriginMutationFilter` |

## User Roles

Current roles:

```text
ADMIN
OPERATOR
VIEWER
APPROVER
AUDITOR
METRICS
```

Examples:

- `VIEWER` can read dashboards and reports but cannot mutate resources.
- `APPROVER` can approve or reject action requests.
- `AUDITOR` can read audit and operational review surfaces.
- `ADMIN` and `OPERATOR` can export reports and evidence bundles.

## Agent Authentication

Agent protocol v2 supports two registration identities. `bootstrap-token` uses a short-lived cluster credential. `kubernetes-token-review` uses a projected ServiceAccount token and validates it against an administrator-configured API Server and CA. After either registration path succeeds, the platform issues a node-specific credential.

TokenReview enrollment uses the Agent projected token only as the object being reviewed. Its dedicated enrollment audience must not match any configured Kubernetes API audience. TokenReview and Pod lookup authenticate with a separate Backend reviewer credential. The expected audience, ServiceAccount subject and UID, groups, Pod UID, namespace, requested node, Running state, required labels, DaemonSet controller UID, and Agent image digest must all match. Agent-provided API URLs, CA bundles, and enrollment metadata are never trusted. Raw identity tokens and CA contents are excluded from API responses and audit details.

Subsequent agent calls must identify:

- cluster
- node name
- node credential

The platform verifies the cluster/node binding and node-scoped Bearer credential before processing heartbeat, evidence, token rotation, and realtime event calls. Legacy protocol v1 body credentials remain temporarily accepted for rolling upgrades.

The bootstrap credential expires after `RCA_AGENT_BOOTSTRAP_TOKEN_TTL_SECONDS` (default 1800 seconds). Administrators can rotate or revoke it, and can revoke individual node credentials. Nodes can rotate their own credential after authenticating with the current value.

Human passwords use PBKDF2-HMAC-SHA256. Random 256-bit bootstrap and node credentials use HMAC-SHA-256 with a versioned key ring, avoiding password-hash CPU cost on every Agent request. The v2 storage format includes a bounded key id, while v1 and PBKDF2 token hashes remain readable during migration. Production validates the current and previous peppers, rejects duplicate key material, and conditionally rehashes only after an explicit rolling-rotation phase. A concurrent rotation or revocation wins the compare-and-set update race and the stale token is rejected. See [Opaque Token Pepper Rotation](opaque-token-key-rotation.md).

The Agent requests node-token rotation every 30 days by default. It durably stages the pending value, proves it with a heartbeat, and then commits it locally. Restart, transient API failure, and rejected-pending rollback are covered without reusing the bootstrap credential.

Enrollment profiles carry a monotonically increasing version. Security-contract changes revoke existing node credentials, and node authentication requires the stored version to match the current profile. Pre-V24 unbound credentials are rejected by default and can only be accepted by a cluster-scoped UTC deadline no more than 30 days ahead. A platform-wide grace setting is rejected at startup. An active Kubernetes identity cannot be replaced by a different Pod UID until an administrator explicitly revokes the node credential.

Strict TokenReview mode revokes the cluster bootstrap credential and disables fallback. Switching back to bootstrap mode does not silently mint a replacement; the Console reports that explicit token rotation is required.

## Webhook Authentication

Alertmanager webhook ingestion requires the configured webhook credential. A blank credential never
authenticates a request. In production, blank or known development values fail startup validation.

## Manifest Download

The cluster bootstrap credential is not accepted in the manifest URL. The install-command API issues
a short-lived, single-use manifest token whose SHA-256 hash is stored in the database.

## Metrics Authentication

Metrics endpoints require one of:

```text
ADMIN, OPERATOR, AUDITOR, METRICS
```

The `METRICS` role is intended for a trusted scraper.

## Production Fail-Fast

Production validation rejects unsafe settings, including:

- weak default administrator settings
- missing or development-only boundary credentials
- weak database credential
- non-HTTPS public URL
- incomplete LLM configuration when LLM is enabled
- invalid notification URL when notification is enabled
- observability enabled without safe scraper authentication
- demo mode enabled in production
- audit disabled in production
- missing encryption material

## Export Security

Report export and evidence bundle export are restricted to operational roles.

Evidence bundle export:

- redacts sensitive values
- applies bundle size limits
- includes a SHA-256 integrity manifest
- can include an HMAC-SHA256 manifest signature when `RCA_EXPORT_SIGNATURE_SECRET` is configured
- can be verified offline with `scripts/verify_evidence_bundle.py`
- writes audit events
- returns `application/zip`
- uses `Cache-Control: no-store`

## LLM Safety

LLM is a diagnostic assistant only.

Rules:

- LLM output must be based on supplied evidence.
- LLM-origin actions are not automatically executable.
- LLM failures should not fail rule-based RCA.
- LLM input/output should be redacted.
- Policy Engine remains the final guardrail.

## Manual Action Safety

Action approval records human decision-making. Approval does not cause the platform or agent to perform operational changes.

## mTLS And Private CA

Agent client supports private CA and client certificate configuration. When
`RCA_AGENT_MTLS_REQUIRED=true`, Agent API requests must include a client certificate accepted by
the servlet container or upstream TLS termination configuration.

## Future Enterprise Security Work

- tenant-aware access scope
- permission matrix
- strict agent protocol mode
- external SIEM delivery
- retention policy enforcement
- external identity provider integration
- customer-managed encryption key support
