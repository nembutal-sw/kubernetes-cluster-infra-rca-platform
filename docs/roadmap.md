# Roadmap

## 한국어 요약

현재 프로젝트는 단순 MVP에서 벗어나 운영 가능한 RCA 플랫폼 형태로 확장되었습니다.

완료된 핵심 기능은 다음입니다.

- Node/Linux level RCA
- Rule-based detector 구조
- durable analysis queue
- multi-signal incident correlation and root-cause promotion
- evidence bundle export
- incident timeline
- confidence score
- demo scenario mode
- agent health dashboard
- impact scope analysis
- notification service
- observability metrics
- agent protocol/version compatibility
- manual approval workflow
- production configuration validation
- scheduled retention cleanup
- cluster topology inventory
- topology-aware cross-node correlation
- one-time manifest download token
- Agent permission modes and payload limits
- topology history comparison
- audit event export
- Agent credential rotation and optional mTLS

앞으로의 방향은 기능을 무리하게 늘리는 것이 아니라, 문서화, 테스트, 운영 안정성, 엔터프라이즈 확장 기반을 정리하는 것입니다.

---

## Completed Phases

### Phase 1: Platform Foundation

- Spring Boot platform consolidation
- JDBC/Flyway persistence
- cluster registry
- user/session authentication
- role-based authorization
- CI validation pipeline

### Phase 2: RCA Pipeline

- Alertmanager webhook ingest
- evidence request lifecycle
- analysis task queue
- worker retry and dead-letter handling
- incident correlation
- RCA report generation

### Phase 3: Security And Structure

- Agent, webhook, manifest, and metrics filters
- production fail-fast validation
- repository façade split
- detector-based signal analysis
- sensitive data redaction
- regression tests

### Phase 4: RCA Operations Features

- Demo Scenario Mode
- Incident Timeline
- Evidence Bundle Export
- RCA Confidence Score
- Agent Health Dashboard
- Impact Scope Analysis
- Notification Service
- Manual Approval Workflow

### Phase 5: Operational Foundations

- Micrometer/Actuator metrics
- optional Prometheus ServiceMonitor
- metrics authentication
- agent protocol compatibility
- platform info endpoint
- manual-only action lifecycle
- FK-safe operational data retention
- retention audit events and maintenance metrics
- Incident Correlation v2 causal rules
- causal timeline edge confidence and rule visibility

### Phase 6: Topology Correlation

- Node, Pod, workload, Service, EndpointSlice inventory
- deterministic topology collector election
- topology observation persistence and API
- stale Node/Pod/Service expiry from authoritative snapshots
- responsive Service-to-Node topology graph
- confirmed Service impact scope
- strict cross-node correlation for cluster-global signal families
- multi-node incident visibility

### Phase 7: Security And Operational Hardening

- explicit initial administrator provisioning
- mandatory webhook authentication
- short-lived, single-use Agent manifest download token
- request body, log collection, and Agent payload limits
- `safe`, `node-diagnostics`, and `ebpf` Agent permission modes
- idempotent evidence response handling
- evidence request pagination and query indexes
- Kubernetes API response cache
- PostgreSQL and MariaDB backup/restore validation
- Agent bootstrap token rotation
- optional Agent mTLS enforcement
- topology history comparison and change highlighting
- role-restricted audit JSON/CSV export

---

## Near-Term Priorities

## Documentation

- Keep Korean summary sections for portfolio and interview readability.
- Keep English reference sections for API and operations usage.
- Keep action workflow documentation aligned with manual-only behavior.

## CI And Validation

- Confirm latest GitHub Actions run.
- Keep Java, Python, frontend, Helm, and Docker checks separated.
- Add workflow badge after stable validation.

## API And Agent Compatibility

- Expand `/api/v1` gradually.
- Add compatibility tests for agent protocol changes.
- Consider strict compatibility mode later.

## Enterprise Readiness

- Expand RBAC into a permission matrix.
- Extend topology comparison with long-term trend storage.
- Exercise backup and restore runbook for every release candidate.
- Improve on-prem and private registry documentation.
- Add SIEM delivery and signed compliance reports.

## Portfolio Positioning

> This project is an enterprise-ready direction portfolio, not a full commercial enterprise product. It focuses on safe RCA, evidence traceability, policy guardrails, auditability, and operational foundations.
