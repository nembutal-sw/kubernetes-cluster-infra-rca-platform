# Stabilization Roadmap

> **역사 문서:** 초기 안정화 단계의 완료 기록입니다. 현재 우선순위는
> [Roadmap](roadmap.md), 현재 구현 기준은 [Current State](current-state.md)를 확인합니다.

운영 안정성, 보안, 유지보수 경계를 먼저 고정한 뒤 기능을 확장한다. 각 phase는 테스트나 정적 검증을 통과한 뒤 다음 단계로 이동한다.

## Phase 1. Security Hardening

상태: 완료

- 클러스터 bootstrap token 평문 저장 제거
- bootstrap token hash, 마지막 사용 시각, 회전 metadata 추가
- agent/webhook 인증 누락 방지 테스트 추가
- manifest one-time token TTL, consume, hash 검증 강화

완료 기준:

- agent register, install command, token rotate 테스트 통과
- manifest token service 테스트 통과
- HTTP 통합 테스트 통과

## Phase 2. Persistence Boundary

상태: 완료

- Cluster, Agent, Evidence, AnalysisTask, Incident, Report repository 분리
- Action, Audit, User, UserSession repository 직접 SQL 소유
- 거대한 통합 저장소 제거
- repository 단위 H2 테스트 추가
- DB 호환성 테스트가 실제 repository 조합을 사용하도록 갱신

완료 기준:

- repository 단위 테스트 통과
- 기존 HTTP 통합 테스트 통과
- public API behavior 유지

## Phase 3. Controller and Service Boundary

상태: 완료

- `RcaController`를 workflow 단위 controller로 분리
- `RcaService`를 action, report, incident, ingest, pipeline service로 분리
- API path와 response 호환성 유지
- audit, action, report, incident 책임 분리

주요 분리 결과:

- `AuditController`
- `AnalysisTaskController`
- `RcaWebhookController`
- `ReportController`
- `IncidentController`
- `ActionWorkflowController`
- `ActionWorkflowService`
- `ReportQueryService`
- `AuditExportService`
- `IncidentWorkflowService`
- `AlertIngestService`
- `CollectorSelectionService`
- `AnalysisPipelineService`

완료 기준:

- 기존 통합 테스트 통과
- controller별 책임과 API 그룹 일치
- route smoke 검증 준비 완료

## Phase 4. Frontend Type Stability

상태: 완료

- `App.tsx`는 인증, URL routing, 화면 조합만 담당하도록 축소
- API 호출 타입을 `ApiCall`, `DownloadApi`, `NotifyFunction`으로 통일
- 화면별 상태 조회와 변경 workflow를 domain hook으로 분리
- 핵심 cluster/action workflow에 React hook 회귀 테스트 추가

주요 분리 결과:

- `useConsoleData`
- `useClusterDetail`
- `useReportDetail`
- `useActionWorkflow`
- `useClusterOperations`
- `useOperationalActions`
- `useAuditSearch`
- `useSettingsOperations`
- `useAuthenticatedApi`
- `useConsoleLocale`
- `useToast`

## Phase 5. RCA Quality Guardrails

상태: 완료

- LLM 결과가 report schema와 policy를 우회하지 못하도록 제한
- rule-based 분석 설명과 evidence path 강화
- detector threshold override 지원
- LLM API key 입력 위치를 환경 변수, Docker env, Kubernetes Secret, Helm values로 명확화
- Web Console Settings에서 LLM 설정 상태와 누락 env 표시

완료 기준:

- LLM structured output DTO와 schema normalization 적용
- 허용하지 않는 field drop
- malformed output, prompt injection 테스트 추가
- LLM source action은 계속 `automation_allowed=false`
- root cause candidate별 score reason과 evidence path 제공

검증 결과:

- structured response normalization과 허용 필드 제한 적용
- malformed output, prompt injection, unknown evidence reference 회귀 테스트 적용
- rule-based candidate의 supporting evidence ID와 evidence path 노출
- cluster별 threshold override 저장 및 PostgreSQL/MariaDB 호환 검증

## Phase 6. Operational Verification

상태: 코드 및 CI 검증 완료, 다중 배포판 실환경 canary 진행 중

- worker 동시 claim 테스트 추가
- lease 만료 후 중복 claim 방지 검증
- worker crash retry, owner-bound fail, dead-letter, retry reset 검증
- duplicate evidence submit idempotency 검증
- PostgreSQL/MariaDB fresh schema 테스트에 동시 claim 계약 추가
- CI에서 PostgreSQL/MariaDB 호환 테스트 4개의 실제 실행과 skip 0건 강제
- Docker base image digest pinning 적용
- container pinning 검증 스크립트 추가
- CI에 Gitleaks, Trivy, Syft SBOM, Grype scan gate 구성
- Kind CI에서 Prometheus Operator selector/reconciliation 및 Alertmanager 전달 검증

남은 실환경 검증:

- kubeadm Agent canary
- EKS, AKS, GKE, OpenShift 보안 정책 및 collector 호환성 확인

## Phase 7. Re-review Hardening

상태: 코드 및 로컬 회귀 검증 완료

- Fleet Evidence 품질과 지연을 실제 DaemonSet Agent의 Platform Evidence Request로 측정
- Overview 요약 API와 route별 frontend polling으로 전체 데이터 반복 조회 제거
- GitOps failed change의 명시적 retry/reconciliation 및 provider 기존 PR 탐색 추가
- Soak artifact에 플랫폼, 아키텍처, Agent/collector/threshold 지문 추가
- Compatibility, absolute, regression 3단계 비교 gate와 Extended workflow baseline 연결
- collector 구현 파일을 `builtin_collectors.py`로 명확화하고 soak 통계 모듈 분리
- 개발/운영 Helm values 분리와 production render-time 안전 조건 추가

실제 DaemonSet Agent Evidence 방식의 1시간 Standard와 5시간 Extended Fleet를 통과했습니다. 남은 외부 검증은 24시간 Production Fleet와 managed Kubernetes 실제 canary입니다.
