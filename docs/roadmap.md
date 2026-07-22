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
- 여러 smoke 결과의 latency/token/cost/action safety를 집계하는 burn-in report와 SLO 변경 gate 추가
- 호출 예산, history 검증, least-sampled-first 계획과 실패 즉시 중단을 적용한 burn-in campaign runner 추가
- 8시간 구간 3개 시간 분산 gate와 시나리오별 latency/token/reliability 통계 추가

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
- RKE2 amd64 canary lifecycle, 14개 collector 요청, RCA 및 evidence bundle 검증 완료
- K3s openSUSE amd64/containerd/Flannel canary lifecycle 검증 완료
- kubeadm, EKS, AKS, GKE, OpenShift 판별 fixture 회귀 테스트 완료
- kubeadm Ubuntu 24.04 amd64/containerd/Flannel Agent real canary 검증 완료
- 관리형 Kubernetes와 OpenShift 실제 canary 검증 대기

## Next Priority

Typed Evidence 품질 평가, LLM Evidence ID/비용·지연 추적, Console 오류 복구와 Agent 상태 시나리오까지 완료했습니다.
남은 우선순위는 실제 환경이 필요한 운영 검증입니다.

1. 수동 `LLM Burn-in` workflow로 readiness 대상 Gemini 표본을 `1/20`에서 확장하고 실제 시간 구간을 `1/3`에서 확장
2. EKS/AKS/GKE/OpenShift real canary와 보안 정책 차이 기록
3. 운영 규모의 장시간 Agent 안정성 및 evidence 품질 표본 수집

Gemini staging smoke는 2026-07-21에 `gemini-3.1-flash-lite`와 provider 호출 예산
1로 성공했습니다. DiskPressure evidence 기반 report가 완료됐고 LLM root cause
candidate 3개, action suggestion 2개, supporting Evidence ID 3개가 생성됐습니다.
provider 호출은 1회였으며 지연 2.957초, input 2,281 token, output 555 token,
total 2,836 token을 기록했습니다. 당시 설정한 유료 단가 기준 예상 비용은
$0.00140275였고, LLM-origin action은 모두 `automation_allowed=false`,
`executable=false`를 유지했습니다. 초기 provider 연동 검증은 완료했으며 다음
단계는 여러 장애 유형과 시간대에서 표본을 축적해 latency/error/token/cost SLO
임계값을 조정하는 것입니다.

같은 날 NodeNotReady 표본도 provider 호출 예산 1로 통과했습니다. 지연은 3.064초,
input 1,700 token, output 468 token, total 2,168 token이었고 root cause candidate와
action suggestion은 각각 2개였습니다. LLM-origin action은 모두
`automation_allowed=false`를 유지했고, 159줄의 LLM Prometheus metric 표본을
저장했습니다. 두 표본 모두 60초 latency 기준을 충족했지만 운영 SLO 임계값을
확정하기에는 표본 수가 부족하므로 장애 유형과 시간대를 나눠 추가 burn-in합니다.

추가 burn-in에서는 inode 고갈과 NIC link flap을 각각 provider 호출 1회로 검증했습니다.
지연은 2.821초와 1.966초, token은 2,003개와 2,127개였습니다. 기존 DiskPressure,
NodeNotReady를 포함한 4개 시나리오 집계 p95는 3.064초이고 총 token은 9,134개입니다.
LLM-origin action 9개는 모두 `automation_allowed=false`, `executable=false`를 유지했습니다.
다만 운영 기준인 20개 표본과 5개 시나리오에 미달하므로 LLM p95 SLO는 60초를
유지합니다.

MemoryPressure 표본을 quota-aware campaign으로 1회 추가했습니다. 커널 OOM과 94%의
node memory 사용률을 높은 신뢰도의 원인으로 연결했고, 지연은 2.695초, token은
2,285개였습니다. 전체 집계는 5개 표본과 5개 시나리오, p95 3.064초, 총 11,419
token이며 LLM-origin action 12개 중 unsafe action은 0개입니다. 장애 유형 기준은
충족했지만 표본 수는 `5/20`, 8시간 구간은 `2/3`이므로 60초 SLO를 유지합니다.

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

