# Code Review Action Plan

> **역사 문서:** 2026-07-10 코드 리뷰 시점의 실행 기록입니다. 현재 구현 상태와 우선순위는
> [Current State](current-state.md)와 [Roadmap](roadmap.md)을 기준으로 확인합니다.

2026-07-10 코드 리뷰를 현재 `main` 브랜치의 `24d34a4`와 대조해 만든 실행 계획입니다. 리뷰 내용을 그대로 옮기지 않고, 실제 코드 상태와 완료 기준을 기준으로 다시 정리했습니다.

## Current Baseline

| 항목 | 상태 | 확인 결과 |
| --- | --- | --- |
| 실클러스터 준비도 검사 | RKE2 완료 | Helm lint, server-side dry-run, canary 배포와 정리까지 실제 ARM64 RKE2에서 검증함 |
| Agent 전체 E2E | RKE2 완료 | register, heartbeat, evidence, report, incident, bundle 검증과 uninstall을 자동화함 |
| Console API 상태 모델 | 완료 | API별 `LoadState<T>`, stale 데이터 유지, 부분 장애 배너, 갱신 시각 적용 |
| Agent Health 조회 | 완료 | `/api/v1/agent-health` 집계 API로 Dashboard N+1 제거 |
| URL Routing | 완료 | React Router 기반 상세 URL, 직접 진입, 새로 고침, 뒤로 가기, RBAC/missing resource 처리를 적용함 |
| Frontend 사용자 흐름 테스트 | 핵심 흐름 완료 | Playwright로 세션, Cluster onboarding, RCA/manual action, Viewer, 모바일 keyboard 흐름을 검증함 |
| GitOps 변경 추적 | GitHub/GitLab/Gitea 완료 | 승인된 catalog draft의 draft PR/MR 생성, webhook 상태 동기화, 배포/검증/rollback 추적을 지원함 |
| 공통 API 오류 형식 | 완료 | Backend/보안 필터 공통 오류 형식, trace header, Frontend typed error 적용 |

## Implementation Order

### Phase 1. Console Reliability

상태: 완료 (2026-07-10)

운영자가 데이터가 없는 상태와 API 장애를 혼동하지 않게 만드는 작업입니다. 다른 UI 개선과 Routing보다 먼저 적용합니다.

구현 항목:

- Backend 공통 오류 응답에 `code`, `title`, `detail`, `suggestion`, `trace_id` 추가
- Frontend에 `ApiError`와 `LoadState<T>` 도입
- API별 `loading`, `error`, `loadedAt`, `stale` 상태 유지
- 일부 API 실패 시 마지막 정상 데이터를 유지하고 부분 장애 배너 표시
- `401` 또는 세션 만료 시 로그인 화면으로 일관되게 이동
- 마지막 성공 갱신 시각, 수동 재시도, 30초 자동 갱신 제공
- 비활성 브라우저 탭에서는 자동 갱신 중단
- Cluster별 Agent Health N+1 호출을 집계 API 하나로 교체

주요 수정 대상:

- `web-console/frontend/src/api/client.ts`
- `web-console/frontend/src/hooks/useAuthenticatedApi.ts`
- `web-console/frontend/src/hooks/useConsoleData.ts`
- `web-console/frontend/src/App.tsx`
- `web-console/src/main/java/io/clusterinfra/rca/webconsole/controller/ApiExceptionHandler.java`
- Agent Health controller/service/repository

완료 기준:

- Incident API가 500을 반환할 때 `0 incidents`가 아니라 오류 상태가 표시됨
- 다른 API의 마지막 정상 데이터는 사라지지 않음
- 실패한 API 이름, 상태, 마지막 성공 시각, 재시도 동작을 확인할 수 있음
- Cluster 수와 관계없이 Dashboard Agent Health 요청은 한 번만 발생함
- 세션 만료 후 변경 API를 다시 호출하지 않음

### Phase 2. URL Routing And Detail Views

상태: 완료 (2026-07-12)

React Router를 적용해 화면 상태를 URL과 일치시킵니다.

대상 Route:

```text
/overview
/clusters
/clusters/:clusterId
/reports
/reports/:reportId
/incidents
/incidents/:incidentId
/pipeline
/audit
/webhooks
/settings
```

구현 항목:

- `react-router-dom` 추가
- Sidebar 이동, Cluster 선택, Report 선택, Incident 이동을 `navigate()`로 통일
- 새로 고침, 뒤로 가기, 직접 URL 입력, 북마크 지원
- 권한이 없는 Route와 존재하지 않는 ID 처리
- Overview의 `Report detail`이 Report 상세 Route로 이동하도록 수정
- 기존 `/console` 진입 시 `/overview`로 연결하고 인증 전 Route를 보존

완료 기준:

- `/reports/{id}`를 새 탭에서 열어 동일한 보고서를 표시함
- 브라우저 뒤로 가기에서 이전 화면과 선택 항목이 복원됨
- 모바일과 PC에서 기존 레이아웃이 깨지지 않음

검증 결과:

- 라우팅 단위 테스트 4건 통과
- 영문/한글, 모바일/데스크톱 route smoke 통과
- 상세 URL 직접 진입, 누락 리소스 안내, 브라우저 history 복원 확인

### Phase 3. Frontend Regression Tests

상태: 핵심 흐름 완료 (2026-07-12), 오류 주입과 Agent 연결 상태는 지속 확장

화면이 렌더링되는지만 확인하는 smoke test와 실제 운영 업무 흐름 테스트를 분리합니다.

도구:

- Component와 hook: Vitest, React Testing Library, Mock Service Worker
- 사용자 흐름: Playwright
- Backend 계약: 기존 Spring Boot HTTP 통합 테스트

필수 시나리오:

- 정상 0건과 API 실패 구분
- stale 데이터 유지와 재시도
- 세션 만료 후 로그인 이동
- Cluster 생성, 설치 명령 발급, Backend URL 오류 안내
- Agent 미연결과 연결 완료 상태
- Evidence collection 요청
- Report URL 이동과 새로 고침 복원
- 승인, 거절, 수동 완료 workflow
- Viewer 권한에서 변경 및 export 버튼 비노출
- 모바일 dialog/table과 keyboard-only 이동

완료 기준:

- CI에서 component test와 Playwright 업무 흐름이 별도 job으로 실행됨
- 실패한 시나리오의 screenshot, trace, log가 artifact로 남음

구현 및 검증 결과:

- Vitest 대상과 Playwright E2E 대상을 분리
- 보호 URL 로그인 복원과 세션 만료 검증
- Cluster 생성, 설치 명령, 상세 새로 고침, 삭제 검증
- Demo Evidence부터 RCA report, 승인, 수동 완료, 거절까지 검증
- Viewer mutation/export 비노출과 제한 Route redirect 검증
- 모바일 viewport에서 수평 overflow와 keyboard-only 삭제 확인 검증
- CI `console-workflow-e2e` job 및 실패 artifact 업로드 구성

### Phase 4. Real Cluster Agent E2E

상태: RKE2 기준 완료 (2026-07-13), kubeadm amd64/containerd/Flannel 검증 완료
(2026-07-21), 관리형 Kubernetes 검증은 후속 진행

기존 `real-cluster-readiness-check.py`는 배포 전 read-only 검사로 유지합니다. 별도 E2E 실행기를 추가해 실제 lifecycle을 검증합니다.

검증 흐름:

```text
Cluster create
  -> one-time Agent manifest 발급
  -> canary DaemonSet 배포
  -> register와 heartbeat 대기
  -> evidence request 생성
  -> Agent response 대기
  -> analysis task 완료 대기
  -> report와 incident 확인
  -> bundle/signature 검증
  -> 테스트 release 제거와 잔여 리소스 확인
```

안전 조건:

- 명시적인 `--apply` 없이는 Cluster 리소스를 변경하지 않음
- 기존 release, namespace, label은 삭제하지 않음
- 실행기가 생성한 release와 임시 label만 정리
- host mutation, restart, reboot, cordon은 수행하지 않음
- 실패 시에도 수집된 artifact와 cleanup 결과를 보존

결과 Artifact:

- Kubernetes, OS, runtime, CNI 버전
- Agent capability와 Collector 상태
- heartbeat/evidence/analysis latency
- Evidence quality와 RCA Report
- Incident timeline과 bundle 검증 결과
- 설치 및 제거 로그

검증 순서:

1. Kind
2. RKE2 또는 k3s
3. kubeadm
4. 선택한 관리형 Kubernetes 한 종류

RKE2 검증 결과:

- 단일 ARM64 node canary의 register, heartbeat, real evidence, report, incident, bundle 검증 통과
- RKE2 systemd unit과 내장 containerd socket 인식 확인
- file collector의 미수집/건너뜀 상태가 kubelet, runtime, kernel 장애로 판정되던 오탐 수정
- Rancher aggregated API discovery 오류로 namespace 삭제가 지연될 경우 artifact에 경고와 조건을 보존
- 종료 후 테스트 namespace, ClusterRole, ClusterRoleBinding, Helm release 잔여 리소스 없음 확인

### Phase 5. GitOps Change Tracking

상태: GitHub/GitLab/Gitea provider 기준 완료 (2026-07-13)

GitHub, GitLab, Gitea를 지원하며, Platform은 PR/MR 생성과 상태 추적까지만 담당합니다. Kubernetes 리소스를 직접 변경하지 않습니다.

구현 항목:

- `GitOpsProvider` interface와 GitHub/GitLab/Gitea adapter
- 승인된 catalog override에서 branch, commit, PR 생성
- Provider credential은 Secret/env로 주입하고 DB와 Audit에 저장하지 않음
- PR URL, number, state, head SHA 저장
- GitHub/Gitea HMAC 및 GitLab secret token 검증과 merged/closed 동기화
- deployment, verification, rollback 결과 기록 API
- Action Request 또는 Catalog Override Draft와 변경 기록 연결
- 실패 시 rollback PR handoff 제공

권장 저장 모델:

```text
gitops_changes
  change_id
  source_type
  source_id
  provider
  repository
  pull_request_number
  pull_request_url
  pull_request_state
  head_sha
  deployment_state
  deployment_started_at
  deployment_completed_at
  verification_result
  rollback_reference
  created_at
  updated_at
```

완료 기준:

- 승인 전에는 PR을 만들 수 없음
- 동일 draft 재시도로 중복 PR이 생기지 않음
- Webhook 위조와 replay가 차단됨
- merged, closed, deployment, verification, rollback 상태가 Audit과 UI에 표시됨
- Platform과 Agent는 host mutation을 실행하지 않음

## Follow-up Quality Work

P0 작업이 끝난 뒤 다음 순서로 진행합니다.

1. Report, Incident, Task cursor pagination과 검색/필터: 완료
2. Collector별 Typed Evidence Adapter: 완료
3. Golden Scenario dataset과 Precision/Recall/Top-k 평가: 완료
4. LLM supporting evidence ID 강제와 호출량/비용 지표
5. 이전 장애 비교, maintenance window, saved view

## Verification Gates

각 Phase는 다음 공통 검증을 통과해야 완료로 처리합니다.

```powershell
python scripts/verify-api-contract.py
python scripts/release-readiness-check.py
python scripts/verify-supply-chain-workflows.py
```

```bash
cd web-console/frontend
npm run build

cd ..
mvn verify

cd ..
pytest
```

실클러스터 단계에서는 환경별 JSON 결과와 로그를 `validation-results/`에 저장하되, token, 서버 주소, kubeconfig, 사용자 계정은 커밋하지 않습니다.

## First Work Package

다음 구현은 Phase 1의 아래 단위로 시작합니다.

1. 공통 Backend 오류 응답과 Frontend `ApiError`
2. `LoadState<T>` 기반 API별 상태 보존
3. 부분 장애 배너와 마지막 성공 갱신 시각
4. Agent Health 집계 API
5. 실패/0건/세션 만료 회귀 테스트

이 단위가 완료된 뒤 React Router를 적용합니다. Routing과 데이터 상태 모델을 동시에 바꾸지 않아 회귀 원인을 분리합니다.
