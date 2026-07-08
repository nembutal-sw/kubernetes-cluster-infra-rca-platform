# Roadmap

이 문서는 현재 구현 상태와 다음 고도화 대상을 정리한다. 목표는 Kubernetes 애플리케이션 장애가 아니라, 노드와 Linux 시스템 레벨 장애를 근거 기반으로 수집하고 분석하는 Cluster RCA Console이다.

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

## Recently Completed

### Catalog Externalization

- classpath 기본 catalog와 외부 JSON override path 추가
- collector selection을 catalog 기반으로 전환
- action policy, action plan, recommendation trigger를 catalog 기반으로 전환
- detector enablement를 rule catalog 기반으로 전환
- catalog schema validation과 unsafe executable plan 차단 테스트 추가
- `/api/v1/catalog` 상세 조회 API와 Settings read-only catalog 화면 추가
- `/api/v1/catalog/preview` override validation/diff API 추가
- Settings 화면에서 override JSON preview와 diff 확인 지원
- preview 성공/거부 audit event 기록
- catalog override draft 저장, 승인/거절/폐기 API 추가
- 승인된 draft의 GitOps PR/runbook handoff 응답 추가

### Cluster Threshold Override Persistence

- `cluster_threshold_overrides` DB 테이블 추가
- cluster별 detector threshold override 저장/조회/초기화 API 추가
- RCA 분석 시 `EvidenceBundle.clusterId` 기준 effective threshold 사용
- 잘못된 key, percent 범위, warning/critical 역전 validation 추가
- Platform Info와 Cluster Detail UI에 threshold definition/effective value 노출
- Cluster Detail UI에서 override 저장/초기화 지원
- PostgreSQL/MariaDB 호환 테스트와 HTTP E2E 반영

### Supply Chain Security

- repository filesystem Trivy scan 결과를 SARIF와 artifact로 보존
- Syft repository SBOM을 workflow artifact로 보존
- Grype SBOM vulnerability scan 결과를 SARIF와 artifact로 보존
- platform/agent image build 후 image SBOM 생성
- platform/agent image Trivy scan 추가
- release image SBOM과 image scan report를 GitHub Release asset으로 업로드
- release readiness static gate에 supply-chain workflow 검증 추가

## Active Backlog

### Editable Catalog Override Workflow

목표:

- 승인된 catalog override draft를 실제 GitOps 시스템과 연결
- 변경 티켓, PR URL, 배포 결과, 롤백 결과를 draft에 추적
- 외부 JSON 파일 배포 runbook을 운영 환경별로 구체화

완료 기준:

- GitHub/GitLab/Gitea 중 하나 이상 PR 생성 연동
- PR URL과 deployment outcome 저장
- rollback handoff와 verification checklist 제공

### Agent And Webhook Auth Regression

대상:

- `/api/agents/**`
- `/api/webhooks/**`
- `/api/clusters/{cluster_id}/agent-manifest`
- metrics/export 계열 인증 경계

완료 기준:

- token 없음, 잘못된 token, bearer/header token, one-time manifest token 재사용 검증
- 인증 실패가 audit event로 남고 민감 token 값은 저장하지 않음
- 새 endpoint 추가 시 인증 누락을 CI에서 빠르게 감지

### Real Cluster Validation

대상:

- kubeadm, k3s/RKE2, EKS/AKS/GKE, OpenShift 계열
- 실제 DaemonSet Agent canary

완료 기준:

- canary node에서 Agent register, heartbeat, evidence response 성공
- disk, inode, memory, pid, network, conntrack, runtime, kubelet, systemd, kernel, cni, dns collector 결과 확인
- 플랫폼별 차이를 compatibility matrix에 기록

## Next Priority

1. catalog override PR 생성 연동 또는 변경 티켓 추적 모델 검토
2. agent/webhook 인증 regression test 강화
3. 실제 Kubernetes canary 검증 반복
4. 플랫폼별 collector compatibility matrix 보강
5. 운영 배포 runbook과 rollback 문서 정리

## Positioning

이 프로젝트는 애플리케이션 로그 분석 도구가 아니라 Kubernetes node와 Linux system layer 장애를 근거 기반으로 수집, 분석, 설명하는 RCA 플랫폼이다. 자동 조치는 기본적으로 금지하고, 정책 엔진과 감사 로그를 통해 사람이 승인하고 추적할 수 있는 운영 흐름을 우선한다.
