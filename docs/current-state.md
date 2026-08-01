# Current State

이 문서는 저장소의 현재 구현 기준을 한곳에 정리한 문서입니다.

- 기준일: `2026-07-28`
- 기준 단계: `Phase 40`
- 기준 브랜치: `main`

세부 설계는 각 주제별 문서를 따르되, 기능 상태나 버전 설명이 서로 다르면 이 문서와 실제 코드·설정을
우선합니다. 과거 계획과 단계별 완료 기록은 역사 문서로 보존합니다.

## Product Boundary

Cluster RCA Console은 애플리케이션 APM이 아니라 Kubernetes 노드와 Linux 시스템 계층의 장애 원인을
수집하고 분석하는 플랫폼입니다. 주요 대상은 Node condition, kubelet·container runtime·CNI·DNS,
systemd·kernel, 디스크·inode·메모리·PID, NIC·conntrack, API Server·etcd 지연입니다.

분석과 설명은 자동화하지만 운영 환경 변경은 자동 실행하지 않습니다.

- Rule-based 분석이 원인 후보와 근거의 기준입니다.
- LLM은 선택적 보조 설명만 추가합니다.
- LLM 조치는 항상 `automation_allowed=false`, `executable=false`입니다.
- 승인 workflow는 승인·거절·감사 기록, 수동 처리 완료, runbook, GitOps PR 추적만 제공합니다.
- Platform과 Agent에는 호스트나 Kubernetes 리소스를 변경하는 실행 경로가 없습니다.

## Runtime Stack

| 영역 | 현재 기준 |
| --- | --- |
| Platform | Spring Boot `3.5.15`, Java `21`, Spring AI `1.1.8` |
| Web Console | React `19.2.7`, TypeScript, Vite `8.0.16`, Bootstrap `5.3.8` |
| Node Agent | Python `3.10+`, Agent protocol `v2` |
| Database | PostgreSQL `16` 또는 MariaDB `11.x`, 로컬 개발용 H2 |
| Schema | Flyway V26, 총 26개 migration |
| Packaging | 단일 Spring Boot 애플리케이션에 React 정적 자산 포함 |

Flyway migration은 SQL 25개와 Java migration `V14__widen_audit_outcome` 1개로 구성됩니다.
JSP와 별도 Python Backend는 사용하지 않습니다.

## Components And Flow

```text
Alertmanager / Platform Scheduler / Demo Scenario
  -> Evidence Request
  -> Node Agent read-only collection
  -> Durable Analysis Task
  -> EvidencePreprocessingStage
  -> RuleAnalysisStage
  -> optional LlmEnrichmentStage
  -> ReportAssemblyStage + Policy Engine
  -> Incident / Report / Job / Notification Outbox / Task completion
     in one database transaction
  -> Timeline / Audit / Manual Action / GitOps workflow
```

Analysis와 notification worker는 fenced lease와 heartbeat renewal을 사용합니다. Evidence request는
request ID와 저장 경계에서 멱등 처리하며, Agent는 전송 실패 payload를 제한된 로컬 spool에 보관합니다.

## Agent Contract

Collector registry에는 다음 14개 collector가 있습니다.

```text
node, kubernetes, systemd, kernel, disk, inode, memory, process,
network, conntrack, runtime, kubelet, cni, dns
```

| Mode | 기본 collector와 권한 |
| --- | --- |
| `safe` | `node`, `kubernetes`, `dns`; 비-root, hostPath 없음 |
| `node-diagnostics` | 14개 collector; 필요한 host 자원을 읽기 전용 mount |
| `ebpf` | `node-diagnostics`와 같은 collector 및 선택적 실시간 eBPF tracing |

DaemonSet의 systemd·journal evidence는 기본적으로 host 파일을 읽습니다. 컨테이너에서 host DBus를
직접 제어하지 않으며, eBPF event는 collector registry와 분리된 실시간 evidence 경로로 전송합니다.

Agent 등록 방식은 두 가지입니다.

- `bootstrap-token`: 짧은 TTL의 cluster credential을 최초 등록에만 사용
- `kubernetes-token-review`: 전용 audience의 projected ServiceAccount token을 Platform reviewer가 검증

