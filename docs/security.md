# Security

## 한국어 요약

이 프로젝트의 보안 방향은 “장애 진단은 자동화하되, 운영 변경은 사람이 승인하고 처리한다”입니다.

현재 보안 경계는 다음처럼 나뉩니다.

- 사용자 API: platform session + RBAC
- Agent API: cluster credential + node credential
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

Agent registration uses a cluster-level credential. After registration, the platform issues a node-specific credential.

Subsequent agent calls must identify:

- cluster
- node name
- cluster credential
- node credential

The platform verifies these fields before processing heartbeat, evidence, and realtime event calls.

## Webhook Authentication

Alertmanager webhook ingestion requires the configured webhook credential. In production, blank or known development values fail startup validation.

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

Agent client supports private CA and client certificate configuration for mTLS-style deployments. Certificate and key must be configured together.

## Future Enterprise Security Work

- tenant-aware access scope
- permission matrix
- credential rotation
- strict agent protocol mode
- audit export
- retention policy enforcement
- external identity provider integration
- customer-managed encryption key support
