# Retention Policy

## 한국어 요약

Platform은 evidence, analysis task, realtime event, RCA report, audit event가 무제한 증가하지
않도록 예약된 retention cleanup을 실행합니다.

삭제는 단순 날짜 조건으로 처리하지 않습니다. 열린 incident, 승인 처리 중인 action, 다른
레코드가 참조하는 evidence는 보존합니다. 정리 작업은 제한된 배치와 단일 트랜잭션으로 실행되어
DB 부하와 부분 삭제 위험을 줄입니다.

---

## English Reference

## Default Schedule And Retention

The default cleanup schedule is `03:17` every day.

| Data | Default retention |
| --- | --- |
| Realtime/eBPF events | 14 days |
| Topology observations | 30 days |
| Evidence bundles | 30 days |
| Completed or failed evidence requests | 30 days |
| Terminal analysis tasks | 30 days |
| Resolved incidents and RCA reports | 365 days |
| Audit events | 180 days |
| Expired user sessions | next cleanup run |

## Safety Rules

- Open incidents are never selected.
- Resolved incidents with active action requests or executions are preserved.
- Standalone reports referenced as an incident's latest report are preserved.
- Evidence is deleted only after request, job, task, realtime event, and incident references are gone.
- Primary cleanup selections are limited by the configured batch size, capped at 1,000.
- Dependent action, task, and job rows are removed with their selected report to preserve FK integrity.
- One incident with more reports than the current report budget is skipped.
- The cleanup repository runs in one transaction.

Deletion order:

```text
resolved incident
  -> action execution/request
  -> analysis task/job/report
  -> incident
  -> realtime event
  -> standalone terminal task
  -> completed/failed evidence request
  -> orphan evidence
  -> expired session
  -> old audit event
```

## Configuration

```text
RCA_MAINTENANCE_ENABLED=true
RCA_MAINTENANCE_CRON="0 17 3 * * *"
RCA_MAINTENANCE_BATCH_SIZE=250
RCA_REALTIME_EVENT_RETENTION_DAYS=14
RCA_TOPOLOGY_OBSERVATION_RETENTION_DAYS=30
RCA_EVIDENCE_RETENTION_DAYS=30
RCA_EVIDENCE_REQUEST_RETENTION_DAYS=30
RCA_ANALYSIS_TASK_RETENTION_DAYS=30
RCA_REPORT_RETENTION_DAYS=365
RCA_AUDIT_RETENTION_DAYS=180
```

## Audit And Metrics

Each run records one of these audit events:

```text
maintenance.retention_completed
maintenance.retention_failed
```

Operational metrics:

```text
rca.maintenance.run
rca.maintenance.duration
rca.maintenance.retention.deleted
```

`rca.maintenance.retention.deleted` uses a bounded `data_type` tag and does not expose cluster,
node, incident, or evidence identifiers.
