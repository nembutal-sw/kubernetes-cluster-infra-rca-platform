# Portfolio Release Checklist

포트폴리오 동결 기준과 실제 확인 결과를 기록합니다.

- 기준 브랜치: `main`
- 기준 코드 커밋: `8559ef76cbe8308663f7e887dc81ef8914e7d162`
- 문서 보정 상태: **현재 작업 중이며 별도 문서 보정 커밋은 아직 생성하지 않음**
- 점검일: `2026-08-02`
- 현재 동결 판단: **조건부 승인**
- 이번 문서 보정에서 Source code 변경: **없음**
- Blocking Issue: **0건 - 확인된 포트폴리오 문서 불일치 수정 완료**
- 남은 외부 gate: **최신 코드 커밋 GitHub Actions, 실제 RKE2 시연 자료, 민감정보 검토**

## Documentation

- [x] README가 실제 코드와 일치한다.
- [x] Rule-based 우선 원칙이 설명돼 있다.
- [x] LLM의 보조 역할이 설명돼 있다.
- [x] `AUTO_SAFE`가 읽기 전용 Evidence 수집으로 설명돼 있다.
- [x] Agent action execution 비활성화가 설명돼 있다.
- [x] Catalog GitOps와 일반 장애 조치가 구분돼 있다.
- [x] 실제 검증과 fixture 검증이 구분돼 있다.
- [x] AI 활용 범위가 공개돼 있다.
- [x] 깨진 내부 링크가 없다.

## Static Validation

- [x] `verify-documentation` 통과
- [x] `release-readiness-check` 통과
- [x] `verify-api-contract` 통과
- [x] `verify-container-pinning` 통과
- [x] `verify-operational-catalog` 통과
- [x] `verify-supply-chain-workflows` 통과

## Backend

- [x] Maven verify 통과
- [x] 부모 커밋 `b89fe8f` 기준 CI run `30363967144`에서 PostgreSQL·MariaDB DB compatibility job 통과
- [x] 부모 커밋 `b89fe8f` 기준 CI run `30363967144`에서 Flyway V26 migration validation 통과
- [x] 부모 커밋 `b89fe8f` 기준 CI run `30363967144`에서 Production security validation 통과

## Node Agent

- [x] pytest 통과
- [x] `safe` mode Collector 범위 확인
- [x] `node-diagnostics` Collector 범위 확인
- [x] local spool 테스트 통과
- [x] token rotation 테스트 통과

## Frontend

- [x] `npm test` 통과
- [x] `npm run build` 통과
- [x] `npm run smoke:routes` 통과
- [x] 부모 커밋 `b89fe8f` 기준 CI run `30363967144`에서 Playwright E2E 통과

## Helm

- [x] Platform chart lint/template 통과
- [x] Agent `safe` mode lint/template 통과
- [x] Agent `node-diagnostics` mode lint/template 통과
- [x] TokenReview variant가 로컬 render와 3-node Kind에서 통과
- [x] 부모 커밋 `b89fe8f` 기준 CI run `30363967144`에서 Agent manifest parity 통과

## Demo

- [x] `cni-mtu-mismatch` 내장 Demo 완료
- [x] Analysis Task `completed` 확인
- [x] RCA Report 생성 확인
- [x] Rule evidence 확인
- [x] Root cause candidates 확인
- [x] Policy gate 확인
- [x] approval과 rejection 기록 확인
- [x] manual completion 확인
- [x] Audit 확인

내장 Demo는 실제 클러스터 장애가 아닌 **합성 Evidence**를 사용했습니다. 승인 흐름은 `pending_approval -> approved_manual -> completed`, 거절 흐름은 `pending_approval -> rejected`로 확인했으며 Agent 명령 실행 레코드는 생성되지 않았습니다.

## Real Cluster

- [ ] 실제 RKE2 Agent healthy를 이번 동결 작업에서 재확인
- [ ] 실제 Evidence 수집을 이번 동결 작업에서 재확인
- [ ] 실제 RCA Report를 이번 동결 작업에서 재확인
- [ ] 사용자 Screenshot 추가
- [ ] 민감정보 제거 확인

과거 문서에는 RKE2, K3s, kubeadm Real Agent E2E 완료 기록이 있습니다. 위 항목은 포트폴리오 제출용 실제 화면과 결과를 이번 동결 기준으로 다시 확인했는지를 뜻합니다.

## Release

- [x] 최종 변경 diff 검토
- [x] 사용자 미커밋 변경 보존
- [x] Blocking Issue 없음
- [x] Known Limitations 작성
- [x] Post-Portfolio Backlog 분리
- [ ] 최신 코드 커밋 `8559ef76` 기준 GitHub Actions 전체 통과
- [ ] `v1.0.0-portfolio` tag는 사용자 승인 후 별도 생성

## Validation Record

