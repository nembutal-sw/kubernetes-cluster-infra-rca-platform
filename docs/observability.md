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

## Notes

- Metrics에는 raw evidence나 민감정보를 넣지 않는다.
- tag cardinality는 낮게 유지한다.
- notification 실패는 관찰 가능해야 하지만 RCA report 생성을 실패시키면 안 된다.
- Slack과 generic webhook delivery는 같은 notification outcome metric을 공유한다.
- gauge refresh는 incident processing과 분리한다.
- retention metric은 cluster, node, resource ID를 tag로 사용하지 않는다.