추가 표본은 GitHub Actions의 수동 `LLM Burn-in` workflow로 수집하도록 정리했습니다.
workflow는 기본 dry-run, 실행당 최대 provider 1회 호출, 명시적 확인, change reference,
`llm-burn-in` Environment 승인을 사용합니다. 실제 호출은 기본 branch로 제한하고,
같은 8시간 구간에 성공 표본이 있으면 호출하지 않습니다. 완료된 run의 cumulative history를
연결하며, 표본과 sibling report는 content hash로 중복 제거한 30일 artifact로 보관합니다.
실패 run은 알려진 검증기 오탐만 현재 validator로 오프라인 재검증하고 다른 오류는 거부합니다.

`llm-burn-in` GitHub Environment에는 required reviewer를 적용했습니다. 단독 관리자
저장소이므로 self-review는 허용하지만 workflow의 명시적 확인, change reference,
기본 branch 제한을 함께 통과해야 실제 호출 단계로 진행됩니다. Dry-run은 별도의
`llm-burn-in-preview` Environment를 사용합니다. 2026-07-21 KST 04시 기준 기존 표본과
같은 8시간 구간이어서 provider 호출은 수행하지 않았고, campaign v3의 시간 구간 gate가
계획 0건과 호출 0건을 반환하는 것을 확인했습니다.

로컬 승인 표본 5개의 원본 report를 공개 저장소에 올리지 않고 workflow 계획에
연결하기 위해 `llm-burn-in-planning-baseline/v1`을 추가했습니다. baseline에는
result/report SHA-256, 시나리오, timestamp, action 안전성만 포함하며 URL, cluster/node,
evidence와 credential은 제외합니다. Campaign v4는 이 baseline을 시간 구간과 다음
시나리오 선택에만 사용하고 `readiness_eligible=false`를 강제합니다. 따라서 GitHub
artifact의 실제 표본 수가 늘기 전까지 SLO readiness는 별도로 충족되지 않습니다.

2026-07-21 UTC 00시 구간에는 self-hosted `rca-demo` runner에서 Gemini를 1회 호출했습니다.
DiskPressure 분석은 3.267초, 입력 2,279 token, 출력 573 token, 총 2,852 token이었고
LLM-origin action의 unsafe 건수는 0개였습니다. 최초 run `29796180376`은 node 이름의
`disk-pressure-node` 일부를 `sk-` credential로 오인해 실패했으며, 키 패턴 경계 조건을
수정했습니다. Run `29796658948`에서 원본 artifact를 provider 재호출 없이 다시 검증해
정상 cumulative history로 승격했고, 현재 8시간 구간의 추가 호출이 차단되는 것도 확인했습니다.
GitHub artifact 기준 readiness 표본은 `1/20`, scenario `1/5`, 시간 구간 `1/3`입니다.
Planning baseline을 합친 호출 계획은 표본 6개, scenario 5개, 시간 구간 3개지만 baseline은
계속 `readiness_eligible=false`이므로 60초 SLO를 유지합니다.

15번째 단계에서는 canonical history 누락으로 같은 구간을 중복 호출할 수 있는 운영 위험을
보완했습니다. `RCA_LLM_BURN_IN_HISTORY_RUN_ID` repository variable을 기본 history로
사용하고 입력값은 일시적인 override로만 처리합니다. Canonical history 없이 live 호출을
시작하려면 `initialize_history=true`가 필요하며, 기존 history와 동시에 사용할 수 없습니다.
Campaign summary와 Actions summary에는 실제 readiness 진행률, 현재 UTC bucket, 호출 허용
여부와 다음 호출 가능 시각을 표시합니다. Planning baseline 수치는 이 readiness 진행률에
포함하지 않습니다.

Run `29797237564`에서 `history_run_id` 입력을 비운 상태로 repository variable의 canonical
history가 자동 연결되는 것을 확인했습니다. Provider 호출은 0회였고 readiness는 표본
`1/20`, scenario `1/5`, 실제 시간 구간 `1/3`으로 표시됐습니다. 현재 UTC 00시 구간은
이미 표본이 있어 추가 호출이 차단됐으며 다음 가능 시각은 `2026-07-21T08:00:00Z`
(KST 17:00)입니다. `RCA_LLM_BURN_IN_HISTORY_RUN_ID`는 이 정상 run으로 갱신했습니다.

