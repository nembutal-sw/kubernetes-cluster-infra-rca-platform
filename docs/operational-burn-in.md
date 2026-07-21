# Operational Burn-in

운영 환경에서 Agent 수집 품질과 장시간 안정성을 반복 검증하는 절차입니다. Kubernetes readiness, 플랫폼 호환성, LLM readiness를 하나의 결과물로 묶되 클러스터 리소스를 변경하거나 LLM provider를 호출하지 않습니다.

## Profiles

| Profile | 반복 | 간격 | 용도 |
| --- | ---: | ---: | --- |
| `smoke` | 3 | 1초 | 설치와 계약 확인 |
| `standard` | 60 | 60초 | 1시간 운영 표본 |
| `extended` | 300 | 60초 | 5시간 Actions 표본 |
| `production` | 1,440 | 60초 | 24시간 별도 운영 검증 |

임계값은 [agent-soak-thresholds.json](../config/agent-soak-thresholds.json)에서 관리합니다. 운영 기준 변경은 CLI override보다 config 변경과 코드 리뷰를 사용합니다.

## Kubernetes Agent Observation

권장 방식은 Ready 상태의 DaemonSet Pod 안에서 Agent 프로세스와 spool 수치만 읽는 것입니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile smoke \
  --discover-agent-pod \
  --require-runtime-observation \
  --health-url http://127.0.0.1:18081/health/ready \
  --output-dir validation-results/operational-burn-in/agent-soak
```

자동 탐색은 고정된 `app.kubernetes.io/part-of=cluster-infra-rca` label과 `agent` 컨테이너의 Ready 상태를 함께 확인합니다. Ready Agent Pod가 둘 이상이면 임의로 선택하지 않으므로 대상을 지정해야 합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile standard \
  --agent-pod rca-system/cluster-rca-agent-abc12 \
  --agent-container agent \
  --kubectl-context operations \
  --require-runtime-observation \
  --output-dir validation-results/operational-burn-in/agent-soak
```

관측 항목:

- Agent RSS, CPU, file descriptor, thread 추세
- 프로세스 시작 tick을 이용한 재시작 감지
- spool 파일 수와 크기, quarantine 파일 수
- 반복 수집 성공률, evidence schema 품질, degraded collector 비율
- 수집 p50/p95 지연과 최대 payload 크기

`kubectl exec`는 저장소에 고정된 Python 관측 코드만 실행합니다. 사용자 입력 명령, shell, spool 본문, 환경변수 값은 실행하거나 결과에 기록하지 않습니다. 실행 계정에는 대상 Pod의 `get/list`와 `pods/exec` 권한이 필요합니다.

## Agent Fleet Observation

다중 노드에서는 모든 Ready Agent Pod를 같은 반복 구간에서 병렬 관측합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile smoke \
  --discover-agent-pods \
  --minimum-agent-pods 3 \
  --require-runtime-observation \
  --output-dir validation-results/operational-burn-in/agent-fleet
```

fleet gate는 각 Pod의 개별 threshold와 다음 Pod 간 편차를 모두 검사합니다.

- RSS peak spread
- p95 CPU spread
- file descriptor peak spread
- thread peak spread
- Pod별 process identity, spool, quarantine, runtime 관측 오류

Pod 이름은 checkpoint와 summary에 저장하지 않습니다. 각 대상은 run마다 생성하는 무작위 salt를 적용한 HMAC-SHA-256 기반 16자리 `target_id`로 표시합니다. 따라서 한 run 안에서는 대상을 구분할 수 있지만 서로 다른 run의 대상을 연결할 수 없습니다. Ready Pod 수가 `--minimum-agent-pods`보다 적거나 하나의 Pod라도 개별 threshold를 넘으면 전체 결과가 실패합니다.

## Local Process Fallback

Kubernetes 밖에서 Agent를 직접 실행한 경우에만 PID와 state directory를 지정합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile standard \
  --agent-pid 12345 \
  --state-dir /var/lib/cluster-infra-rca-agent \
  --require-runtime-observation \
  --output-dir validation-results/operational-burn-in/agent-soak
```

Pod 관측 옵션과 로컬 PID/state 옵션은 함께 사용할 수 없습니다.

