# Operational Burn-in

운영 규모 검증은 한 번의 성공 여부보다 반복 수집의 안정성, evidence 품질, 자원 증가 추세를 함께 봐야 합니다. 이 검증 묶음은 다음 결과를 하나의 summary로 합칩니다.

- Node Agent 반복 로컬 수집
- collector 누락, schema 오류, degraded 비율
- 수집 성공률, p50/p95 지연, 최대 payload 크기
- 선택한 Agent PID의 RSS, file descriptor, thread 증가량과 p95 CPU 사용률
- 선택한 Agent state directory의 spool, quarantine 증가량
- 실제 Kubernetes 클러스터의 read-only readiness와 compatibility fingerprint
- 플랫폼별 real E2E coverage와 managed canary 공백
- provider를 호출하지 않는 LLM burn-in readiness 상태

모든 기본 경로는 read-only입니다. Agent 조치 실행, `kubectl apply/delete`, node restart, LLM provider 호출을 수행하지 않습니다. 원본 evidence는 기본적으로 임시 디렉터리에서 삭제하고, checkpoint에는 상태와 수치만 기록합니다.

## Profiles

| Profile | 반복 | 시작 간격 | 용도 |
| --- | ---: | ---: | --- |
| `smoke` | 3 | 1초 | 설치와 계약 확인 |
| `standard` | 60 | 60초 | 1시간 운영 표본 |
| `extended` | 300 | 60초 | 5시간 Actions 상한 내 표본 |
| `production` | 1,440 | 60초 | 24시간 로컬 운영 검증 |

임계값은 [agent-soak-thresholds.json](../config/agent-soak-thresholds.json)에서 관리합니다. CLI의 `--iterations`, `--interval-seconds`는 짧은 재현과 테스트에만 사용하고, 승인된 운영 기준은 config 변경과 코드 리뷰로 남깁니다.

## Quick Smoke

Linux 노드의 저장소 루트에서 실행합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile smoke \
  --output-dir validation-results/operational-burn-in/agent-soak
```

결과:

```text
validation-results/operational-burn-in/agent-soak/
  agent-soak-checkpoints.jsonl
  agent-soak-summary.json
```

`agent-soak-checkpoints.jsonl`은 매 반복 후 flush와 `fsync`를 수행하므로 중간 종료 시점까지의 기록을 유지합니다.

## Long-Running Agent Observation

DaemonSet Agent 프로세스의 host PID와 state directory를 알고 있을 때 자원과 spool 추세를 같이 측정합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile standard \
  --agent-pid 12345 \
  --state-dir /var/lib/cluster-infra-rca-agent \
  --health-url http://127.0.0.1:8080/health/ready \
  --output-dir validation-results/operational-burn-in/agent-soak
```

- `--agent-pid`를 생략하면 수집 품질과 지연은 판정하지만 RSS/CPU/FD/thread는 `not measured` 경고로 남습니다.
- `--state-dir`를 생략하면 spool/quarantine은 `not measured` 경고로 남습니다.
- health URL에는 credential, query string, fragment를 넣을 수 없습니다.
- `--retain-evidence`는 host 정보가 포함된 원본을 보존하므로 제한된 검증 디렉터리에서만 사용합니다.

24시간 profile은 GitHub Actions job 시간 제한 밖이므로 승인된 Linux 노드의 `tmux` 같은 운영 세션에서 실행합니다.

```bash
python3 scripts/agent-soak-validation.py \
  --profile production \
  --agent-pid 12345 \
  --state-dir /var/lib/cluster-infra-rca-agent \
  --output-dir validation-results/operational-burn-in/production-24h
```

## Integrated Local Summary

실제 클러스터 readiness는 Kubernetes 리소스를 생성하거나 변경하지 않습니다. Helm server dry-run도 영구 리소스를 남기지 않습니다.

```bash
python3 scripts/real-cluster-readiness-check.py \
  --agent-local \
  --agent-output validation-results/operational-burn-in/real-cluster-agent-evidence.json \
  --output validation-results/operational-burn-in/real-cluster-readiness.json
```

LLM 상태는 기존 cumulative history만 읽고 provider를 호출하지 않는 `--provider-call-budget 0 --dry-run` 결과를 사용합니다. 이후 통합 summary를 생성합니다.

```bash
python3 scripts/operational-burn-in-summary.py \
  --agent-soak validation-results/operational-burn-in/agent-soak/agent-soak-summary.json \
  --real-cluster validation-results/operational-burn-in/real-cluster-readiness.json \
  --require-real-cluster \
  --llm-campaign validation-results/operational-burn-in/llm-status/campaign-summary.json \
  --output validation-results/operational-burn-in/operational-burn-in-summary.json \
  --markdown-output validation-results/operational-burn-in/operational-burn-in-summary.md
```

LLM 표본 부족과 managed-platform canary 미완료는 숨기지 않고 `warning`과 `next_actions`로 남깁니다. Agent soak 또는 필수 real-cluster 검증 실패는 전체 결과를 `failed`로 만듭니다.

## GitHub Actions

수동 `Operational Burn-in` workflow는 전용 `rca-demo` self-hosted runner에서 실행합니다.

1. 첫 실행은 `profile=smoke`를 선택합니다.
2. `include_real_cluster=true`로 현재 kubeconfig의 read-only readiness를 포함합니다.
3. 필요하면 `agent_pid`, `agent_state_dir`, `platform_base_url`을 지정합니다.
4. smoke 통과 후 `standard`, 이후 `extended` 순서로 확장합니다.
5. 결과 artifact의 JSON summary와 checkpoint를 검토합니다.

Workflow는 `RCA_LLM_BURN_IN_HISTORY_RUN_ID`의 canonical artifact를 읽어 LLM readiness를 표시하지만 provider 호출 예산은 항상 0입니다. 실제 LLM 표본 수집은 별도 승인형 `LLM Burn-in` workflow에서만 수행합니다.

## Acceptance

- 모든 반복에서 요청 collector와 `collector-evidence/v1` schema가 존재
- profile별 collection/evidence/health 성공률 충족
- degraded collector 비율과 p95 지연이 임계값 이하
- payload가 Agent 8 MiB 기본 한도 이하
- 관찰 대상 Agent의 RSS, FD, thread 증가량과 p95 CPU 사용률이 임계값 이하
- spool과 quarantine 증가량이 임계값 이하
- 실제 클러스터 readiness 실패 없음
- LLM readiness와 managed canary 미완료 상태가 summary에 명시됨
