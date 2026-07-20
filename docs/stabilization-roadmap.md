# Stabilization Roadmap

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

- RKE2 amd64와 kubeadm Agent canary
- EKS, AKS, GKE, OpenShift 보안 정책 및 collector 호환성 확인