## Phase 16. Integrated Operational Burn-in

구현 완료:

- Agent 반복 로컬 수집 checkpoint와 atomic summary
- collector 누락/schema/degraded 비율과 p50/p95/payload threshold
- Agent Pod RSS/p95 CPU/FD/thread, process identity와 state spool/quarantine 추세
- real-cluster readiness, platform matrix, LLM readiness 통합 summary
- provider 호출 예산 0인 수동 self-hosted `Operational Burn-in` workflow
- smoke/1시간/5시간/24시간 profile과 release static gate

실환경 표본 진행 상황:

1. 완료: Agent Pod runtime 관측을 포함한 1시간 standard profile
2. 5시간 extended와 별도 24시간 production profile 표본 확보
3. EKS, AKS, GKE, OpenShift real Agent canary 수행
4. 승인된 새 시간 구간에서 canonical LLM readiness 표본 확장

2026-07-21 `rca-demo` workflow run `29803718643`에서 smoke profile을 통과했습니다. 14개 collector를 3회 수집해 성공률과 evidence quality가 100%였고 degraded collector는 없었습니다. 수집 p95는 0.630초, 최대 payload는 91,130 bytes였습니다. openSUSE K3s amd64/containerd/Flannel cluster는 `verified_real`, node 1개와 pod 4개는 모두 정상으로 확인했습니다. LLM은 provider 호출 없이 canonical readiness `1/20`, `1/5`, `1/3`을 유지했습니다. Runner 권한 경계로 Agent PID와 state directory를 관찰하지 못했으므로 장시간 자원과 spool 추세는 완료로 표시하지 않습니다.

## Phase 17. DaemonSet Agent Runtime Observation

구현 완료:

- Ready Agent Pod 단일 자동 탐색과 명시적 `namespace/name` 대상 지정
- 고정된 read-only `kubectl exec` 관측 코드
- Agent RSS, CPU, FD, thread, 프로세스 재시작 추세 검증
- spool 파일 수/크기와 quarantine 증가 검증
- 수동 host PID 입력을 제거한 Operational Burn-in CI/CD 경로
- 다중 Agent Pod 환경에서 임의 선택을 금지하는 안전 gate

운영 표본 진행 상황:

1. 완료: K3s 단일 노드 Pod runtime smoke
2. 완료: K3s 단일 노드 1시간 standard profile
3. 대기: 다중 노드별 standard 결과를 모은 fleet 기준선

## Phase 18. One-Hour Agent Standard Baseline

구현 및 검증 완료:

- self-hosted `rca-demo` runner에서 `standard` profile 60회 완료
- 수집 성공률과 evidence 품질 100%, degraded collector 0%
- Agent RSS 증가 6.24 MiB, CPU p95 0.34%, FD 증가 1, thread 증가 0
- process identity 안정, runtime 관측 오류와 quarantine 0
- 일시 spool 1건이 다음 표본에서 해소되는 것 확인

Run `29806950288`은 K3s 단일 노드 기준선으로 기록합니다. 공통 threshold 조정은 다중 노드와 추가 플랫폼 표본을 확보한 뒤 진행합니다.

## Phase 19. Multi-Node Agent Fleet Baseline

구현 완료:

- 모든 Ready Agent Pod 병렬 runtime 관측
- 최소 Agent Pod 수와 Pod별 개별 threshold gate
- RSS peak, p95 CPU, FD, thread의 fleet 편차 gate
- Pod 이름 대신 run별 salt를 적용한 HMAC-SHA-256 target ID 사용
- 1 control-plane과 2 worker로 구성된 Kind CI
- 단일 Pod Operational Burn-in과 호환되는 선택형 fleet mode

2026-07-21 CI run `29813187277`에서 Kind Agent 3/3이 fleet smoke gate를 통과했습니다. 수집 성공률과 evidence quality는 100%, RSS peak spread는 0.95 MiB였고 CPU·FD·thread spread와 runtime 오류는 0이었습니다. Artifact에는 Pod 이름과 namespace를 기록하지 않았습니다.

남은 표본은 24시간 production과 EKS/AKS/GKE/OpenShift real canary입니다.

## Phase 20. Long-Running Fleet and Managed Canary Pipeline

구현 완료:

- push CI의 3노드 smoke와 분리된 승인형 standard/extended Fleet workflow
- profile별 확인 문자열, change reference, Environment 승인, artifact 보존 정책
- EKS/AKS/GKE/OpenShift별 Environment와 runner label을 사용하는 managed canary workflow
- environment-scoped 임시 kubeconfig와 job 종료 시 private material 정리
- 예상 플랫폼과 실제 fingerprint 불일치 차단
- applied canary의 namespace/Helm/Platform test cluster cleanup gate
- evidence bundle 검증과 `managed-cluster-canary/v1` 비식별 attestation
- compatibility matrix 자동 승격 금지와 owner 수동 검토 계약
- release-readiness 정적 gate와 회귀 테스트

실표본 순서:

1. 완료: 실제 Agent Evidence 방식의 3노드 Kind standard 1시간 campaign, workflow run `29853015154`
2. 완료: 같은 commit과 지문을 사용한 extended 5시간 campaign, workflow run `29857828475`
3. EKS, AKS, GKE, OpenShift 순으로 preflight와 applied canary
4. 플랫폼별 승인 PR로 compatibility matrix profile 추가

Standard 결과는 Agent 3/3, checkpoint 60/60, Agent Evidence 180/180 통과였습니다. 수집 p95는 15.146초, 최대 payload는 17,444 bytes였고 runtime/spool/quarantine 오류는 없었습니다. 이 결과를 Extended regression gate의 승인된 기준선으로 사용했습니다.

## Phase 21. Extended Fleet Steady-State Validation

구현 완료:

- RSS 전체 증가량과 warm-up 제외 steady-state를 분리
- steady-state 시간당 선형 기울기, 범위, 연속 증가, 최근 10/30 표본 진단값 추가
- `smoke`는 관측 전용, `standard/extended/production`은 profile별 실패 gate 적용
- Fleet target별 판정과 비식별 최악값/편차 집계
- Actions Job Summary와 Operational Burn-in 요약에 최악 steady-state 수치 노출
- legacy threshold catalog에는 비활성화된 보수적 기본값을 적용해 하위 호환 유지

실행 순서:

1. 완료: Platform Evidence Request 방식의 1시간 standard 기준선 확보
2. 완료: 3노드 Kind, 300회, 약 5시간 extended workflow 실행
3. 완료: extended artifact의 900개 Agent Evidence와 RSS/CPU/FD/thread/spool 검토
4. 완료: standard와 extended compatibility, absolute, regression gate 확인

Standard run `29853015154`는 3/3 target, 60/60 checkpoint, 180/180 Agent Evidence를 통과했습니다. 수집 성공률과 evidence 품질은 100%, degraded collector와 runtime/spool/quarantine 오류는 0건입니다.

Extended workflow run `29857828475`는 300/300 checkpoint, 900/900 Agent Evidence, target 3/3을 통과했습니다. 수집 성공률과 evidence 품질은 100%, degraded collector와 runtime/spool/quarantine 오류는 0건입니다. Agent Evidence 수집 p95는 14.937초, 최악 steady-state RSS 기울기는 `0.835 MiB/hour`, 범위는 `2.578 MiB`였습니다. Standard 대비 compatibility, absolute, regression gate가 모두 통과했습니다. 동일 Kind 환경의 단일 표본이므로 threshold는 유지합니다.

다음 단계는 EKS, AKS, GKE, OpenShift managed canary를 플랫폼별 preflight부터 순차 실행하고, 별도의 승인된 Linux 세션에서 24시간 production profile을 확보하는 것입니다.

## Phase 22. EKS Document-Backed Canary Contract

실제 EKS 노드 없이 완료한 범위:

- AWS 최신 공식 문서와 Kubernetes 공식 보안 문서를 기준으로 계약 확인
- EKS Managed Node Group AL2023 amd64 fixture
- EKS Managed Node Group Bottlerocket arm64 fixture
- EKS Auto Mode의 관리형 CNI/DNS 비노출과 `safe` 우선 배포 계약
- EKS Fargate의 DaemonSet/HostNetwork 미지원 탐지 및 lifecycle 차단
- EKS compute variant가 섞인 클러스터의 비식별 fingerprint
- CI와 release-readiness에서 4개 fixture 자동 검증

