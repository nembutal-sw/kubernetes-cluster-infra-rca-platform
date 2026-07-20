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

### Frontend Workflow Decomposition

- `App.tsx`를 URL routing, 인증 상태, page 조합 중심으로 축소
- Cluster, Action, Incident, Export, Audit, Settings workflow를 domain hook으로 분리
- 공통 API, 알림, 번역 함수 타입 경계를 명시
- Cluster 생성과 승인 요청 workflow 회귀 테스트 추가

### Database Compatibility CI Gate

- PostgreSQL/MariaDB fresh schema와 기존 schema baseline 테스트 유지
- Surefire 결과에서 DB 호환 테스트 4개의 실제 실행 여부 확인
- Docker 미탐지로 Testcontainers가 skip되면 CI를 실패 처리
- release readiness에 DB 호환 실행 계약 추가

### Console Data Reliability

- 공통 API 오류 계약과 `X-Request-ID` trace 연결
- Frontend `ApiError`, API별 `LoadState<T>` 적용
- 실패 시 마지막 정상 데이터를 유지하고 stale 상태 표시
- 부분 장애 배너, 마지막 갱신 시각, 수동/30초 자동 갱신
- 비활성 탭 polling 중단과 세션 만료 공통 처리
- `/api/v1/agent-health` 집계 API로 Dashboard N+1 제거
- Vitest/React Testing Library 회귀 테스트와 모바일 route smoke 검증

### URL Routing And Shareable Detail Views

- React Router 기반 화면/선택 상태 URL 동기화
- Cluster, Report, Incident 상세 URL 직접 진입과 새로 고침 지원
- Sidebar 이동, 상세 열기, 브라우저 뒤로 가기 동작 통일
- `/`, `/console`, 알 수 없는 경로의 `/overview` 정규화
- 권한 없는 경로 redirect와 존재하지 않는 상세 리소스 안내
- 모바일/데스크톱 및 영문/한글 route smoke 검증

### Console Workflow E2E

- Playwright test runner와 전용 H2/demo Spring Boot 서버 구성
- 보호된 상세 URL 로그인 복원과 세션 만료 검증
- Cluster 생성, 설치 명령 발급, 상세 새로 고침, 삭제 검증
- Demo Evidence, RCA report, 승인, 거절, 수동 완료 workflow 검증
- Viewer 변경/export 제한과 모바일 keyboard confirmation 검증
- 부분 API 503 오류 주입, 마지막 정상 데이터 유지, 오류 code/trace ID 표시, 재시도 복구 검증
- Agent `healthy`, `stale`, `collector_degraded`, `version_mismatch`, `unauthorized`, `offline` 상태와 모바일 필터 UI 검증
- CI 독립 job 및 실패 trace/screenshot/video/HTML artifact 보존

### LLM Evidence And SLO Controls

- LLM이 `evidence_catalog`의 안정적인 Evidence ID만 참조하도록 응답 계약 강제
- Provider 응답의 token usage, 요청 지연, 설정 단가 기반 예상 비용 기록
- RCA 상세 화면에 LLM 지연, token, 비용과 supporting Evidence ID 표시
- Helm chart에 선택형 LLM SLO recording rule과 latency/error/usage/circuit/cost 경보 추가
- 실제 Provider 연결과 usage/cost 한도를 검증하는 staging smoke 보강

### Agent Fleet Operations UX

- Clusters 화면에 전체 Agent 연결 상태, 하트비트 경과, 버전/프로토콜, Collector 수, 위험 사유 표시
- 상태별 segmented filter와 노드/클러스터/버전/사유 검색 지원
- 데스크톱 1440px과 모바일 390px에서 수평 overflow 없는 레이아웃 검증
- 테스트 과정에서 발견된 Cluster 생성 직후 설치 명령 초기화 race condition 수정

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

### Operations Data Pagination

- Report, Incident, Analysis Task versioned cursor API 추가
- timestamp와 ID 기반 keyset ordering, literal 검색, cluster/status 필터 적용
- PostgreSQL/MariaDB 공통 복합 인덱스 추가
- Reports, Incidents, Pipeline UI에 검색과 이전/다음 탐색 연결
- 기존 배열 API는 호환성을 위해 유지

## Active Backlog

