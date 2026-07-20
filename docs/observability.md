# Observability And SLO

## 개요

Platform 자체도 운영 대상이다. RCA 결과만 보는 것이 아니라 Agent heartbeat, evidence 수집 지연, 분석 큐, dead-letter, report 생성, LLM 호출, 알림, retention 작업 상태를 함께 관찰해야 한다.

현재 Platform은 Micrometer와 Spring Actuator를 사용한다. Prometheus 연동은 선택 사항이며, Prometheus를 쓰지 않는 환경에서는 백엔드 자체 scheduled monitoring을 켜서 등록된 Agent에 주기적으로 read-only evidence collection을 요청할 수 있다.

운영 metric endpoint는 공개 API가 아니다. `ADMIN`, `OPERATOR`, `AUDITOR`, `METRICS` 권한 또는 별도 metrics token으로 제한한다.

## Endpoints

```text
GET /actuator/metrics
GET /actuator/metrics/{metricName}
GET /actuator/prometheus
```

Allowed roles:

```text
ADMIN, OPERATOR, AUDITOR, METRICS
```

`METRICS` role은 Prometheus나 신뢰된 scraper 전용 역할로 둔다.

## Observability Configuration

```text
RCA_OBSERVABILITY_ENABLED=true
RCA_OBSERVABILITY_REFRESH_INTERVAL_MS=15000
RCA_OBSERVABILITY_INITIAL_DELAY_MS=5000
RCA_METRICS_TOKEN=<deployment-secret>
```

`RCA_METRICS_TOKEN`이 설정되면 Prometheus는 아래 중 하나로 인증할 수 있다.

```text
X-Metrics-Token: <token>
Authorization: Bearer <token>
```

## Backend Scheduled Monitoring

Prometheus나 Alertmanager를 쓰지 않는 환경에서는 Platform이 자체적으로 Agent에 collection request를 만들 수 있다.

```text
RCA_MONITORING_ENABLED=true
RCA_MONITORING_INTERVAL_MS=60000
RCA_MONITORING_INITIAL_DELAY_MS=30000
RCA_MONITORING_COLLECT_HEALTHY_AGENTS=true
RCA_MONITORING_HEALTHY_INTERVAL_MINUTES=15
RCA_MONITORING_DEGRADED_INTERVAL_MINUTES=5
RCA_MONITORING_STALE_INTERVAL_MINUTES=2
RCA_MONITORING_VERSION_MISMATCH_INTERVAL_MINUTES=60
RCA_MONITORING_UNAUTHORIZED_INTERVAL_MINUTES=60
```

동작 기준:

- pending evidence request가 있는 노드는 새 요청을 만들지 않는다.
- 같은 노드와 같은 상태의 최근 scheduled request가 있으면 건너뛴다.
- healthy Agent는 baseline collector만 실행한다.
- stale 또는 collector degraded 상태는 systemd, kernel, kubelet을 포함한 deep collector를 실행한다.
- scheduled monitoring에서 수집된 healthy evidence는 RCA report 생성을 skip한다.

## Core Metrics

| Metric | Meaning |
| --- | --- |
| `rca.agent.offline.count` | heartbeat freshness 기준을 넘긴 Agent 수 |
| `rca.agent.heartbeat.lag.max.seconds` | 등록된 Agent 중 최대 heartbeat 지연 |
| `rca.analysis.queue.depth` | queued, retry-waiting, processing 상태의 분석 작업 수 |
| `rca.analysis.dead.letter.count` | dead-letter 상태의 분석 작업 수 |
| `rca.webhook.ingest` | Alertmanager webhook payload 수 |
| `rca.webhook.alerts` | Alertmanager alert 수 |
| `rca.evidence.requests` | Platform이 만든 evidence request 수 |
| `rca.evidence.collection` | Agent evidence response 수 |
| `rca.evidence.collection.duration` | evidence request 생성부터 response까지 걸린 시간 |
| `rca.analysis.task.claimed` | worker가 claim한 분석 작업 수 |
| `rca.analysis.task.completed` | completed 또는 skipped 분석 작업 수 |
| `rca.analysis.task.failed` | 실패한 분석 시도 수 |
| `rca.report.generation` | RCA report 생성 시도 수 |
| `rca.report.generation.duration` | RCA report 생성 시간 |
| `rca.llm.analysis` | LLM 분석 결과 |
| `rca.llm.analysis.duration` | LLM 분석 소요 시간 |
| `rca.llm.request` | provider 호출 결과, operation/provider/model 기준 |
| `rca.llm.request.duration` | provider 단일 호출 지연 시간 |
| `rca.llm.usage` | provider usage metadata 제공 여부 |
| `rca.llm.tokens` | provider가 반환한 input/output/total token 누적값 |
| `rca.llm.estimated.cost.usd` | 설정 단가로 계산한 예상 USD 비용 누적값 |
| `rca.notification` | incident notification 결과 |
| `rca.maintenance.run` | scheduled maintenance 실행 결과 |
| `rca.maintenance.duration` | scheduled maintenance 소요 시간 |
| `rca.maintenance.retention.deleted` | retention policy로 삭제된 레코드 수 |

