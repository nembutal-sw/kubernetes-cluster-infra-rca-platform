# RCA Scenario Matrix

이 문서는 Rule-based RCA가 반드시 안정적으로 처리해야 하는 장애 시나리오를 정리합니다.
새 detector, policy, timeline rule을 추가할 때는 이 matrix와 fixture 테스트를 같이 갱신합니다.

Fixture 파일:

```text
web-console/src/test/resources/analysis/rule-based-rca-regression-scenarios.json
```

검증 테스트:

```text
RuleBasedRegressionFixtureTests
RuleBasedScenarioTests
IncidentTimelineServiceTests
IncidentCorrelationServiceTests
IncidentCorrelationDatasetTests
```

## Required Scenarios

| Scenario | Main Alert | Root Evidence | Required Signals | Expected Action Boundary |
| --- | --- | --- | --- | --- |
| NodeNotReady | `NodeNotReady` | node readiness, kubelet, runtime, network | `node_not_ready` | collect more evidence only |
| Disk capacity pressure | `DiskPressure` | root usage, large paths | `disk_usage_critical` | cleanup is manual/approval only |
| Disk I/O latency | `DiskPressure` | await/util, kernel I/O log | `disk_io_latency_high`, `kernel_io_error` | hardware/kernel checks are manual |
| Inode exhaustion | `DiskPressure` | inode usage, hot paths | `inode_usage_critical` | cleanup is manual/approval only |
| Memory pressure / OOM | `MemoryPressure` | memory usage, OOM log/eBPF event | `memory_pressure_critical`, `kernel_oom_detected`, `ebpf_oom_kill` | cordon/drain is approval only |
| PID pressure | `PIDPressure` | PID usage, thread/zombie count | `pid_usage_high` | read-only inspection first |
| kubelet failure | `KubeletUnhealthy` | kubelet unit, systemd failure | `kubelet_unit_unhealthy`, `systemd_failed_units` | restart is approval/manual only |
| container runtime failure | `ContainerRuntimeUnhealthy` | CRI socket/unit state | `container_runtime_unit_unhealthy`, `containerd_unit_unhealthy` | restart is approval/manual only |
| CNI config/MTU | `NetworkUnavailable` | CNI config, MTU mismatch | `cni_config_invalid`, `cni_mtu_values_inconsistent` | GitOps PR only |
| CoreDNS failure | `CoreDNSUnhealthy` | endpoint readiness, DNS config | `coredns_no_ready_endpoints`, `dns_unconfigured` | GitOps PR only |
| conntrack exhaustion | `NetworkUnavailable` | count/max, insert/drop counters | `conntrack_table_full`, `conntrack_insert_failures` | sysctl change via GitOps/review |
| NIC link flap | `NetworkUnavailable` | carrier changes, kernel link logs | `nic_link_flap` | manual hardware/network check |
| etcd latency | `EtcdLatencyHigh` | fsync latency, readyz, pod health | `etcd_latency_high`, `etcd_readyz_failed` | read-only etcd health check |
| API Server latency | `APIServerLatencyHigh` | latency, readyz/livez errors | `api_server_latency_high`, `api_server_readyz_failed` | dependency check first |
| systemd restart loop | `NodeNotReady` | failed units, restart counter | `systemd_failed_units` | inspect first, no direct restart |
| eBPF network burst | `NetworkUnavailable` | TCP retransmit, DNS timeout | `ebpf_tcp_retransmit`, `ebpf_dns_timeout` | read-only/GitOps only |

## Quality Expectations

각 fixture는 다음을 만족해야 합니다.

- 최소 하나 이상의 derived signal 생성
- root cause candidate 존재
- `confidence_score`가 시나리오별 최소 기준 이상
- `quality_gate.status`가 `insufficient`가 아님
- disruptive action은 `automation_allowed=false`
- restart, cleanup, cordon, reboot, GitOps PR은 직접 실행 계획을 만들지 않음
- resolution checklist에 운영자가 확인할 명령이 포함됨

## Timeline Expectations

정량 품질 평가는 `RuleAnalysisQualityTests`가 같은 fixture로 수행합니다. Precision, Recall, Top-1,
Top-3 결과는 `web-console/target/analysis-quality-report.json`에 생성되며 CI artifact로 보존됩니다.

연쇄 장애는 causal edge가 생겨야 합니다.

| Root Signal | Downstream Signal | Expected Rule |
| --- | --- | --- |
| `disk_io_latency_high` | `kubelet_unit_unhealthy` | `disk_io_to_kubelet` |
| `kubelet_unit_unhealthy` | `node_not_ready` | `kubelet_to_node_ready` |
| `container_runtime_unit_unhealthy` | `kubelet_unit_unhealthy` | `runtime_to_kubelet` |
| `conntrack_table_full` | `dns_latency_high` | `conntrack_full_to_dns` |
| `cni_mtu_values_inconsistent` | `dns_latency_high` | `cni_mtu_to_dns` |
| `etcd_latency_high` | `api_server_latency_high` | `etcd_fsync_to_api_latency` |
| `memory_pressure_critical` | `kernel_oom_detected` | `memory_to_oom` |

Timeline은 감사 로그가 아니라 RCA 모델입니다. 실제 발생 순서가 늦게 수집되어도 더 upstream인 증거가 있으면 root cause로 승격될 수 있습니다.
