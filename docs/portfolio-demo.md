# Portfolio Demo

이 문서는 Kubernetes Cluster Infra RCA Platform을 면접과 포트폴리오에서 시연하기 위한 절차입니다. 실제 화면에 존재하는 기능과 API만 사용합니다.

## Demo Boundary

> **중요:** Track A의 내장 Demo는 합성 Evidence입니다. 실제 RKE2 장애를 수집한 결과로 설명하지 않습니다.

- Track A는 누구나 재현 가능한 제품 workflow를 보여줍니다.
- Track B는 실제 RKE2 노드에서 Agent 수집 경로를 보여줍니다.
- Platform과 Agent는 운영 변경 명령을 직접 실행하지 않습니다.
- 승인 workflow는 승인·거절, Runbook 확인과 수동 완료 기록을 남깁니다.
- LLM을 사용하지 않아도 Rule-based RCA는 동작합니다.

## Before The Demo

1. `RCA_DEMO_ENABLED=true`로 Platform을 실행합니다.
2. 강한 관리자 계정과 webhook token을 환경 변수나 Secret으로 설정합니다.
3. `http://localhost:8080/health/ready`가 정상인지 확인합니다.
4. Web Console에 로그인합니다.
5. 브라우저 배율을 100%로 두고 민감정보가 화면에 없는지 확인합니다.
6. 최신 [Portfolio Release Checklist](portfolio-release-checklist.md)의 미완료 외부 gate를 확인합니다.

로컬 실행 예시:

```bash
export RCA_DEFAULT_ADMIN_USERNAME=admin
export RCA_DEFAULT_ADMIN_PASSWORD='<strong-password>'
export RCA_WEBHOOK_TOKEN='<random-webhook-token>'
export RCA_DEMO_ENABLED=true
mvn -f web-console/pom.xml -Pfrontend process-resources spring-boot:run
```

## Track A: Reproducible Built-In Demo

기본 Scenario는 `cni-mtu-mismatch`입니다. CNI와 network Evidence를 함께 사용하며 `NetworkUnavailable` 계열의 원인 후보와 장애 전파를 보여주기 좋습니다.

### 1. Scenario 실행

1. `Pipeline` 화면으로 이동합니다.
2. Demo Scenario에서 `CNI MTU Mismatch`를 선택합니다.
3. 실행 확인 절차를 거쳐 Scenario를 시작합니다.
4. 생성된 Analysis Task ID를 기록합니다.
5. Analysis Task가 `queued` 이후 최종적으로 `completed`가 되는지 확인합니다.

처리 속도와 화면 갱신 시점에 따라 `processing` 상태는 화면에서 보이지 않을 수 있습니다. Task가
`failed`, `retry_wait`, `dead_letter`로 끝나면 정상 결과로 설명하지 않습니다.

API로 같은 작업을 수행할 때는 로그인 후 발급된 access token을 사용합니다.

```bash
curl -X POST http://localhost:8080/api/demo/scenarios/cni-mtu-mismatch/run \
  -H 'Authorization: Bearer <access-token>' \
  -H 'Content-Type: application/json' \
  -d '{"confirmed":true,"node_name":"demo-worker-01"}'
```

등록된 특정 Demo cluster를 사용하려는 경우에만 `cluster_id`를 추가합니다.

```json
{
  "confirmed": true,
  "cluster_id": "<cluster-id>",
  "node_name": "demo-worker-01"
}
```

`cluster_id`를 생략하면 서비스는 `environment=demo`인 기존 cluster를 찾고, 없으면 Demo cluster를
생성합니다. 응답의 `analysis_task.task_id`, `analysis_task.evidence_id`, `cluster.cluster_id`를
기록합니다.

### 2. Analysis Task 확인

`Pipeline` 화면에서 다음을 확인합니다.

- `alert_name` 또는 `task_id`가 실행한 Scenario와 대응하는지
- `cluster_id`와 `node_name`이 Scenario 응답과 일치하는지
- 최종 상태가 `completed`인지
- `failed`, `retry_wait`, `dead_letter`로 종료되지 않았는지

Pipeline Task 목록에는 Report 링크가 없습니다. Report는 다음 순서로 찾습니다.

1. Analysis Task의 최종 상태가 `completed`인지 확인합니다.
2. `RCA Reports` 화면으로 이동합니다.
3. `cluster_id`, `node_name`, Scenario 실행 시각을 기준으로 생성된 Report를 찾습니다.
4. Report ID와 Analysis Task의 cluster·node 정보가 일치하는지 확인합니다.

### 3. RCA Report 확인

