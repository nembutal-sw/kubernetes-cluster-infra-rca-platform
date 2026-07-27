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

- `App.tsx`를 인증 상태, 공통 hook, shell과 전역 dialog 조합 중심으로 축소
- Cluster, Action, Incident, Export, Audit, Settings workflow를 domain hook으로 분리
- URL 정규화·권한 redirect를 `useConsoleNavigation`으로 분리
- 상세 resource route 동기화를 `useRouteResourceSync`로 분리
- 화면 선택과 page props 연결을 `ConsoleViewHost`로 분리
- 공통 API, 알림, 번역 함수 타입 경계를 명시
- Cluster 생성, 승인 요청, 상세 route workflow 회귀 테스트 추가

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

현재 기준과 세부 상태는 [Current State](current-state.md)를 우선합니다.

### P1: Credential Lifecycle

- opaque token key별 credential 사용 현황과 old-key 제거 readiness 제공
- 외부 Kubernetes reviewer credential rotation, 만료 감시, fail-closed 검증
- 관리자 승인과 audit을 포함한 Agent workload identity rebind
- fleet migration 완료 후 protocol v1 body credential 제거

### P1: Agent Installation Parity

- Web Console manifest와 Agent Helm chart의 Kubernetes object 구조 비교 확대
- enrollment mode, audience, labels, image digest, security context parity를 CI에서 강제
- upgrade preflight와 canary 결과를 Console에서 확인할 수 있는 운영 상태 제공

### P1: Real Environment Validation

- 24시간 Production Fleet burn-in과 resource/spool 추세 검증
- EKS, AKS, GKE, OpenShift 실제 canary
- 관리형 플랫폼별 node type, runtime, CNI, 제한 권한 차이 기록
- 비식별 실제 장애 표본과 복합·누락·시간 역전 corpus 확대

### P2: LLM Operational Readiness

- 장애 유형별 canonical 표본을 20개 이상으로 확대
- 세 개 이상의 독립 시간 구간에서 latency/error/token/cost 관찰
- provider quota와 circuit breaker fault-injection
- 모델 lifecycle 변화에 따른 설정 검증과 fallback 유지

## Next Priority

1. 문서 기준선과 코드 계약을 CI로 고정
2. opaque token key inventory와 제거 readiness 구현
3. Helm·Web Console Agent manifest 구조 parity 확대
4. 외부 reviewer credential lifecycle과 승인 기반 identity rebind
5. Production Fleet 및 managed Kubernetes 실제 canary

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

## Phase 26. Analysis And Console Orchestration Decomposition

구현 및 검증 완료:

- `EvidencePreprocessingStage`, `RuleAnalysisStage`, `LlmEnrichmentStage`, `ReportAssemblyStage` 분리
- 단계 사이의 불변 `RcaAnalysisPipelineContext` record 계약
- quality gate 계산을 `RcaQualityGateEvaluator`로 단일화
- `RuleBasedRcaAnalyzer`를 네 단계 실행 facade로 축소
- LLM 후보의 낮은 신뢰도와 Policy Engine 재분류 계약 유지
- `App.tsx`에서 navigation, resource route sync, active view rendering 분리
- Backend 단계 순서와 Frontend 상세 URL 상태 회귀 테스트 추가

## Phase 27. Production-Like Evidence Corpus

구현 및 검증 완료:

- 저장소 Agent E2E 산출물의 collector 구조를 기반으로 한 비식별 운영 형태 corpus
- 정상 음성, threshold 경계, 단일·복합 장애, degraded evidence, 시간 역전 시나리오
- Ubuntu/containerd, RHEL/CRI-O, K3s embedded containerd/file collector 변형
- 13개 시나리오와 31개 기대 신호의 독립 Precision/Recall 및 양성·음성 gate
- `status=degraded` collector가 Evidence 품질 평가에서 누락되던 상태 판정 수정
- CI artifact와 release-readiness 정적 gate 연결
- 합성 golden, production-like reproduction, 실운영 정확도의 문서상 구분

## Phase 28. Maven And Frontend Lifecycle Separation

구현 및 검증 완료:

- 기본 `mvn test/verify`에서 Frontend Maven plugin과 npm 접근 제거
- 명시적 `frontend` profile에서 locked dependency 설치, Vite build, 정적 자산 복사
- 통합 package 전 `target/classes/static/index.html` 존재 여부 강제
- 기본 lifecycle 시작 시 이전 Frontend 정적 자산 제거로 stale UI 혼입 방지
- Frontend unit test를 Maven 중복 실행에서 제거하고 npm/CI job으로 단일화
- E2E JAR, Docker image와 전체 개발 검증 스크립트에 `frontend` profile 적용
- CI와 release-readiness에 빌드 lifecycle 정적 계약 검사 추가