등록 후에는 node-scoped Bearer token만 사용합니다. Node token은 기본 30일마다 원자적으로
회전하며 cluster/node/profile version에 결합됩니다. TokenReview enrollment audience는 Kubernetes API
audience와 겹칠 수 없습니다. 외부 cluster reviewer credential은 raw token을 저장하지 않고 mount
경로와 version만 관리합니다. 새 credential 검증 후 bounded grace로 교체하며, `401/403` 또는 파일
읽기 실패에서만 이전 credential을 사용합니다.

## Platform Contract

- 사용자 인증: session + RBAC
- Agent 인증: 등록 identity + node-scoped Bearer token, 선택적 mTLS
- Webhook 인증: 전용 webhook credential
- Manifest 인증: 짧은 TTL의 1회용 token
- Export 권한: `ADMIN`, `OPERATOR`
- 운영 관찰: audit, request ID, Prometheus metric, health/readiness
- DB 호환: PostgreSQL·MariaDB fresh schema 및 Alembic baseline migration CI
- LLM provider: OpenAI, Anthropic, Google GenAI, Ollama, OpenAI-compatible endpoint

초기 계정은 환경 변수나 외부 Secret으로 명시적으로 생성합니다. `admin/admin` 기본 계정은 자동으로
생성하지 않습니다.

## Validation Baseline

저장소 CI는 다음 계약을 검증합니다.

- Python Agent compile과 pytest
- React unit test, TypeScript, Vite build, Playwright E2E
- Spring Boot `mvn verify`
- PostgreSQL·MariaDB compatibility
- Helm lint와 주요 설정 조합 렌더링
- API 인증, Agent enrollment, supply-chain, build lifecycle 정적 gate
- 3-node Kind bootstrap Agent와 Platform TokenReview 전체 등록 smoke
- unsafe audit 차단, one-shot migration, 최종 rollout, DB NetworkPolicy
- PostgreSQL·MariaDB packaged CLI migration

실제 Agent canary는 RKE2 amd64/arm64, K3s amd64, kubeadm Ubuntu 24.04 amd64에서 완료했습니다.
EKS, AKS, GKE, OpenShift는 공식 문서 기반 contract fixture까지만 검증했으며 실제 managed canary가
남아 있습니다. 1시간 Standard와 5시간 Extended Fleet 검증은 완료했고 24시간 Production Fleet는
남아 있습니다.

2026-08-02 현재 코드로 Kind v0.31.0과 Kubernetes v1.35.0의 3-node smoke를 다시 실행했습니다.
bootstrap Agent와 TokenReview Agent가 각각 3/3 등록됐고, audit-only upgrade 차단, one-shot audience
migration, reviewer credential, Evidence 수집, Incident와 RCA Report 생성이 모두 통과했습니다. smoke
수집 성공률과 Evidence 품질은 100%, degraded collector는 0%, 수집 p95는 14.955초였으며
runtime/spool/quarantine 오류는 없었습니다. 이 결과는 격리된 Kind 검증이며 managed Kubernetes
실환경 결과를 대신하지 않습니다.

RCA 품질 수치는 저장소의 golden, production-like, 내부 holdout corpus에 대한 회귀 결과입니다.
실운영 정확도를 의미하지 않으며 managed canary와 비식별 실제 장애 표본으로 별도 검증해야 합니다.

## Current Priorities

1. opaque token key 사용 현황과 이전 key 제거 준비 상태 가시화
2. Helm과 Web Console Agent manifest의 구조적 parity 강화
3. 관리자 승인 기반 Agent identity rebind
4. 24시간 Production Fleet와 EKS/AKS/GKE/OpenShift 실제 canary
5. 실제 장애 corpus와 LLM burn-in 표본 확대

진행 순서와 완료 기록은 [Roadmap](roadmap.md)을 참고합니다.

## Sources Of Truth

| 계약 | 코드 기준 |
| --- | --- |
| Platform·Spring AI 버전 | `web-console/pom.xml` |
| Frontend 버전 | `web-console/frontend/package.json` |
| Runtime 기본값 | `web-console/src/main/resources/application.yml` |
| DB schema | `web-console/src/main/resources/db/migration`, `web-console/src/main/java/db/migration` |
| Agent collector·mode | `node_agent/collectors/registry.py`, `node_agent/collectors/modes.py` |
| Agent protocol | `node_agent/__init__.py` |
| Helm 설정 | `charts/cluster-infra-rca-platform`, `charts/cluster-infra-rca-agent` |
| Platform compatibility | `config/platform-compatibility.json` |
| CI 계약 | `.github/workflows`, `scripts/release-readiness-check.py` |