`RCA Reports`로 이동해 최신 Report를 선택하고 다음 순서로 설명합니다.

1. **Confidence / Rule signals:** 분석 기준이 Rule-based 결과임을 설명합니다.
2. **Quality gate / Evidence quality:** 입력 freshness와 Collector coverage를 확인합니다.
3. **Cascading timeline:** CNI·network 신호의 관찰 순서와 추론된 전파를 확인합니다.
4. **Rule evidence:** LLM 이전에 어떤 detector signal이 만들어졌는지 확인합니다.
5. **Root cause candidates:** CNI MTU 불일치 후보와 confidence를 확인합니다.
6. **Evidence summary:** 후보를 뒷받침하는 CNI·network Evidence를 확인합니다.
7. **Additional checks:** 운영자가 실제 노드에서 확인할 읽기 전용 명령을 확인합니다.
8. **Policy gate:** 권장 조치가 자동 실행되지 않는 이유와 정책 등급을 확인합니다.
9. **Recommended actions:** source와 `automation_allowed` 상태를 확인합니다.

LLM이 활성화된 경우에도 LLM-origin action은 `automation_allowed=false`, `executable=false`입니다. LLM 호출 결과를 Rule evidence보다 우선하는 것처럼 설명하지 않습니다.

### 4. Approval과 Manual Completion

Rule-based `APPROVAL_REQUIRED` 또는 `GITOPS_PR_ONLY` action이 있을 때만 다음 절차를 진행합니다.

1. Report의 `Recommended actions`에서 요청 생성을 누릅니다.
2. 2차 확인과 요청 사유를 입력합니다.
3. `Action requests`에서 `pending_approval` 상태를 확인합니다.
4. 승인 또는 거절을 선택하고 결정 사유를 기록합니다.
5. 승인한 요청은 Runbook을 사람이 검토했다고 설명합니다.
6. 실제 운영 명령을 Platform에서 실행하지 않습니다.
7. 수동으로 처리했다고 가정한 뒤 완료 메모를 입력합니다.
8. Action request의 수동 처리 완료 이력을 확인합니다.

API 흐름은 다음 endpoint를 사용합니다.

```text
POST /api/rca/reports/{reportId}/actions/{actionIndex}/execute
POST /api/rca/action-requests/{actionRequestId}/approve
POST /api/rca/action-requests/{actionRequestId}/reject
POST /api/rca/action-requests/{actionRequestId}/complete-manual
```

이름에 `execute`가 포함된 첫 endpoint도 Agent 명령을 실행하지 않고 정책에 맞는 Action Request 또는 읽기 전용 Evidence Request를 생성합니다.

역할별 권한은 다음과 같이 구분합니다.

- Action Request 생성: `ADMIN` 또는 `OPERATOR`
- Action Request 승인·거절: `ADMIN` 또는 `APPROVER`
- 수동 처리 완료 기록: `ADMIN` 또는 `OPERATOR`
- Audit 화면 조회와 Audit export: `ADMIN` 또는 `AUDITOR`

### 5. Audit 확인

`Audit` 화면에서 Scenario 실행 시각과 사용자 ID를 기준으로 검색합니다.

- Demo Scenario enqueue
- Analysis Task와 Report 생성
- Action request 생성
- 승인 또는 거절
- 수동 완료 기록
- request ID, client IP, 결과와 timestamp

Audit 화면과 Audit export는 `ADMIN` 또는 `AUDITOR`만 사용할 수 있습니다.

## Track B: Real RKE2 Agent Demo

이 경로는 실제 RKE2 클러스터와 노드가 준비됐을 때만 진행합니다.

1. `Clusters`에서 실제 RKE2 클러스터를 등록합니다.
2. bootstrap 또는 Kubernetes TokenReview 등록 방식을 선택합니다.
3. 생성된 Agent 설치 명령을 검토합니다.
4. 실제 image repository와 Secret을 사용해 Helm으로 Agent를 설치합니다.
5. `Agent connectivity`에서 모든 대상 노드가 `healthy`인지 확인합니다.
6. Agent version과 protocol `v2`를 확인합니다.
7. mode와 Collector posture가 의도한 범위인지 확인합니다.
8. 실제 노드를 대상으로 수동 Evidence collection을 요청합니다.
9. Agent가 요청을 polling하고 `completed` 응답을 제출하는지 확인합니다.
10. Analysis Task가 `completed`인지 확인합니다.
11. 생성된 RCA Report의 원인 후보와 supporting Evidence를 확인합니다.
12. Additional checks를 별도 터미널에서 읽기 전용으로 확인합니다.
13. 필요한 조치는 Runbook에 따라 사람이 처리합니다.
14. Web Console에서 수동 완료와 Audit 기록을 확인합니다.

