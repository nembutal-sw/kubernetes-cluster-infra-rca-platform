# Observability And SLO

Platform은 Micrometer와 Spring Actuator를 사용합니다.

```text
GET /actuator/metrics
GET /actuator/metrics/{metric-name}
GET /actuator/prometheus
```

`/actuator/health`만 익명 접근이 가능합니다. Metrics endpoint는 `ADMIN`, `OPERATOR`,
`AUDITOR` session 또는 전용 `RCA_METRICS_TOKEN`이 필요합니다.

```bash
curl -H "Authorization: Bearer ${RCA_METRICS_TOKEN}" \
  https://rca.example.com/actuator/prometheus
```

## Metrics

| Metric | Type | Description |
| --- | --- | --- |
| `rca.webhook.ingest` | Counter | Alertmanager payload 처리 결과 |
| `rca.webhook.alerts` | Counter | 수신한 alert 수 |
| `rca.evidence.requests` | Counter | source별 evidence request 생성 결과 |
| `rca.evidence.collection` | Counter | Agent evidence 응답 결과 |
| `rca.evidence.collection.duration` | Timer | 요청부터 Agent 응답까지 시간 |
| `rca.analysis.task.claimed` | Counter | worker가 claim한 task 수 |
| `rca.analysis.task.completed` | Counter | 완료 또는 skip된 task 수 |
| `rca.analysis.task.failed` | Counter | retry/dead-letter 실패 수 |
| `rca.analysis.task.dead.letter` | Counter | dead-letter 전환 누적 수 |
| `rca.analysis.task.duration` | Timer | task 생성부터 완료까지 시간 |
| `rca.report.generation` | Counter | report 생성, correlation, 실패 결과 |
| `rca.report.generation.duration` | Timer | report 분석 및 저장 시간 |
| `rca.incident` | Counter | incident 생성 또는 correlation 결과 |
| `rca.llm.analysis` | Counter | provider별 LLM 완료, 실패, skip 결과 |
| `rca.llm.analysis.duration` | Timer | LLM validation/retry 포함 처리 시간 |
| `rca.notification` | Counter | severity별 알림 전송 결과 |
| `rca.agent.offline.count` | Gauge | heartbeat 기준 offline Agent 수 |
| `rca.agent.heartbeat.lag.max.seconds` | Gauge | 전체 Agent 중 최대 heartbeat 지연 |
| `rca.analysis.queue.depth` | Gauge | queued, retry_wait, processing task 수 |
| `rca.analysis.dead.letter.count` | Gauge | 현재 dead-letter task 수 |

리소스 ID, cluster ID, node 이름은 metric tag로 사용하지 않습니다. 제한된 `result`,
`source`, `provider`, `severity`, `status`만 tag로 사용하여 cardinality를 제어합니다.

## SLO Examples

Report 생성 성공률:

```promql
sum(rate(rca_report_generation_total{result!="failed"}[5m]))
/
sum(rate(rca_report_generation_total[5m]))
```

Report 30초 이내 생성 비율:

```promql
sum(rate(rca_report_generation_duration_seconds_bucket{le="30.0"}[5m]))
/
sum(rate(rca_report_generation_duration_seconds_count[5m]))
```

Evidence 수집 실패율:

```promql
sum(rate(rca_evidence_collection_total{result="failed"}[10m]))
/
sum(rate(rca_evidence_collection_total[10m]))
```

권장 초기 alert:

- `rca_agent_offline_count > 0` 5분 지속
- `rca_analysis_dead_letter_count > 0`
- report 생성 성공률 99% 미만
- evidence 수집 실패율 5% 초과
- 최대 heartbeat 지연이 `RCA_AGENT_OFFLINE_AFTER_SECONDS` 초과

## Helm

Prometheus Operator가 설치된 환경에서는 선택적으로 ServiceMonitor를 생성합니다.

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set platform.secret.metricsToken='<strong-token>' \
  --set platform.serviceMonitor.enabled=true
```

Prometheus를 사용하지 않는 환경에서는 ServiceMonitor를 활성화하지 않아도 모든 RCA 기능이
동작합니다.
