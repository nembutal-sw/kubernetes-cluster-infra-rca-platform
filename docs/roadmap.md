# Roadmap

현재 프로젝트는 단순 MVP를 넘어 운영 가능한 RCA 플랫폼의 기반을 갖춘 상태입니다. 다음 단계는 기능을 무리하게 늘리는 것보다, 실제 클러스터 검증과 운영 품질을 반복적으로 높이는 방향입니다.

## Completed Phases

### Phase 1: Platform Foundation

- Spring Boot platform 통합
- JDBC/Flyway persistence
- cluster registry
- user/session authentication
- role-based authorization
- 기본 CI validation pipeline

### Phase 2: RCA Pipeline

- Alertmanager webhook ingest
- evidence request lifecycle
- durable analysis task queue
- worker retry/dead-letter
- incident correlation
- RCA report generation

### Phase 3: Security And Structure

- Agent, webhook, manifest, metrics 인증 필터
- production fail-fast validation
- repository facade split
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
- topology observation persistence/API
- stale Node/Pod/Service expiry from authoritative snapshots
- responsive Service-to-Node topology graph
- confirmed Service impact scope
- strict cross-node correlation for cluster-global signal families
- multi-node incident visibility

### Phase 7: Security And Operational Hardening

- explicit initial administrator provisioning
- mandatory webhook authentication
- short-lived one-time Agent manifest download token
- request body, log collection, Agent payload limits
- `safe`, `node-diagnostics`, `ebpf` Agent permission modes
- idempotent evidence response handling
- evidence request pagination/query indexes
- Kubernetes API response cache
- PostgreSQL/MariaDB backup and restore validation
- Agent bootstrap token rotation
- optional Agent mTLS enforcement
- topology history comparison
- role-restricted audit JSON/CSV export

### Phase 8: Operational Validation

- expanded demo scenario catalog
- live API scenario validation runner
- read-only DaemonSet operational checker
- UTF-8 Korean documentation cleanup for key docs
- regression test for demo scenario report quality and unsafe action guardrails

## Near-Term Priorities

### Real Cluster Validation

- Agent DaemonSet을 실제 Kubernetes 클러스터에 canary로 배포
- 배포판별 collector 차이 확인: kubeadm, k3s/RKE2, EKS/AKS/GKE, OpenShift 계열
- runtime socket 자동 탐지와 override 검증
- hostPath와 ServiceAccount 권한 최소화 검증
- evidence payload 크기와 redaction 품질 확인

### Scenario Quality

- DiskPressure, inode, MemoryPressure, PIDPressure, NetworkUnavailable 계열 반복 검증
- kubelet/container runtime/systemd/kernel/network/DNS/CNI/conntrack 신호 오탐 반례 추가
- report confidence와 root cause ranking 기준 보강
- timeline edge가 실제 장애 전파 흐름을 설명하는지 검증

### LLM Integration

- provider별 timeout, retry, fallback 검증
- OpenAI, Gemini, Claude, Ollama/local model 설정 문서화
- LLM 결과 schema validation 강화
- LLM 장애 시 Rule-based report 단독 동작 확인

### Enterprise Readiness

- RBAC permission matrix 확장
- SIEM/webhook delivery 옵션 검토
- signed report 또는 감사용 export bundle 검토
- private registry/on-prem 배포 문서 보강
- backup/restore runbook을 release candidate마다 재검증

## Positioning

이 프로젝트는 완제품 상용 솔루션이라기보다, 엔터프라이즈 RCA 플랫폼 방향성을 보여주는 구현체입니다. 핵심은 안전한 evidence 수집, 근거 기반 RCA, policy guardrail, auditability, 운영 확장 기반입니다.