최신 코드 리뷰를 현재 `main`과 대조한 세부 실행 순서와 완료 기준은
`code-review-action-plan-2026-07-10.md`를 기준으로 한다.

### Editable Catalog Override Workflow

목표:

- 승인된 catalog override draft를 실제 GitOps 시스템과 연결
- 변경 티켓, PR URL, 배포 결과, 롤백 결과를 draft에 추적
- 외부 JSON 파일 배포 runbook을 운영 환경별로 구체화

완료 기준:

- GitHub/GitLab/Gitea 중 하나 이상 PR 생성 연동
- PR URL과 deployment outcome 저장
- rollback handoff와 verification checklist 제공

현재 상태:

- GitHub/Gitea draft PR과 GitLab draft MR 생성, provider별 webhook 상태 동기화 완료
- deployment, verification, rollback 결과 저장 및 Settings UI 표시 완료
- GitHub, GitLab, Gitea provider 지원 완료

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

현재 상태:

- 플랫폼/runtime/CNI/architecture fingerprint와 외부 compatibility matrix 구현 완료
- RKE2 ARM64 canary lifecycle과 read-only collector 검증 완료, 2026-07-21 현재 `main` 재검증 통과
- K3s openSUSE amd64/containerd/Flannel canary lifecycle 검증 완료
- kubeadm, EKS, AKS, GKE, OpenShift 판별 fixture 회귀 테스트 완료
- RKE2 amd64, kubeadm, 관리형 Kubernetes, OpenShift 실제 canary 검증 대기

## Next Priority

Typed Evidence 품질 평가, LLM Evidence ID/비용·지연 추적, Console 오류 복구와 Agent 상태 시나리오까지 완료했습니다.
남은 우선순위는 실제 환경이 필요한 운영 검증입니다.

1. RKE2 amd64와 kubeadm Agent real canary 검증
2. Gemini staging smoke 반복 표본 수집과 SLO 임계값 burn-in
3. EKS/AKS/GKE/OpenShift real canary와 보안 정책 차이 기록

Gemini staging smoke는 2026-07-21에 `gemini-3.1-flash-lite`와 provider 호출 예산
1로 성공했습니다. DiskPressure evidence 기반 report가 완료됐고 LLM root cause
candidate 3개, action suggestion 2개, supporting Evidence ID 3개가 생성됐습니다.
provider 호출은 1회였으며 지연 2.957초, input 2,281 token, output 555 token,
total 2,836 token을 기록했습니다. 당시 설정한 유료 단가 기준 예상 비용은
$0.00140275였고, LLM-origin action은 모두 `automation_allowed=false`,
`executable=false`를 유지했습니다. 초기 provider 연동 검증은 완료했으며 다음
단계는 여러 장애 유형과 시간대에서 표본을 축적해 latency/error/token/cost SLO
임계값을 조정하는 것입니다.

LLM `PrometheusRule`은 Helm 렌더 결과를 대상으로 한 `promtool` 회귀 테스트에서
정상/latency/error/usage/circuit/cost 시나리오의 firing 여부까지 검증합니다.
2026-07-21에는 실제 Prometheus `3.12.0`과 Alertmanager `0.33.1`을 사용해
`ClusterRcaLlmCircuitBreakerOpen`의 firing/resolved webhook 전달, Bearer credentials
file 인증, payload label을 검증했습니다. Helm chart에는 선택형
`AlertmanagerConfig`와 필수 `clusterId` 주입을 추가했습니다. 2026-07-21 Kind CI에서는
고정 버전 `kube-prometheus-stack`을 설치하고 Operator의 selector/reconciliation,
runbook URL, 인증된 firing/resolved webhook 전달을 모두 확인했습니다. 운영 또는
관리형 클러스터에서는 해당 환경의 selector와 보안 정책을 반영한 canary를 별도로
실행해야 합니다.

## Positioning

이 프로젝트는 애플리케이션 로그 분석 도구가 아니라 Kubernetes node와 Linux system layer 장애를 근거 기반으로 수집, 분석, 설명하는 RCA 플랫폼이다. 자동 조치는 기본적으로 금지하고, 정책 엔진과 감사 로그를 통해 사람이 승인하고 추적할 수 있는 운영 흐름을 우선한다.