## Operational Gauge Refresh

`OperationalMetricsRefresher`는 주기적으로 현재 Agent와 analysis task 상태를 읽어 gauge를 갱신한다.

- offline agent count
- maximum heartbeat lag
- queue depth
- dead-letter count

## Suggested SLOs

초기 운영 기준:

```text
Report generation p95 < 30s
Analysis task processing p95 < 300s
Evidence collection p95 < 300s
LLM analysis p95 < 60s
Dead-letter task count = 0
Agent offline count = 0 for required nodes
Retention maintenance failures = 0
```

LLM p95 임계값은 단일 성공 실행으로 조정하지 않습니다. `scripts/llm-burn-in-campaign.py`로 호출 예산을 제한하고 `scripts/llm-burn-in-report.py`로 최소 20개 표본과 5개 장애 시나리오를 모은 뒤 latency, 오류율, usage metadata와 LLM action 안전성을 함께 검토합니다. 기준을 채우지 못하면 60초 초기값을 유지합니다.

## Prometheus Operator

Helm에서 ServiceMonitor를 켤 수 있다.

```yaml
platform:
  serviceMonitor:
    enabled: true
    interval: 30s
    scrapeTimeout: 10s
```

ServiceMonitor scrape path:

```text
/actuator/prometheus
```

### LLM PrometheusRule

Prometheus Operator를 사용하는 환경에서는 LLM SLO recording rule과 alert를 선택적으로 생성할 수 있다. 기본값은 비활성화다.

```yaml
platform:
  serviceMonitor:
    enabled: true
  prometheusRule:
    enabled: true
    evaluationWindow: 10m
    latency:
      p95Seconds: 60
      for: 10m
    errorRate:
      ratio: 0.10
      minimumRequests: 5
    usageMetadata:
      unavailableRatio: 0.10
      minimumRequests: 5
    costBudget:
      enabled: true
      lookback: 24h
      maxUsd: 25
```

생성되는 alert:

- `ClusterRcaLlmHighLatency`: analysis 요청 p95 latency 초과
- `ClusterRcaLlmHighErrorRate`: 최소 호출 수를 넘긴 뒤 분석 실패율 초과
- `ClusterRcaLlmUsageMetadataMissing`: provider usage metadata 누락률 초과
- `ClusterRcaLlmCircuitBreakerOpen`: LLM circuit breaker open 감지
- `ClusterRcaLlmEstimatedCostBudgetExceeded`: 설정 단가 기준 예상 비용 예산 초과

비용 alert는 `costBudget.enabled=true`일 때만 생성한다. `RCA_LLM_INPUT_COST_PER_MILLION_TOKENS`와 `RCA_LLM_OUTPUT_COST_PER_MILLION_TOKENS`를 실제 계약 단가로 설정하지 않았다면 비용 alert를 켜지 않는다.

### AlertmanagerConfig

Prometheus Operator가 관리하는 Alertmanager에서 Platform webhook으로 전달하려면
`AlertmanagerConfig`를 함께 활성화한다.

```yaml
platform:
  prometheusRule:
    enabled: true
  alertmanagerConfig:
    enabled: true
    clusterId: production-a
    labels:
      release: kube-prometheus-stack
    sendResolved: true
```