## Integrated Summary

```bash
python3 scripts/real-cluster-readiness-check.py \
  --agent-local \
  --agent-output validation-results/operational-burn-in/real-cluster-agent-evidence.json \
  --output validation-results/operational-burn-in/real-cluster-readiness.json

python3 scripts/operational-burn-in-summary.py \
  --agent-soak validation-results/operational-burn-in/agent-soak/agent-soak-summary.json \
  --real-cluster validation-results/operational-burn-in/real-cluster-readiness.json \
  --require-real-cluster \
  --llm-campaign validation-results/operational-burn-in/llm-status/campaign-summary.json \
  --output validation-results/operational-burn-in/operational-burn-in-summary.json \
  --markdown-output validation-results/operational-burn-in/operational-burn-in-summary.md
```

결과 경로:

```text
validation-results/operational-burn-in/
  agent-soak/agent-soak-checkpoints.jsonl
  agent-soak/agent-soak-summary.json
  real-cluster-readiness.json
  llm-status/campaign-summary.json
  operational-burn-in-summary.json
  operational-burn-in-summary.md
```

원본 evidence는 기본적으로 임시 디렉터리에서 삭제합니다. `--retain-evidence`는 제한된 검증 디렉터리에서만 사용해야 합니다.

## GitHub Actions

수동 `Operational Burn-in` workflow는 `rca-demo` self-hosted runner에서 실행합니다.

1. `profile=smoke`, `include_real_cluster=true`로 시작합니다. Agent Pod runtime 관측은 항상 필수입니다.
2. Agent Pod가 하나면 자동 탐색하고, 특정 Pod만 확인할 때는 `agent_pod=namespace/name`을 지정합니다.
3. 다중 노드는 `fleet_mode=true`와 `minimum_agent_pods`를 지정합니다.
4. smoke 통과 후 `standard`, `extended` 순서로 확장합니다.
5. 24시간 `production` profile은 Actions 시간 제한 밖의 승인된 Linux 세션에서 실행합니다.

Workflow의 LLM provider 호출 예산은 항상 0입니다. canonical LLM history artifact는 상태 계산에만 사용하고 self-hosted runner 임시 경로에서 job 종료 시 삭제합니다. Actions artifact에는 원본 반복 evidence를 포함하지 않습니다.

## Long-Running Fleet Workflow

`Agent Fleet Burn-in` workflow는 일반 push CI와 분리된 3노드 Kind 장시간 gate입니다. 일반 CI는 계속 `smoke`만 실행하므로 개발 피드백 시간이 늘어나지 않습니다.

| Profile | 반복 | 예상 시간 | 확인 문자열 | Environment | Artifact 보존 |
| --- | ---: | ---: | --- | --- | ---: |
| `standard` | 60 | 약 1시간 | `RUN-STANDARD-FLEET` | `agent-fleet-standard` | 30일 |
| `extended` | 300 | 약 5시간 | `RUN-EXTENDED-FLEET` | `agent-fleet-extended` | 90일 |

실행 시 `change_reference`를 함께 입력합니다. 두 GitHub Environment에는 required reviewer와 profile 값이 일치하는 `RCA_AGENT_FLEET_PROFILE` Environment variable을 설정합니다. Marker가 없거나 값이 다르면 workflow가 즉시 실패합니다. `extended`는 standard artifact 검토 후 승인합니다. Workflow는 1 control-plane과 2 worker를 만들고 Agent 3개가 모두 등록될 때까지 기다린 다음 다음 항목을 검사합니다.

- 모든 target의 수집 성공률, evidence 품질, process identity
- Pod별 RSS/CPU/FD/thread 증가량
- RSS peak, p95 CPU, FD peak, thread peak의 Pod 간 편차
- spool, quarantine, runtime 관측 오류
- Platform health, evidence 요청, incident, RCA report 생성

결과 artifact 이름은 `agent-fleet-<profile>-<run_id>`입니다. 원본 Pod 이름은 저장하지 않고 run별 salt가 적용된 target ID만 기록합니다. 일반 CI의 smoke 실패, standard 실패, extended 실패는 서로 별개로 숨기지 않고 해당 profile artifact에서 확인합니다.

## Acceptance