다음 개선은 비식별 blind evaluation corpus와 managed Kubernetes 실제 장애 표본을 지속적으로
확장하는 것입니다. 그 다음 node enrollment를 ServiceAccount TokenReview 또는 node-bound mTLS로
전환해 static bootstrap Secret 의존성을 줄입니다.

## Phase 29. Blind Evaluation Corpus

구현 및 검증 완료:

- 19개 비식별 holdout evidence를 opaque case ID로 구성
- Analyzer 입력과 sealed label을 별도 JSON으로 분리
- 장애 설명, 예상 signal, root cause, alert/class 필드의 입력 포함 금지
- 모든 detector 실행 후 label을 로드하는 평가 순서 강제
- 정상·경계·단일·복합·degraded evidence와 3개 runtime, 8개 이상 platform shape 검증
- Precision, Recall, 양성·음성 통과율, Top-1/Top-3, forbidden signal gate 적용
- Evidence/label SHA-256과 `label_loaded_after_detection` 상태를 독립 보고서에 기록
- CI 정적 분리 검사와 `rule-analysis-quality` artifact 보존

다음 개선은 원인 판정자가 분석 규칙과 분리된 외부 표본 수집 절차를 만들고, managed Kubernetes
canary에서 비식별 evidence와 사후 판정 label을 누적하는 것입니다. 이후 node enrollment identity를
ServiceAccount TokenReview 또는 node-bound mTLS로 전환합니다.

## Phase 30. Managed Blind Evaluation Intake

구현 및 검증 완료:

- 적용형 managed canary에서만 사용할 수 있는 opt-in evidence 후보 생성
- bundle path traversal, 엔트리 수·크기, manifest SHA-256과 signature attestation 재검증
- 단일 node collector payload만 allowlist로 추출하고 분석 report·signal·action 제외
- cluster/node/workload 식별자, IP, credential, 사용자 경로와 raw Kubernetes metadata redaction
- 무작위 opaque case ID와 analyzer 결과가 없는 adjudication template 생성
- primary/secondary 두 명의 독립 판정, 합의와 RFC 3339 판정 시각 강제
- evidence·label canonical SHA-256을 기록한 immutable sample manifest 생성
- corpus 자동 변경 금지와 별도 검토 PR 승격 계약
- raw lifecycle 미업로드와 runner private directory 삭제를 CI 정적 gate로 검증

이 단계는 실제 managed 장애 표본을 추가한 것이 아니라 안전한 수집·판정 절차를 준비한 것입니다.
실제 표본은 승인된 managed canary와 독립 판정이 완료된 뒤 별도 PR로 누적합니다. 다음 개선은 node
enrollment identity를 ServiceAccount TokenReview 또는 node-bound mTLS로 전환하는 것입니다.

## Phase 31. Agent Enrollment Identity Hardening

구현 및 검증 완료:

- bootstrap token과 Kubernetes TokenReview를 선택하는 클러스터별 enrollment profile
- 관리자가 고정한 HTTPS API Server와 private CA trust, CA fingerprint만 조회·감사에 노출
- TokenReview audience, ServiceAccount subject/UID/group 검증
- trusted Pod 재조회 기반 Pod UID, ServiceAccount, node binding과 삭제 상태 검증
- projected ServiceAccount token의 요청 시점 재읽기와 kubelet rotation 대응
- strict mode의 bootstrap credential 폐기 및 fallback 차단
- bootstrap 복귀 시 암묵적 token 재발급 금지와 명시적 rotation 요구
- TokenReview Helm/RBAC 분기와 bootstrap secret 비노출 렌더링 gate
- Flyway V23 PostgreSQL·MariaDB 공통 enrollment profile schema
- Web Console profile 상태, CA fingerprint, strict mode와 복구 안내

기본값은 rolling compatibility를 위해 `bootstrap-token`입니다. 실제 운영 전환은 대상 API Server가
설정 audience를 수락하는지 canary로 확인한 뒤 strict mode를 적용합니다. 다음 우선순위는 승인된
managed cluster에서 TokenReview enrollment와 실제 장애 표본 intake를 함께 검증하는 것입니다.

## Phase 32. RCA Persistence Idempotency And Worker Lease Safety

구현 및 검증 완료:

- Incident, Report, Job, Notification Outbox와 Analysis Task 완료의 단일 transaction 경계
- V3 `evidence_id` 고유 제약과 `lease_owner + attempt_count` fence 기반 중복 반영 차단
- stale worker 완료 실패 시 Incident occurrence를 포함한 전체 저장 rollback
- commit 이후 Audit·Metrics 실패의 best-effort 격리와 후처리 실패 metric
- Analysis와 Notification worker의 처리 중 lease heartbeat
- Notification timeout, LLM 최대 시도 시간과 lease 관계의 startup fail-fast 검증
- Audit 저장 장애, stale lease, 다중 worker reclaim과 renewal 회귀 테스트
- release-readiness 정적 계약에 멱등성·lease 안전 장치 연결

다음 우선순위는 기계용 opaque Agent token hash를 사용자 password hash와 분리하고, Agent의
주기적 node token rotation을 실제 실행 경로에 연결하는 것입니다.

## Phase 33. Opaque Agent Token Hashing And Automatic Rotation

구현 및 검증 완료:

- 사용자 비밀번호 PBKDF2와 256-bit 기계용 token HMAC-SHA-256 처리 분리
- production 전용 `RCA_OPAQUE_TOKEN_PEPPER` 길이, 기본값, 암호화 키 중복 fail-fast
- 기존 PBKDF2 bootstrap/node token의 무중단 dual-read와 조건부 lazy upgrade
- 동시 token 회전·폐기가 legacy upgrade보다 먼저 반영되면 stale token을 거부하는 CAS 재검증
- Agent node token의 기본 30일 자동 self-rotation과 영속 retry throttle
- active/pending identity의 원자적 저장, 재시작 복구, heartbeat 확인 후 승격
- pending token 거부 시 기존 active token rollback과 bootstrap 자동 재사용 금지 유지
- Helm, 정적 manifest, Compose의 pepper 및 rotation 옵션 연결
- Java 인증 경계·경쟁 조건과 Python crash/retry lifecycle 회귀 테스트
- release-readiness 정적 계약에 opaque token lifecycle 연결

이 단계는 DB schema를 변경하지 않습니다. 기존 token hash는 실제 인증 시 점진 전환하므로
rolling upgrade가 가능하지만, 배포 전 모든 Platform replica에 동일한 pepper를 먼저 제공해야 합니다.
다음 우선순위는 trusted proxy 기반 audit client IP 판정과 destination version이 고정된 outbox 전달입니다.

## Phase 34. Kubernetes Agent Workload Identity And Manifest Parity

구현 및 검증 완료:

- Agent projected token과 Backend reviewer credential의 Kubernetes API 인증 경계 분리
- Platform chart의 opt-in reviewer projected token과 최소 `tokenreviews.create`, `pods.get` RBAC
- ServiceAccount UID, Running Pod, 필수 cluster label, DaemonSet controller UID, image digest 검증
- UID를 배포 후 바인딩할 수 있는 staged profile과 `workload_identity_ready` 상태
- Flyway V24 profile version, workload identity, node binding schema
- profile 보안 필드 변경 시 기존 node token 폐기와 인증 시 profile version 비교
- 활성 node identity를 다른 Pod UID가 덮어쓰지 못하는 명시적 revoke 기반 재등록
- Agent RBAC의 TokenReview 권한 제거와 DaemonSet read parity 수정
- Web manifest와 Helm의 reserved label, capability, RBAC 공통 golden contract
- Web Console workload identity 입력, readiness, profile version 표시

현재 owner reference 검증은 DaemonSet name/UID 연속성을 강화하지만, Pod 생성 권한 자체를 admission
경계로 대체하지는 않는다. 운영 환경은 Agent namespace의 Pod 생성 권한을 제한하고 admission policy,
image digest pinning을 함께 적용해야 한다. 다음 우선순위는 trusted proxy 기반 audit IP 판정,
versioned notification destination, worker별 lease renewal scheduler 분리다.

## Phase 35. Opaque Token Pepper Key Ring And Rolling Rotation

구현 및 검증 완료:

- `hmac_sha256$v2$<key-id>$<digest>` 저장 형식과 기존 v1 dual-read
- 현재 key와 최대 8개 이전 검증 key를 분리한 bounded key ring
- key id 형식, 중복 id/key material, pepper 길이와 암호화 key 재사용 startup 검증
- 첫 코드 배포를 위한 v1 writer 기본값과 명시적인 v2 writer 전환
- 모든 replica 전환 후에만 활성화하는 인증 기반 lazy rehash
- bootstrap/node token 저장소의 compare-and-set 재해시와 최신 credential 재검증
- 준비 단계 replica가 새 v2를 읽고도 이전 v1로 되돌리지 않는 rolling compatibility 테스트
- Secret hash를 노출하지 않는 Helm key-ring revision rollout trigger
- Compose, `.env.example`, release-readiness 정적 계약과 3단계 운영 runbook

