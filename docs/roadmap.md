# Roadmap

이 문서는 현재 구현 상태와 다음 고도화 대상을 정리한다. 목표는 데모 수준을 넘어서 실제 운영 환경에서 쓸 수 있는 Cluster RCA Console로 안정화하는 것이다.

## Completed Phases

### Phase 1: Platform Foundation

- Spring Boot 기반 Web Console 통합
- JDBC/Flyway 기반 PostgreSQL/MariaDB 호환 저장소
- 클러스터 등록, 삭제, Agent 설치 명령 제공
- 기본 관리자 로그인, 세션 인증, RBAC
- Maven, pytest, frontend build, Helm 검증 기반 CI

### Phase 2: RCA Pipeline

- Alertmanager webhook ingest
- Agent evidence request/response lifecycle
- durable analysis task queue
- incident correlation
- rule-based RCA report generation
- LLM provider abstraction과 fallback 구조

### Phase 3: Security And Structure

- Agent, webhook, manifest, metrics 인증 필터
- production profile fail-fast validation
- sensitive data redaction
- manual-only action lifecycle
- audit log, export 권한 제한, RBAC matrix
- controller/service/repository 분리

### Phase 4: Operations UX

- Cluster RCA Console 대시보드
- incident timeline, causal edge, impact scope
- evidence bundle export
- agent health, topology, audit 화면
- LLM 설정 상태 표시
- 반응형 레이아웃과 page/component 분리

### Phase 5: Operational Validation

- local E2E smoke 검증
- demo scenario catalog
- live API scenario validation runner
- DaemonSet operational checker
- PostgreSQL/MariaDB backup/restore 검증 문서
- Kubernetes/Helm chart 정리

## Active Backlog

### Agent And Webhook Auth Regression

대상:

- `/api/agents/**`
- `/api/webhooks/**`
- `/api/clusters/{cluster_id}/agent-manifest`
- metrics/export 계열 인증 경계

현재 진행:

- Agent/Webhook/Manifest 인증 실패와 성공 경로를 별도 회귀 테스트로 고정
- webhook/manifest 인증 실패 audit에 client IP, user-agent, query redaction 컨텍스트 기록

완료 기준:

- token 없음, 잘못된 token, bearer/header token, one-time manifest token 재사용을 모두 검증
- 인증 실패도 audit event로 남고 민감 token 값은 저장하지 않음
- 새 endpoint 추가 시 인증 누락을 CI에서 빠르게 감지

### Catalog Externalization

대상:

- collector catalog
- action catalog
- rule catalog

현재 진행:

- classpath 기본 catalog와 외부 JSON override path 추가
- collector selection을 catalog 기반으로 전환
- action policy, action plan, recommendation trigger를 catalog 기반으로 전환
- detector enablement를 rule catalog 기반으로 전환
- catalog schema validation과 unsafe executable plan 차단 테스트 추가

목표:

- 코드에 고정된 collector/action/rule 정의를 YAML 또는 JSON catalog로 분리
- 기본 catalog는 classpath에 두고, 운영 override는 외부 config path로 주입
- catalog version, source, checksum을 platform info 또는 별도 endpoint에서 확인

완료 기준:

- catalog schema validation 실패 시 boot 단계에서 명확히 실패
- rule/action key 변경이 report, policy, audit과 호환되는지 regression test 추가
- 잘못된 action catalog가 자동 실행 경로를 만들지 않도록 정책 검증 추가
- 다음 단계: UI 또는 DB 기반 cluster별 catalog/threshold override 관리

### Cluster Threshold Override Persistence

대상:

- cluster별 detector threshold override 저장 모델

목표:

- global threshold는 기본값으로 유지
- cluster별 override를 DB에 저장하고 RCA analyzer가 cluster context에 맞는 기준을 사용
- report에 적용된 threshold 값과 source를 표시

완료 기준:

- threshold override CRUD API와 RBAC 적용
- PostgreSQL/MariaDB migration과 repository test 추가
- override가 없으면 global default로 fallback
- 잘못된 threshold 값은 저장 전에 validation

### Supply Chain Security

대상:

- SBOM 생성
- image/dependency vulnerability scan
- secret scan

목표:

- CI에서 Gitleaks, Syft, Grype 또는 Trivy를 release gate로 실행
- build artifact와 container image에 대해 SBOM을 남김
- high/critical 취약점 예외 처리 기준을 문서화

완료 기준:

- Gitleaks secret scan workflow
- Syft SBOM artifact 생성
- Grype/Trivy vulnerability scan 결과 보존
- release readiness check와 연결

### Real Cluster Validation

대상:

- kubeadm, k3s/RKE2, EKS/AKS/GKE, OpenShift 계열
- 실제 DaemonSet Agent canary

목표:

- runtime socket 자동 감지와 override 검증
- hostPath, RBAC, ServiceAccount 최소 권한 검증
- evidence payload 크기, UTF-8, redaction 검증

완료 기준:

- canary node에서 Agent register, heartbeat, evidence response 성공
- disk, inode, memory, pid, network, conntrack, runtime, kubelet, systemd, kernel, cni, dns collector 결과 확인
- 플랫폼별 차이를 compatibility matrix에 기록

## Next Priority

1. cluster별 threshold override 저장 모델 구현
2. supply-chain scan workflow와 SBOM artifact 추가
3. catalog override 운영 UI 또는 DB 관리 모델 검토
4. 실제 Kubernetes canary 검증 반복
5. 플랫폼별 collector compatibility matrix 보강

## Positioning

이 프로젝트는 애플리케이션 로그 분석 도구가 아니라 Kubernetes node와 Linux system layer 장애를 근거 기반으로 수집, 분석, 설명하는 RCA 플랫폼이다. 자동 조치는 기본적으로 금지하고, 정책 엔진과 감사 로그를 통해 사람이 승인하고 추적할 수 있는 운영 흐름을 우선한다.