- 요청한 collector가 모든 반복에서 `collector-evidence/v1` schema를 반환
- collection, evidence, health 성공률이 profile 기준 이상
- degraded 비율, p95 지연, payload가 임계값 이하
- Agent 프로세스 재시작 없음
- RSS, CPU, FD, thread 증가가 임계값 이하
- fleet 모드에서 모든 target 통과와 Pod 간 자원 편차 기준 충족
- spool과 quarantine 증가가 임계값 이하
- 실제 클러스터 readiness 실패 없음

## Last Verified Smoke

2026-07-21 workflow run `29803718643`에서 14개 collector의 3회 수집, evidence 품질 100%, K3s readiness를 확인했습니다. 당시 runner 권한 경계 때문에 Agent 런타임 추세는 측정하지 못했으며, 현재 workflow는 이 항목을 Pod 내부 read-only 관측으로 필수 검증합니다.

## Last Verified Standard

2026-07-21 workflow run `29806950288`에서 openSUSE K3s 단일 노드 Agent의 1시간 `standard` profile을 통과했습니다.

- 60/60회 수집 성공, evidence 품질 100%, degraded collector 0%
- 수집 p95 0.645초, 최대 payload 98,849 bytes
- RSS 44.38 MiB에서 50.62 MiB, 최대 증가 6.24 MiB
- CPU p95 0.34%, FD 증가 1, thread 증가 0
- process identity 안정, runtime 관측 오류 0
- spool은 60회 중 한 번만 1개/70.24 KiB였고 다음 표본에서 0으로 회복
- quarantine 파일 0

이 결과는 K3s 단일 노드 기준선입니다. 플랫폼과 노드 수가 다른 환경의 표본 없이 공통 threshold를 낮추지 않습니다.

## Last Verified Fleet

2026-07-21 CI run `29813187277`에서 1 control-plane과 2 worker로 구성된 Kind 클러스터의 DaemonSet Agent 3개를 `smoke` fleet profile로 검증했습니다.

- Agent target 3/3 통과, 수집 성공률과 evidence quality 100%, degraded collector 0%
- 수집 p95 0.158초, 최대 payload 20,647 bytes, health probe 성공률 100%
- Pod별 RSS peak 32.52~33.46 MiB, fleet spread 0.95 MiB
- p95 CPU, FD peak, thread peak의 fleet spread 0
- 모든 target의 process identity 안정, runtime 관측 오류 0
- spool과 quarantine 파일 0
- artifact에 Pod 이름, namespace, `agent_pod` 필드 없음

이 결과는 CI의 3노드 Kind smoke 기준선입니다. 다중 노드 standard/extended 및 managed Kubernetes 표본을 확보하기 전에는 공통 threshold를 낮추지 않습니다.

## Last Verified Fleet Standard

2026-07-21 workflow run `29816133829`에서 3노드 Kind DaemonSet Agent의 1시간 `standard` profile을 통과했습니다. Environment 승인부터 cluster 정리까지 전체 소요 시간은 1시간 3분 23초였습니다.

- checkpoint 60/60, Pod runtime snapshot 180/180, Agent target 3/3 통과
- 수집 성공률과 evidence quality 100%, degraded collector 0%, health probe 100%
- 수집 p50 0.108초, p95 0.122초, 최대 0.153초
- 최대 payload 20,662 bytes
- Pod별 RSS peak 54.40~54.82 MiB, fleet spread 0.42 MiB
- Pod별 RSS 증가 21.32~22.17 MiB, 마지막 10회 변동 폭 0.80~0.81 MiB
- Pod별 p95 CPU 0.1834~0.1850%, fleet spread 0.0016%p
- FD peak spread 1, FD 증가 0~1, thread peak spread와 thread 증가 0
- process identity 모두 안정, runtime/collection 오류 0
- spool 파일과 bytes 0, quarantine 0
- checkpoint와 summary의 Pod 이름, namespace, `agent_pod` 노출 0건

RSS는 전반부에 증가한 뒤 후반부에 둔화됐습니다. 누수로 단정할 신호는 아니지만 5시간 extended에서 plateau 유지 여부를 다시 확인합니다. 현재 공통 threshold는 변경하지 않습니다.
