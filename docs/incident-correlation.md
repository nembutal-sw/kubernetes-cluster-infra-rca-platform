# Incident Correlation

## 한국어 요약

Incident Correlation v2는 동일 alert의 반복뿐 아니라, 같은 노드에서 짧은 시간 안에 발생한
서로 다른 인프라 신호를 방향성 규칙으로 연결합니다.

대표 규칙:

```text
storage -> runtime/kubelet -> node readiness
memory/PID pressure -> kubelet/node readiness
conntrack/network -> CNI/DNS
etcd -> API server
```

예를 들어 `NodeNotReady`가 먼저 보이고 뒤늦게 Disk I/O evidence가 수집되더라도, storage
신호를 더 상위 원인으로 판단하면 기존 incident의 canonical report와 root cause를 승격합니다.
무관한 subsystem은 같은 시간 구간에 있어도 병합하지 않습니다.

---

## English Reference

## Correlation Inputs

The correlation engine uses:

- cluster ID
- node name
- configurable time window
- normalized signal families
- directed causal rules
- current incident canonical report
- temporal distance score

Known signal families:

```text
storage, memory, process, runtime, kubelet, node,
conntrack, network, cni, dns, etcd, api_server
```

Generic collector names such as `kernel` and `systemd` are not correlation families. Specific event
types such as `kernel_oom_detected`, `oom_kill`, `kernel_io_error`, and `tcp_retransmit` are mapped
to a known family.

## Decision Model

Candidates are open incidents for the same cluster and node within the configured time window.

The engine scores:

- same alert recurrence
- upstream-to-downstream causal relation
- downstream symptom followed by an upstream root signal
- shared known signal family
- temporal proximity

If an incoming signal is more upstream than the current canonical report, the platform:

1. stores the new report in the existing incident
2. updates `latest_report_id`
3. updates the incident root cause and alert name
4. keeps occurrence and evidence history
5. records `incident.root_cause_promoted`

Downstream or repeated signals update occurrence and latest evidence without creating another report.

## Configuration

```text
RCA_INCIDENT_CORRELATION_WINDOW_MINUTES=15
RCA_INCIDENT_CORRELATION_MINIMUM_SCORE=70
RCA_INCIDENT_CORRELATION_CANDIDATE_LIMIT=20
```

## Timeline

The incident timeline reuses the same causal rule registry. It returns:

- normalized signal family per node
- root trigger marker
- directed source and target IDs
- rule ID
- relationship text
- normalized confidence
- whether the edge is causal inference or observed sequence

The timeline is an RCA model, not an audit trail.

```text
GET  /api/rca/incidents
GET  /api/rca/incidents/{incident_id}/timeline
POST /api/rca/incidents/{incident_id}/resolve
POST /api/rca/incidents/{incident_id}/reopen
```
