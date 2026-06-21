# Incident Correlation

## 한국어 요약

Incident Correlation v3는 동일 노드의 방향성 규칙에 Kubernetes 토폴로지 관계를 추가합니다.

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

노드 간 병합은 DNS, CNI, network, conntrack, etcd, API Server 계열로 제한합니다. 두 노드가
같은 Service endpoint를 제공하거나 control-plane peer인 경우에만 후보가 됩니다. 디스크,
메모리, PID, runtime, kubelet 같은 노드 로컬 장애는 다른 노드 incident와 병합하지 않습니다.

열린 incident에 일정 시간 동안 새 evidence가 없고 승인 대기 또는 수동 처리 중인 조치가
없으면 자동으로 `resolved` 처리합니다. 이후 같은 노드에서 같은 alert 또는 같은 주 신호
계열이 다시 발생하면 이전 incident를 다시 열지 않고 새 incident를 생성해 재발 관계를 남깁니다.

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
- Service endpoint and control-plane peer relationships

Known signal families:

```text
storage, memory, process, runtime, kubelet, node,
conntrack, network, cni, dns, etcd, api_server
```

Generic collector names such as `kernel` and `systemd` are not correlation families. Specific event
types such as `kernel_oom_detected`, `oom_kill`, `kernel_io_error`, and `tcp_retransmit` are mapped
to a known family.

## Decision Model

The engine checks same-node candidates first. Cross-node candidates are considered only for
cluster-global signal families with a confirmed topology relationship.

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

## Lifecycle And Recurrence

An open incident is automatically resolved when:

- no correlated evidence arrived before the inactivity cutoff
- no action request is pending approval, queued, executing, or waiting for manual completion
- automatic resolution is enabled

Alertmanager `resolved` notifications close matching open incidents immediately. Manual resolve and
reopen endpoints remain available.

A later signal creates a new incident linked through `recurrence_of_incident_id` only when the
resolved incident has the same alert or the same primary signal family. This intentionally uses a
stricter rule than open-incident correlation.

## Configuration

```text
RCA_INCIDENT_CORRELATION_WINDOW_MINUTES=15
RCA_INCIDENT_CORRELATION_MINIMUM_SCORE=70
RCA_INCIDENT_CORRELATION_CANDIDATE_LIMIT=20
RCA_INCIDENT_AUTO_RESOLVE_ENABLED=true
RCA_INCIDENT_INACTIVITY_MINUTES=60
RCA_INCIDENT_LIFECYCLE_SCAN_INTERVAL_MS=60000
RCA_INCIDENT_LIFECYCLE_BATCH_SIZE=100
RCA_INCIDENT_RECURRENCE_LOOKBACK_HOURS=168
RCA_TOPOLOGY_ENABLED=true
RCA_TOPOLOGY_LOOKBACK_HOURS=24
RCA_TOPOLOGY_OBSERVATION_LIMIT=500
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
GET  /api/clusters/{cluster_id}/topology
POST /api/rca/incidents/{incident_id}/resolve
POST /api/rca/incidents/{incident_id}/reopen
```