key rotation은 단일 Secret 교체가 아니라 reader 준비, writer 전환, lazy rehash의 세 단계로 수행한다.
이전 key 제거 전 비활성 node credential을 회전하거나 재등록해야 한다. 후속 Agent enrollment
audience 분리와 legacy profile binding 정책은 Phase 36에서 처리한다.

## Phase 36. Dedicated Agent Audience And Legacy Profile Fencing

구현 및 검증 완료:

- Agent projected token의 기본 audience를 `cluster-infra-rca-agent-enrollment`로 분리
- Backend의 Kubernetes API audience 목록과 겹치는 enrollment profile 저장 거부
- 운영 기동 시 DB에 남은 위험 audience profile을 cluster ID와 함께 fail-fast
- Agent Helm chart의 API audience 목록 필수화와 audience overlap 렌더링 거부
- Platform reviewer audience가 Backend API audience 목록에 포함되는지 Helm 검증
- V24 이전 `enrollment_profile_version IS NULL` node token의 기본 인증 차단
- ISO-8601 UTC 절대 시각 기반 최대 30일 재등록 유예와 만료 후 자동 차단
- Web Console 전용 audience 기본값, Backend·Helm·Frontend 회귀 테스트

Kubernetes 공식 ServiceAccount 지침에 따라 애플리케이션이 수락하는 audience를 명시하고 API
Server audience와 분리한다. 다음 우선순위는 Helm과 Web Console manifest의 구조적 object diff,
외부 cluster reviewer credential 수명주기, 승인 기반 Agent identity rebind다.

## Phase 37. Agent Enrollment Upgrade Safety

구현 및 검증 완료:

- 기존 Kubernetes API audience profile을 찾는 read-only migration audit CLI
- 정확한 확인 문자열과 cluster allowlist가 필요한 transaction 기반 apply mode
- audience 변경과 profile version 증가, 기존 node token 폐기의 단일 DB transaction
- 기본 audit Helm pre-upgrade hook과 위험 profile 발견 시 upgrade 차단
- Flyway V25 cluster별 legacy unbound token grace와 전역 유예 설정 기동 거부
- Web Console의 cluster별 만료 시각, profile 미결합 Agent와 token 상태 표시
- 실제 Kind API Server에서 전용 audience token의 API 접근 401 및 TokenReview 성공 검증
- Agent 선배포, canary 전환, 검증과 복구를 포함한 운영 runbook

전용 audience 전환은 Agent chart를 먼저 배포한 뒤 cluster allowlist 단위로 수행한다. 유예는 기존
V24 미결합 token의 순차 재등록에만 사용하며, 완료 즉시 제거한다. 다음 우선순위는 Helm과 Web
Console manifest의 구조적 object diff, 외부 reviewer credential 수명주기, 승인 기반 identity
rebind다.

## Phase 38. Documentation Baseline

구현 및 검증:

- 현재 stack, 인증 경계, DB schema, Agent collector와 검증 범위를 `current-state.md`로 통합
- README, Agent, 설치, 운영, 보안, threat model, deployment, database 문서 현행화
- 과거 계획 문서에 역사 문서 표시와 현재 기준 링크 추가
- local Markdown link, UTF-8, migration 수량, stale 보안 설명을 검사하는 CI gate 추가

문서의 기능 상태와 버전이 충돌하면 `current-state.md`와 실제 코드·설정을 우선한다.

## Phase 39. Enrollment Migration Deployment Boundary

구현:

- Helm pre-upgrade hook을 audit-only로 고정하고 apply 관련 release values 제거
- Apply와 최종 audit을 release 외부의 one-shot Job으로 생성하는 renderer 추가
- Platform과 preflight Job의 공통 DB client label 및 내장 DB NetworkPolicy 허용
- preflight와 one-shot Job에 DB URL·사용자·비밀번호 Secret key만 주입
- 실제 fat JAR `PropertiesLauncher`를 PostgreSQL·MariaDB에서 실행하는 Failsafe 추가
- Kind에서 unsafe audit 차단, one-shot migration, 최종 rollout과 Platform TokenReview 전체 등록 경로 추가

로컬 정적·단위 검증 후 PostgreSQL·MariaDB Failsafe와 Kind E2E는 Docker 기반 CI에서 최종 판정한다.

## Positioning

이 프로젝트는 애플리케이션 로그 분석 도구가 아니라 Kubernetes node와 Linux system layer 장애를 근거 기반으로 수집, 분석, 설명하는 RCA 플랫폼이다. 자동 조치는 기본적으로 금지하고, 정책 엔진과 감사 로그를 통해 사람이 승인하고 추적할 수 있는 운영 흐름을 우선한다.
