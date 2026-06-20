# Observability And SLO

## 한국어 요약

Platform 자체도 운영 대상이기 때문에 RCA 결과뿐 아니라 Platform의 상태도 관측 가능해야 합니다.

현재 observability는 Spring Actuator와 Micrometer 기반입니다. Prometheus는 선택 사항이며, Helm chart에서 `ServiceMonitor`를 활성화할 수 있습니다.

관측 대상은 다음입니다.

- Agent offline count
- Agent heartbeat lag
- Analysis queue depth
- Dead-letter task count
- Webhook ingest count
- Evidence request/response count
- Evidence collection duration
- Analysis task duration
- Report generation duration
- LLM analysis duration/result
- Notification delivery result

---

## English Reference

## Endpoints

```text
GET /actuator/metrics
GET /actuator/prometheus
GET /actuator/health
GET /actuator/health/readiness
```

Metrics endpoints require one of:

```text
ROLE_ADMIN
ROLE_OPERATOR
ROLE_AUDITOR
ROLE_METRICS
```

When `RCA_METRICS_TOKEN` is set, a Prometheus scraper can authenticate with:

```text
X-Metrics-Token: <token>
Authorization: Bearer <token>
```

## Configuration

```yaml
rca:
  observability:
    enabled: true
    metrics-token: ${RCA_METRICS_TOKEN:}
    refresh-interval-ms: 15000
    initial-delay-ms: 5000
```

Production mode requires a non-default metrics token when observability is enabled.

## Metrics

### Agent metrics

```text
rca.agent.heartbeat
rca.agent.offline.count
rca.agent.heartbeat.lag.max.seconds
```

Use these to detect stale or missing node agents.

### Evidence metrics

```text
rca.evidence.requests
rca.evidence.collection
rca.evidence.collection.duration
```

Evidence collection duration measures request creation to agent response.

### Analysis metrics

```text
rca.analysis.queue.depth
rca.analysis.dead.letter.count
rca.analysis.task.claimed
rca.analysis.task.completed
rca.analysis.task.failed
rca.analysis.task.dead.letter
rca.analysis.task.duration
```

These metrics show pipeline health and worker latency.

### Report and incident metrics

```text
rca.report.generation
rca.report.generation.duration
rca.incident
```

`rca.incident` tags correlation outcomes such as created or duplicate/correlated reports.

### LLM metrics

```text
rca.llm.analysis
rca.llm.analysis.duration
```

LLM is optional. Failures should not prevent rule-based RCA from completing.

### Notification metrics

```text
rca.notification
```

Tags include result and severity.

## Gauges Refresh

`OperationalMetricsRefresher` periodically updates gauges from repositories:

```text
agentOfflineCount
agentHeartbeatLagMaxSeconds
analysisQueueDepth
analysisDeadLetterCount
```

This job is skipped when observability is disabled.

## Helm ServiceMonitor

Prometheus Operator users can enable:

```yaml
platform:
  serviceMonitor:
    enabled: true
    interval: 30s
    scrapeTimeout: 10s
    metricsTokenSecretKey: RCA_METRICS_TOKEN
```

The generated `ServiceMonitor` scrapes `/actuator/prometheus` using bearer token authentication.

## Suggested SLOs

Initial portfolio-level SLOs:

```text
99% of heartbeat requests accepted under 1s
95% of evidence requests completed under 180s
95% of RCA analysis tasks completed under 300s
99% of report generation completed under 30s
0 dead-letter tasks during normal demo scenarios
```

## Alert Ideas

```text
agent_offline_count > 0 for 5m
analysis_queue_depth > 50 for 10m
analysis_dead_letter_count > 0
report_generation_p95 > 30s
notification_failed_total > 0
```

## Important Note

Observability must not expose sensitive evidence. Metrics should contain operational counters, durations, and low-cardinality tags only.
