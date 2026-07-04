# Collector Output Contract

Node Agent collector output은 Backend와 LLM 전처리의 공통 계약입니다.
collector 구현이 바뀌어도 아래 필드와 의미는 유지합니다.

## Envelope

각 collector는 `collectors.<name>` 아래에 JSON object를 반환합니다.

```json
{
  "status": "ok",
  "collected_at": "2026-06-21T02:00:00Z",
  "duration_ms": 42,
  "data_source": "file",
  "warnings": [],
  "error": null
}
```

권장 상태값:

| Status | Meaning | RCA Handling |
| --- | --- | --- |
| `ok` | 수집 성공 | 정상 evidence |
| `limited` | 일부 필드만 수집 | quality gate `limited` 가능 |
| `unsupported` | 플랫폼에서 지원 불가 | confidence penalty 가능 |
| `disabled` | mode/policy로 비활성화 | missing/degraded로 기록 |
| `unavailable` | 파일/socket/API 부재 | degraded로 기록 |
| `timeout` | timeout 발생 | degraded로 기록 |
| `error` | collector 실행 실패 | confidence penalty |

Collector 내부의 서비스 상태값(`systemd.status=failed`, `runtime.status=unhealthy`)은 collector 실행 실패가 아닙니다.
collector 실행 실패로 보려면 `status=error` 또는 `error`, `exception`, `traceback`, `collector_error` 같은 실행 오류 필드가 있어야 합니다.

## Required Metadata

가능하면 모든 collector는 다음 metadata를 포함합니다.

- `status`
- `collected_at`
- `duration_ms`
- `data_source`: `file`, `procfs`, `sysfs`, `socket`, `kubernetes_api`, `ebpf`, `command`
- `warnings`
- `error`

## Collector Fields

| Collector | Stable Fields | Notes |
| --- | --- | --- |
| `node` | `ready`, `conditions`, `kernel_tainted`, `boot_id` | node readiness와 host 상태 |
| `disk` | `root_usage_percent`, `await_ms`, `io_pressure`, `root_mount_read_only`, `largest_paths` | capacity와 latency를 분리 |
| `inode` | `inode_usage_percent`, `hot_paths` | inode 고갈 판단 |
| `memory` | `usage_percent`, `available_bytes`, `pressure`, `oom_kill_detected` | OOM은 kernel/eBPF와 교차 확인 |
| `process` | `pid_usage_percent`, `process_count`, `thread_count`, `zombie_count` | PIDPressure 판단 |
| `kernel` | `messages`, `io_error_detected`, `blocked_task_detected`, `read_only_filesystem_detected` | 로그 excerpt는 redaction 대상 |
| `systemd` | `failed_units`, `messages`, `<unit>_status`, `<unit>_restart_count` | file mode 우선 |
| `runtime` | `status`, `socket_healthy`, `socket_latency_ms`, `runtime_name` | containerd/CRI-O/Docker 공통 |
| `kubelet` | `status`, `active`, `restart_count`, `messages` | unit/journal 접근은 제한 가능 |
| `network` | `interfaces`, `carrier_changes`, `rx_dropped`, `tx_errors`, `tcp_retrans_segments` | NIC/link/TCP 계층 |
| `conntrack` | `count`, `max`, `near_limit`, `insert_failed`, `drop`, `early_drop` | count/max 모두 필요 |
| `cni` | `configured`, `config_count`, `plugin_types`, `mtu_values`, `mtu_mismatch`, `parse_errors` | 설정 변경은 GitOps |
| `dns` | `dns_configured`, `nameserver_count`, `nameservers`, `ndots`, `timeout_seconds`, `latency_ms` | 유저 에이전트/OS 버전은 LLM 입력에서 제외 |
| `kubernetes` | `node_conditions`, `pods`, `services`, `endpoint_slices`, `api_server_latency_ms`, `readyz` | topology와 control-plane evidence |
| `etcd` | `fsync_latency_ms`, `readyz_healthy`, `endpoint_health` | managed cluster에서는 제한 가능 |
| `ebpf` | `events` | OOM kill, TCP retransmit, DNS timeout 같은 realtime event |

## Backend Quality Gate

Backend는 collector output을 그대로 신뢰하지 않습니다.

- stale evidence면 confidence penalty
- collector 실행 실패면 confidence penalty
- unsupported/limited/missing collector면 report quality `limited`
- signal이 없거나 candidate score가 낮으면 `quality_gate.status=insufficient`
- LLM은 `quality_gate`를 참고하지만 confidence를 단독으로 올릴 수 없음

## Compatibility Rule

필드 추가는 허용합니다. 필드 제거/의미 변경은 금지합니다.
지원이 어려운 필드는 값을 추정하지 말고 `null`, `unsupported`, `limited`로 표현합니다.
