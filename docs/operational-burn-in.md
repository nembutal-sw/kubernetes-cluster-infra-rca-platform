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
2. Agent Pod가 하나면 자동 탐색하고, 여러 노드라면 `agent_pod=namespace/name`을 지정합니다.
3. smoke 통과 후 `standard`, `extended` 순서로 확장합니다.
4. 24시간 `production` profile은 Actions 시간 제한 밖의 승인된 Linux 세션에서 실행합니다.

Workflow의 LLM provider 호출 예산은 항상 0입니다. canonical LLM history artifact는 상태 계산에만 사용하고 self-hosted runner 임시 경로에서 job 종료 시 삭제합니다. Actions artifact에는 원본 반복 evidence를 포함하지 않습니다.

## Acceptance

- 요청한 collector가 모든 반복에서 `collector-evidence/v1` schema를 반환
- collection, evidence, health 성공률이 profile 기준 이상
- degraded 비율, p95 지연, payload가 임계값 이하
- Agent 프로세스 재시작 없음
- RSS, CPU, FD, thread 증가가 임계값 이하
- spool과 quarantine 증가가 임계값 이하
- 실제 클러스터 readiness 실패 없음

## Last Verified Smoke

2026-07-21 workflow run `29803718643`에서 14개 collector의 3회 수집, evidence 품질 100%, K3s readiness를 확인했습니다. 당시 runner 권한 경계 때문에 Agent 런타임 추세는 측정하지 못했으며, 현재 workflow는 이 항목을 Pod 내부 read-only 관측으로 필수 검증합니다.