실제 노드에서 reboot, service restart, CNI 변경, conntrack flush 또는 장애 주입은 이 시연 절차에 포함하지 않습니다.

### RKE2 Evidence To Capture

- `TODO(USER): 실제 RKE2 Cluster 화면 캡처`
- `TODO(USER): Agent healthy 상태 캡처`
- `TODO(USER): 실제 Evidence Request 완료 화면 캡처`
- `TODO(USER): 실제 RCA Report 캡처`
- `TODO(USER): Rule evidence와 Timeline 캡처`
- `TODO(USER): Action Request와 Audit 캡처`

이미지가 준비되기 전에는 존재하지 않는 경로나 예시 이미지를 문서에 추가하지 않습니다.

## Required Screenshots

| 번호 | 화면 | 반드시 보여줄 내용 |
| --- | --- | --- |
| 1 | Overview | Cluster, Incident, Pipeline 요약 |
| 2 | Clusters | Agent connectivity, version, protocol, Collector posture |
| 3 | Pipeline | Analysis Task 상태 전환과 완료 |
| 4 | RCA Report | Rule evidence |
| 5 | RCA Report | Root cause candidates와 supporting Evidence |
| 6 | RCA Report | Cascading timeline |
| 7 | RCA Report | Policy gate와 자동화 차단 사유 |
| 8 | Action request | 승인·거절과 manual completion |
| 9 | Audit | 사용자, client IP, action, outcome, timestamp |

`Settings`의 Catalog GitOps 화면은 선택 사항입니다. 보여줄 경우에도 승인된 Catalog override만 GitOps PR 대상이라는 경계를 설명합니다.

## Three-Minute Interview Script

### 0:00 - 0:30 | 문제

"Kubernetes는 Pod를 재시작할 수 있지만, NodeNotReady가 디스크 I/O, kubelet, runtime, CNI 또는 Linux kernel 문제 중 무엇 때문인지는 설명하지 못합니다. 이 프로젝트는 노드와 Linux 계층의 Evidence를 모아 근본 원인 후보를 만드는 플랫폼입니다."

### 0:30 - 1:00 | 수집

"Python Node Agent가 14개 Collector를 mode에 따라 실행합니다. Agent는 Evidence Request를 polling하고 결과를 제출하며, 전송 실패 시 제한된 local spool을 사용합니다. eBPF event는 일반 Collector와 분리된 선택적 realtime 경로입니다."

### 1:00 - 1:35 | 분석

"Backend는 Spring Boot와 DB 기반 durable task 구조입니다. Worker lease와 retry, dead letter가 있고, Evidence preprocessing, Rule analysis, 선택적 LLM enrichment, Report assembly 단계로 분석합니다. Rule-based 결과가 기준이고 LLM은 설명 보조입니다."

### 1:35 - 2:10 | 결과

"Report에서는 원인 후보, supporting Evidence, 품질 gate, 장애 전파 timeline, 추가 확인 명령과 권장 조치를 확인합니다. Incident correlation으로 같은 원인의 신호를 묶고 알림은 Transactional Outbox로 전달합니다."

### 2:10 - 2:35 | 안전 경계

"AUTO_SAFE도 운영 명령 실행이 아니라 추가 읽기 전용 수집입니다. 변경 조치는 승인과 Runbook, 수동 완료 기록 또는 제한된 Catalog GitOps 절차로 처리합니다. Agent action execution endpoint는 의도적으로 비활성화돼 있습니다."

### 2:35 - 3:00 | 검증과 한계

"RKE2, K3s, kubeadm Agent E2E와 Kind Fleet burn-in 기록이 있고 PostgreSQL·MariaDB, Frontend, Helm과 Security gate를 CI로 검증합니다. 현재 코드는 Kind 3-node에서 bootstrap과 TokenReview Agent 3/3 등록을 다시 통과했습니다. managed Kubernetes 실제 canary, 24시간 Fleet와 실제 장애 corpus 확대는 남아 있습니다."

## Demo Stop Conditions

다음 중 하나라도 발생하면 결과를 정상으로 꾸미지 않고 시연을 중단합니다.

- readiness 실패
- Analysis Task가 `failed`, `retry_wait`, `dead_letter`로 종료
- Report에 Rule evidence가 없음
- Action이 직접 실행 가능한 것처럼 표시됨
- 실제 RKE2 Evidence에 민감정보가 포함됨
- 화면과 설명이 현재 코드 경계와 다름
