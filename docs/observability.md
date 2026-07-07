# Observability And SLO

## 한국어 요약

Platform 자체도 운영 대상이기 때문에 RCA 결과뿐 아니라 Platform의 상태도 관측 가능해야 합니다.

현재 프로젝트는 Micrometer와 Spring Actuator를 사용해 Agent heartbeat, analysis queue,
dead-letter, evidence collection, report generation, LLM analysis, notification outcome,
retention maintenance 등을 metric으로 노출합니다. Prometheus 연동은 선택 사항이며,
Helm chart에서 ServiceMonitor를 켤 수 있습니다.

운영 metric은 공개 API가 아니며, 운영 권한 또는 별도 metric 전용 인증이 필요합니다.

---

## English Reference

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

The `METRICS` role is intended for Prometheus or another trusted scraper.

## Configuration

```text
RCA_OBSERVABILITY_ENABLED=true
RCA_OBSERVABILITY_REFRESH_INTERVAL_MS=15000
RCA_OBSERVABILITY_INITIAL_DELAY_MS=5000
```

When observability is enabled in production, configure a non-default metric credential through deployment secrets.

## Core Metrics

| Metric | Meaning |
| --- | --- |
| `rca.agent.offline.count` | agents beyond heartbeat freshness threshold |
| `rca.agent.heartbeat.lag.max.seconds` | max heartbeat lag across agents |
| `rca.analysis.queue.depth` | queued/retry/processing analysis tasks |
| `rca.analysis.dead.letter.count` | tasks currently in dead-letter |
| `rca.webhook.ingest` | Alertmanager webhook payload count |
| `rca.webhook.alerts` | Alertmanager alert count |
| `rca.evidence.requests` | evidence requests created |
| `rca.evidence.collection` | evidence responses received |
| `rca.evidence.collection.duration` | request-to-response duration |
| `rca.analysis.task.claimed` | tasks claimed by workers |
| `rca.analysis.task.completed` | completed or skipped tasks |
| `rca.analysis.task.failed` | failed task attempts |
| `rca.report.generation` | RCA report generation attempts |
| `rca.report.generation.duration` | report generation duration |
| `rca.llm.analysis` | LLM analysis outcomes |
| `rca.llm.analysis.duration` | LLM analysis duration |
| `rca.notification` | incident notification outcomes |
| `rca.maintenance.run` | scheduled maintenance outcomes |
| `rca.maintenance.duration` | scheduled maintenance duration |
| `rca.maintenance.retention.deleted` | deleted records by bounded data type |

## Operational Gauge Refresh

`OperationalMetricsRefresher` updates long-lived gauges on a schedule. It reads current agents and analysis task counts, then updates:

- offline agent count
- maximum heartbeat lag
- queue depth
- dead-letter count

## Suggested SLOs

Suggested starting points:

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

Enable ServiceMonitor in Helm:

```yaml
platform:
  serviceMonitor:
    enabled: true
    interval: 30s
    scrapeTimeout: 10s
```

The ServiceMonitor scrapes:

```text
/actuator/prometheus
```

## Notes

- Metrics must not contain raw evidence or sensitive values.
- Use low-cardinality tags only.
- Notification failures should be observable but should not fail RCA report generation.
- Slack and generic webhook delivery share the same notification outcome metric.
- Gauge refresh is intentionally separated from incident processing.
- Retention metrics use bounded data-type tags and never include cluster, node, or resource IDs.
