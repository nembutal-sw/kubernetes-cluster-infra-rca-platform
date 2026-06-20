# Roadmap

## 한국어 요약

현재 프로젝트는 단순 MVP에서 벗어나 **운영 가능한 RCA 플랫폼 형태**로 많이 가까워졌습니다.

완료된 핵심 방향은 다음입니다.

- durable RCA analysis pipeline
- rule-based detector 구조
- confidence score
- incident correlation/timeline
- evidence bundle export
- demo scenario mode
- agent health dashboard
- Slack notification
- observability metrics
- agent protocol/platform version 정보
- production fail-fast
- manual-only action workflow
- agent-side mutation 기능 제거

앞으로는 기능을 무작정 늘리기보다, 엔터프라이즈 확장 기반인 tenant/RBAC/retention/SSO/supply-chain/air-gapped 문서를 조금씩 실제 코드로 옮기는 방향이 좋습니다.

---

## English Roadmap

## Completed

### Platform foundation

- Spring Boot 3.5 platform
- Web Console and API served from one application
- Flyway migration
- H2/PostgreSQL/MariaDB-compatible persistence direction
- Durable analysis task queue
- Lease/retry/dead-letter concepts

### RCA pipeline

- Alertmanager webhook ingest
- Evidence request creation
- Agent evidence submission
- Rule-based RCA analyzer
- Signal detector structure
- Confidence scoring
- Root cause candidates with evidence paths
- Incident correlation and timeline
- Duplicate suppression within correlation window

### Node Agent

- Python node agent
- Read-only evidence collectors
- Collector package split
- Spool/retry for backend failures
- Optional realtime event path
- Agent version and protocol version reporting
- Agent-side mutation feature removed

### Safety and security

- Platform bearer auth
- Agent filter authentication
- Webhook filter authentication
- Manifest access filter
- Same-origin mutation guard
- Metrics token filter
- Production fail-fast validator
- RBAC tests
- LLM action safety
- Manual-only approval workflow

### Operations features

- Demo Scenario Mode
- Evidence Bundle Export
- Agent Health Dashboard
- Impact Scope Analyzer
- Slack notification
- Micrometer/Actuator metrics
- Optional Helm ServiceMonitor
- Platform info API `/api/v1/platform/info`

## Current Phase

```text
Phase 5: operational foundations
```

Focus areas:

- observability and SLO metrics
- agent protocol compatibility
- production configuration safety
- manual action workflow documentation
- export/audit hardening
- Helm chart production readiness

## Next Priorities

### 1. Documentation finalization

- Keep docs aligned with current code behavior.
- Clearly document deprecated agent action endpoints.
- Document manual-only action workflow.
- Document metrics and ServiceMonitor usage.
- Document soft vs strict agent compatibility roadmap.

### 2. Enterprise readiness groundwork

Planned areas:

```text
tenant/access scope model
advanced RBAC permissions
retention policy cleanup job
OIDC login
agent token rotation
mTLS-required agent mode
SIEM/audit export
backup/restore guide
SBOM and image scan CI
air-gapped install guide
```

### 3. Reliability hardening

- More cross-database integration tests
- More false-positive/false-negative detector tests
- Worker concurrency tests against PostgreSQL-specific locking behavior
- Notification queue or async delivery
- Export size and redaction regression tests

### 4. UI polish

- Better incident timeline view
- Better evidence bundle download affordance
- Agent health filtering
- Clear service impact caveat labels
- Manual action request lifecycle view

## Explicitly Out of Scope For Now

```text
automatic remediation by the agent
automatic infrastructure changes without human approval
full AIOps product scope
large-scale log search engine
full SIEM replacement
commercial billing/licensing
```

## Long-term Enterprise Direction

The project can later evolve toward:

```text
multi-tenancy
SSO/OIDC/SAML
custom RBAC permission matrix
retention and legal hold
customer-managed keys
HA worker scaling
private registry / air-gapped install
signed images and provenance
external ticketing integration
ServiceNow/Jira/PagerDuty routing
```

## Portfolio Message

> 이 프로젝트는 Kubernetes 애플리케이션 장애가 아니라, 클러스터 노드와 Linux 시스템 레벨 장애를 대상으로 한 RCA 플랫폼입니다. Agent는 read-only evidence만 수집하고, Platform은 rule-based detector와 optional LLM으로 원인 후보와 근거를 설명합니다. 위험 조치는 자동 실행하지 않고 manual approval, audit, runbook/GitOps 흐름으로 제한했습니다.