| Validation | Result | Evidence or failure |
| --- | --- | --- |
| Documentation | PASS | UTF-8 Markdown, 내부 링크와 Agent/history 계약 통과 |
| Release readiness | PASS | 로컬 release readiness gate 통과 |
| API contract | PASS | 116개 endpoint, 위반 0건 |
| Container pinning | PASS | 로컬 image pinning 검증 통과 |
| Operational catalog | PASS | Collector 15개, Action 25개, Rule 19개 계약 통과 |
| Supply-chain workflow | PASS | 로컬 workflow 정적 검증 통과 |
| pytest | PASS | 로컬 Python, 210개 통과 |
| Maven verify | PASS | JDK 21, Surefire 372개 실패 0; Docker 미탐지 Testcontainers·packaged-jar IT는 로컬 skip |
| DB compatibility | PASS | 부모 커밋 `b89fe8f` 기준 CI run `30363967144`, PostgreSQL·MariaDB 실행 강제 통과 |
| Frontend test | PASS | Vitest 11 files, 24 tests 통과 |
| Frontend build | PASS | TypeScript 검사와 Vite 8 production build 통과 |
| Integrated package | PASS | JDK 21, Maven `frontend` profile JAR 패키징 통과 |
| Route smoke | PASS | 실행 중인 통합 JAR에서 8 routes, desktop/mobile, en/ko 통과 |
| Built-in demo | PASS | `cni-mtu-mismatch`, Task/Report/Rule/Policy/Timeline/Bundle/Audit 통과 |
| Approval workflow | PASS | 승인·수동 완료·거절·Audit 확인, ActionExecution 0건 |
| Playwright E2E | PASS | 부모 커밋 `b89fe8f` 기준 CI run `30363967144`, `console-workflow-e2e` 성공 |
| Helm | PASS | Platform/Agent lint, safe/node-diagnostics/TokenReview template 통과 |
| Security | PASS | 부모 커밋 기준 Security run `30363966388` 성공 |
| Kind 3-node smoke | PASS | 2026-08-02 격리된 로컬 실행, bootstrap·TokenReview Agent 각각 3/3 등록 |

## Resolved Issue: Kind Agent Fleet Registration Timeout

**이전 증상:** `main` CI run `30363967144`의 3-node Kind smoke에서 DaemonSet Pod 3개 중 Platform Agent inventory에 1개만 등록된 뒤 timeout이 발생했습니다.

**재현 명령:**

```bash
bash scripts/kind-smoke.sh
```

**이전 실패 테스트:** GitHub Actions `CI` run `30363967144`, job `kind-smoke`

**수정 범위:**

```text
scripts/kind-smoke.sh
charts/cluster-infra-rca-agent/templates/daemonset.yaml
charts/cluster-infra-rca-platform/values.yaml
.github/workflows/ci.yml
tests/test_canary_workflows.py
tests/test_real_cluster_agent_e2e.py
```

**관찰 결과와 수정:**

1. Kind 환경에서 `hostNetwork=true` 상태일 때 일부 Agent가 Platform 등록 단계까지 도달하지 못하는 현상을 확인했습니다. 당시 Kubernetes event 또는 Pod log에서 정확한 충돌 지점은 확인하지 못했으므로 특정 포트 충돌로 단정하지 않습니다. Smoke 환경에서 `hostNetwork=false`로 network namespace를 분리하고 Platform Agent inventory를 Kubernetes의 실제 node set과 정확히 비교하도록 검증을 강화한 뒤 bootstrap·TokenReview Agent 3/3 등록을 재확인했습니다.
2. TokenReview chart의 bare `required` 출력이 첫 YAML key와 붙어 잘못된 manifest를 만들었습니다. 반환값을 버리는 Helm assignment로 수정하고 `apps/v1` DaemonSet 렌더 계약을 추가했습니다.
3. release namespace와 chart Namespace가 동시에 생성되는 충돌은 `--create-namespace`와 `namespace.create=false` 조합으로 정리했습니다.
4. non-root Platform이 `0400 root:root` reviewer token을 읽지 못했습니다. `platform.podSecurityContext.fsGroup=65532`로 실제 token을 `0640 root:65532`로 투영합니다.
5. Kind API issuer와 다른 reviewer audience를 기본값으로 사용했습니다. 기본 audience를 `https://kubernetes.default.svc.cluster.local`로 맞추고 현재 Kind smoke에서 검증했습니다. RKE2, K3s, kubeadm의 최신 Chart TokenReview 경로를 같은 변경 기준으로 재검증한 결과는 아니며, 클러스터별 API Server issuer 또는 audience 설정에 따라 명시적 override가 필요할 수 있습니다.

**재검증 결과:** 2026-08-02 격리된 로컬 Kind 3-node smoke에서 Kind v0.31.0, Kubernetes v1.35.0의 1 control-plane·2 worker 구성을 확인했습니다. bootstrap Agent와 TokenReview Agent는 각각 3/3 등록됐고, migration gate, dedicated audience 경계, Evidence 수집, Incident와 RCA Report 생성도 통과했습니다. 해당 로컬 smoke의 수집 성공률과 Evidence 품질은 100%, degraded collector는 0%, p95는 14.955초, runtime/spool/quarantine 오류는 0건입니다. Raw artifact 또는 최신 코드 커밋의 GitHub Actions 결과 연결은 아직 미완료입니다.

**포트폴리오 동결 차단 여부:** 현재 Kind 환경의 재검증에서는 같은 등록 timeout이 재현되지 않았습니다. 최신 코드 커밋 기준 GitHub Actions 결과 확인은 tag 생성 전 필수 gate로 유지합니다.

## User Actions Required

- 실제 RKE2 시연 수행
- 필수 화면 Screenshot 추가
- Screenshot과 Evidence의 민감정보 검토
- 시연 영상 촬영
- 최신 코드 커밋 `8559ef76` 기준 GitHub Actions 전체 통과 확인
- RKE2, K3s, kubeadm에서 최신 Chart의 TokenReview reviewer audience 재검증
- 클러스터별 API Server issuer·audience가 다르면 명시적 override 검토
- 최종 tag와 Release 승인

## Freeze Decision

**Portfolio freeze decision: YES (code and documentation candidate).**

현재 코드의 격리된 로컬 Kind smoke에서는 이전 등록 timeout이 재현되지 않았으므로 포트폴리오 코드·문서 동결 후보로 판단합니다. 다만 `v1.0.0-portfolio` tag와 GitHub Release는 최신 코드 커밋 기준 GitHub Actions가 통과하고, 사용자가 실제 시연 자료와 민감정보를 확인한 뒤 생성합니다.
