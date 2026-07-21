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

### RSS steady-state 판정

전체 RSS 증가량만으로는 Python 런타임과 collector 캐시의 정상 워밍업을 누수와 구분하기 어렵습니다. 검증기는 모든 profile에서 다음 수치를 기록하고, `standard`, `extended`, `production`에서만 실패 조건으로 사용합니다.

- profile에 정의된 초기 비율을 warm-up으로 제외합니다. 현재 장시간 profile은 앞 50%를 제외합니다.
- 제외 후 구간의 RSS 선형 기울기를 `MiB/hour`로 계산합니다.
- steady-state 구간의 최대/최소 범위와 연속 증가 횟수를 확인합니다.
- 마지막 10개와 30개 표본의 범위, 최종 증감, 기울기를 진단 값으로 함께 저장합니다.
- 최소 표본 수를 채우지 못하면 장시간 profile은 통과하지 않습니다.

전체 증가량 gate도 유지합니다. 따라서 초기 워밍업이 비정상적으로 크거나 후반부에 지속 증가하는 두 경우를 각각 탐지합니다. `smoke`는 표본이 3개뿐이므로 steady-state를 관측만 하며 누수 판정 근거로 사용하지 않습니다.

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

과거 artifact를 현재 임계값과 계산식으로 다시 판정한 뒤 두 profile을 비교하려면 다음 명령을 사용합니다. 재검증기는 checkpoint에 node, namespace, Pod 식별 필드가 있으면 입력을 거부합니다. 비교 결과에는 target ID를 포함하지 않습니다.

```bash
python3 scripts/agent-soak-revalidate.py \
  --summary validation-results/standard/agent-soak-summary.json \
  --checkpoints validation-results/standard/agent-soak-checkpoints.jsonl \
  --output validation-results/standard/agent-soak-summary-revalidated.json

python3 scripts/agent-soak-comparison.py \
  --baseline validation-results/standard/agent-soak-summary-revalidated.json \
  --candidate validation-results/extended/agent-soak-summary.json \
  --policy config/agent-soak-comparison-policy.json \
  --output validation-results/agent-soak-comparison.json
```

비교기는 `compatibility`, `absolute_gate`, `regression_gate`를 각각 판정합니다. Platform family, architecture, Agent version, collector/threshold 지문, target 수가 맞지 않으면 회귀 계산을 수행하지 않습니다. Extended workflow는 같은 commit의 성공한 Standard Fleet `baseline_run_id`가 필요하며, 지연·payload·RSS·CPU·오류율 중 하나라도 정책 한도를 넘으면 job을 실패시킵니다.

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

아래 smoke 결과는 현재의 `platform_evidence_request` 방식 도입 전 기록입니다. 수집 성공률, evidence 품질, 수집 지연은 GitHub Runner의 local Agent 실행값이고, RSS/CPU/FD/thread/spool은 DaemonSet Pod 관측값입니다. 따라서 3개 DaemonSet Agent 각각의 Evidence 품질 검증으로 해석하지 않습니다.

- Runtime target 3/3 통과, Runner local 수집 성공률과 evidence quality 100%, degraded collector 0%
- Runner local 수집 p95 0.158초, 최대 payload 20,647 bytes, health probe 성공률 100%
- Pod별 RSS peak 32.52~33.46 MiB, fleet spread 0.95 MiB
- p95 CPU, FD peak, thread peak의 fleet spread 0
- 모든 target의 process identity 안정, runtime 관측 오류 0
- spool과 quarantine 파일 0
- artifact에 Pod 이름, namespace, `agent_pod` 필드 없음

이 결과는 과거 CI의 3노드 Kind smoke 기준선입니다. 현재 release 비교 기준선은 아래의 실제 Agent Evidence 방식 Standard 결과입니다.

## Last Verified Fleet Standard

2026-07-22 KST workflow run `29853015154`에서 3노드 Kind DaemonSet Agent의 1시간 `standard` profile을 통과했습니다. 수집 품질과 지연은 각 Agent가 처리한 Platform Evidence Request를 기준으로 측정했습니다.

- checkpoint 60/60, Agent Evidence observation 180/180, target 3/3 통과
- Agent별 수집 성공률과 evidence quality 100%, degraded collector 0%
- Agent Evidence 수집 p95 15.146초, 최대 payload 17,444 bytes
- Pod별 전체 RSS 증가 17.145~19.625 MiB, fleet RSS peak spread 2.102 MiB
- 앞 30개 표본 제외 후 최악 RSS 기울기 `4.139 MiB/hour`, 범위 `1.648 MiB`
- Pod별 p95 CPU 0.1951~0.1963%, FD 증가 0~1, thread 증가 0
- process identity 모두 안정, runtime/collection 오류 0
- spool 파일과 bytes 0, quarantine 0

이 artifact는 같은 commit과 collector/threshold 지문을 사용한 Extended 실행의 승인된 비교 기준선입니다.

## Last Verified Fleet Extended

2026-07-22 KST workflow run `29857828475`에서 3노드 Kind DaemonSet Agent의 5시간 `extended` profile을 통과했습니다. Environment 승인부터 artifact 업로드까지 약 5시간 4분이 걸렸습니다.

- checkpoint 300/300, Agent Evidence observation 900/900, target 3/3 통과
- Agent별 수집 성공률과 evidence quality 100%, degraded collector 0%
- Agent Evidence 수집 p50 8.073초, p95 14.937초, 최대 16.070초
- 최대 payload 18,789 bytes
- Pod별 전체 RSS 증가 19.793~25.098 MiB, fleet RSS peak spread 4.445 MiB
- 앞 150개 표본 제외 후 RSS 기울기 `-0.421~0.835 MiB/hour`, 범위 `1.672~2.578 MiB`
- 최악 연속 RSS 증가 3회, 마지막 구간에서 장기 상승 신호 없음
- Pod별 p95 CPU 0.2131~0.2274%, FD 증가 0~1, thread 증가 0
- process identity 모두 안정, runtime/collection 오류 0
- spool 파일과 bytes 0, quarantine 0

오프라인 재검증도 실패와 warning 없이 통과했습니다. Standard run `29853015154`와 비교한 결과 platform, architecture, Agent version, collector/threshold 지문이 일치했고 compatibility, absolute, regression gate가 모두 통과했습니다. 수집 p95는 15.146초에서 14.937초로 감소했고 최악 steady-state RSS 기울기는 `4.139`에서 `0.835 MiB/hour`로 낮아졌습니다. 단일 Kind 표본만으로 공통 threshold를 낮추지 않으며, 다음 외부 검증은 managed Kubernetes canary와 별도 승인 세션의 24시간 production profile입니다.