`clusterId`는 Web Console에 이미 등록된 클러스터 ID여야 한다. 차트는 이 값을
자체 LLM alert의 `cluster_id` label에 주입한다. `labels`는 운영 중인
Alertmanager의 `alertmanagerConfigSelector`와 일치해야 한다. 기본 webhook URL은
같은 namespace의 Platform Service이고, 다른 namespace나 외부 Platform을 사용할
때는 `webhookUrl`을 명시한다. Bearer credential은 기본적으로 Platform Secret의
`RCA_WEBHOOK_TOKEN`을 참조하며 token 원문을 `AlertmanagerConfig`에 기록하지 않는다.

## Notes

- Metrics에는 raw evidence나 민감정보를 넣지 않는다.
- tag cardinality는 낮게 유지한다.
- notification 실패는 관찰 가능해야 하지만 RCA report 생성을 실패시키면 안 된다.
- Slack과 generic webhook delivery는 같은 notification outcome metric을 공유한다.
- gauge refresh는 incident processing과 분리한다.
- retention metric은 cluster, node, resource ID를 tag로 사용하지 않는다.
- `PrometheusRule`은 Prometheus Operator CRD가 설치된 환경에서만 활성화한다.

### LLM Rule Regression Test

Helm이 렌더링한 `PrometheusRule`의 `spec.groups`를 실제 Prometheus rule 파일로
추출한 뒤 `promtool`로 문법과 평가 결과를 검증한다. 테스트에는 정상 트래픽과
latency, error ratio, usage metadata, circuit breaker, cost budget 경보 시나리오가
포함된다.

```bash
python3 scripts/llm_prometheus_rule_test.py \
  --helm helm \
  --promtool promtool
```

CI는 Prometheus `3.12.0`의 Linux amd64 archive를 SHA-256으로 확인한 뒤 같은
테스트를 실행한다. 이 테스트는 Alertmanager delivery 자체가 아니라 Prometheus의
recording/alert rule 평가와 firing label을 검증한다.

### Alertmanager Delivery Integration Test

아래 테스트는 Helm이 렌더링한 실제 rule을 Prometheus `3.12.0`에 로드하고,
Alertmanager `0.33.1`이 Bearer credentials file을 사용해 webhook으로 전달하는지
확인한다. circuit breaker alert의 `firing`과 `resolved`가 모두 수신되어야
성공한다.

```bash
python3 scripts/alertmanager_delivery_test.py \
  --helm helm \
  --prometheus prometheus \
  --alertmanager alertmanager
```

CI는 Prometheus와 Alertmanager archive를 각각 SHA-256으로 검증한 뒤 이 테스트를
실행한다. 이 테스트는 Operator가 생성하는 런타임과 같은 Prometheus/Alertmanager
notification 경로를 검증한다. 실제 클러스터에서는 추가로
`AlertmanagerConfig` selector가 해당 리소스를 선택했는지 확인해야 한다.

### Prometheus Operator Delivery E2E

실제 Operator selector와 reconciliation은 별도의 Kubernetes canary로 검증한다.
기본 실행은 cluster에 접근하지 않으며 `--apply`와 현재 context의 명시적 확인이
모두 있어야 리소스를 생성한다.

```bash
context="$(kubectl config current-context)"
scripts/prometheus-operator-delivery-e2e.sh \
  --apply \
  --confirm-context "${context}" \
  --selector-label release=monitoring
```

canary는 기존에 존재하지 않는 고유 namespace에 digest-pinned webhook sink,
`PrometheusRule`, `AlertmanagerConfig`만 생성한다. `firing`을 받은 뒤 rule을
비활성화해 `resolved`까지 확인하며, namespace ownership label이 실행 ID와
일치할 때만 정리한다. Webhook token은 Kubernetes Secret에만 저장하고 진단
산출물에는 포함하지 않는다.

CI는 Kind `0.32.0`, digest-pinned Kubernetes `1.35.5` node image,
`kube-prometheus-stack` chart `87.17.0`을 사용해 동일한 canary를 실행한다.