이 단계는 문서 기반 contract 검증이며 real Agent E2E가 아닙니다. Compatibility matrix는 계속
`contract_fixture`를 유지하고, 실제 EKS preflight와 applied canary artifact를 검토한 뒤에만 별도
승인 PR로 변경합니다. 다음 managed platform fixture는 AKS, GKE, OpenShift 순으로 확장합니다.

## Phase 23. AKS Document-Backed Canary Contract

실제 AKS 노드 없이 완료한 범위:

- 2026-07-21 기준 Microsoft Learn 공식 문서로 계약과 지원 경계 확인
- Ubuntu amd64 system pool과 Azure CNI Overlay fixture
- Azure Linux 3 arm64 user pool과 Azure CNI powered by Cilium fixture
- Karpenter label 기반 NAP 판별과 `safe` 우선 배포 계약
- Virtual Kubelet node와 Windows node pool의 Linux Agent 배포 차단
- Agent Helm chart의 Linux node selector 기본값
- EKS와 AKS를 함께 검사하는 multi-catalog freshness 및 CI gate

AKS Automatic과 Standard NAP은 Kubernetes snapshot에서 같은 Karpenter/Cilium 신호를 보일 수 있어
`node_auto_provisioning`으로만 기록합니다. 이 단계도 real Agent E2E가 아니며
`contract_fixture_only`를 유지합니다. 다음 문서 기반 계약 대상은 GKE이며, managed platform을
`verified_real`로 승격하는 작업은 별도 real canary와 수동 승인 PR 이후에만 수행합니다.

## Phase 24. Agent Identity Lifecycle Hardening

구현 및 검증 완료:

- Agent protocol v2의 Authorization Bearer 인증
- bootstrap token의 등록 전용 사용과 기본 30분 TTL
- 등록 성공 직후 Agent 프로세스 환경·메모리에서 bootstrap token 제거
- heartbeat, evidence, realtime 요청의 node-scoped token 단독 인증
- bootstrap token 회전·폐기와 node token self-rotation·관리자 폐기
- token 거부 시 자동 bootstrap 재사용을 금지하고 명시적 재등록 요구
- protocol v1 body credential rolling-upgrade 호환과 header/body 충돌 차단
- Flyway V21 기반 PostgreSQL·MariaDB 공통 token lifecycle schema
- Python Agent, Spring HTTP security boundary, repository 회귀 테스트

잔여 과제는 autoscaling node 등록을 위한 ServiceAccount TokenReview 또는 node-bound mTLS
enrollment identity입니다. 현재 static bootstrap Secret은 TTL 만료 후 수동 회전이 필요합니다.

## Phase 25. Notification Transactional Outbox

구현 및 검증 완료:

- Incident·Report·Job과 notification event의 동일 DB transaction 저장
- Flyway V22 기반 PostgreSQL·MariaDB 공통 outbox schema
- 다중 worker conditional claim, lease 만료 복구와 최대 시도 횟수 관리
- 네트워크 오류, `408`, `425`, `429`, `5xx` 지수 backoff 재시도
- 영구 `4xx`와 재시도 소진 event의 `dead_letter` 격리
- report/channel 단위 idempotency key와 일반 webhook `Idempotency-Key` 헤더
- payload 비노출 outbox 조회, 역할 기반 dead-letter 수동 재큐잉과 audit 기록
- queue depth와 dead-letter gauge, Settings 상태 표시
- cluster 삭제와 report retention에 연결된 outbox 정리
- 원자적 rollback, 동시 claim, lease 회수, 재시도·영구 실패 회귀 테스트

다음 코드 개선은 RCA 분석 pipeline 단계 분리와 Frontend orchestration 축소입니다. 이후 실제
비식별 장애 corpus 확장과 Maven/Frontend build lifecycle 분리를 순차 진행합니다.

## Positioning

이 프로젝트는 애플리케이션 로그 분석 도구가 아니라 Kubernetes node와 Linux system layer 장애를 근거 기반으로 수집, 분석, 설명하는 RCA 플랫폼이다. 자동 조치는 기본적으로 금지하고, 정책 엔진과 감사 로그를 통해 사람이 승인하고 추적할 수 있는 운영 흐름을 우선한다.
