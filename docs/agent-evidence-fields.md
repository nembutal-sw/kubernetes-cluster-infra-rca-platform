# Agent Evidence Field 원칙

Node Agent evidence는 두 종류의 값을 구분합니다.

- 안정 필드: collector가 직접 읽거나 계산한 값입니다.
- 참고 필드: raw excerpt, command result, config excerpt처럼 사람이 확인할 수 있는 원문 근거입니다.

추정이 필요한 값은 사실처럼 채우지 않습니다. 예를 들어 한 시점의 `/proc/stat`만 보고 현재 I/O wait rate를 알 수 없으므로 `io_wait_percent`는 `null`로 두고, 참고용으로 `io_wait_percent_since_boot`만 제공합니다.

## 공통 규칙

- collector 실패는 Agent 프로세스 실패로 처리하지 않습니다.
- 실패한 collector는 `status: "error"`와 `error`를 반환합니다.
- 지원하지 않는 collector는 `status: "unsupported"`를 반환합니다.
- 민감한 문자열은 token, password, secret, authorization, api key 패턴 기준으로 마스킹합니다.
- 명령 실행 결과는 `ok`, `exit_code`, `stdout`, `stderr` 구조로 남깁니다.
- 능동 probe는 짧은 timeout이 있는 읽기/연결 확인만 사용합니다.

## 주요 안정 필드

| Collector | 필드 | 의미 |
| --- | --- | --- |
| `node` | `boot_id`, `kernel_tainted`, `kernel_tainted_raw` | `/proc/sys/kernel` 기반 부팅 ID와 kernel taint 상태 |
| `systemd` | `kubelet_status`, `kubelet_sub_state`, `kubelet_result`, `kubelet_restart_count` | `systemctl show kubelet` 결과 |
| `systemd` | `containerd_status`, `containerd_sub_state`, `containerd_result`, `containerd_restart_count` | `systemctl show containerd` 결과 |
| `systemd` | `failed_units` | `systemctl --failed` 파싱 결과 |
| `kubelet` | `kubelet_status`, `kubelet_sub_state`, `kubelet_result`, `kubelet_restart_count` | kubelet unit 상태와 재시작 횟수 |
| `runtime` | `containerd_socket_exists`, `containerd_socket_is_socket` | hostPath socket 파일 상태 |
| `runtime` | `containerd_socket_healthy`, `containerd_socket_latency_ms` | Unix socket 연결 probe 결과 |
| `runtime` | `containerd_pid`, `containerd_pid_running` | containerd pid file과 `/proc` 기준 프로세스 존재 여부 |
| `disk` | `root_path_available` | host root path를 읽을 수 있는지 여부 |
| `disk` | `root_usage_percent`, `inode_usage_percent` | host root path가 있을 때만 채움 |
| `disk` | `root_mount_read_only` | `/proc/mounts` 기준 root filesystem read-only 여부 |
| `disk` | `io_wait_percent_since_boot` | `/proc/stat` 기반 누적 참고값 |
| `disk` | `io_pressure` | `/proc/pressure/io` 파싱 결과 |
| `kernel` | `blocked_task_detected`, `read_only_filesystem_detected` | kernel log 후보에서 장애 문자열 탐지 |
| `memory` | `usage_percent`, `mem_total_kib`, `mem_available_kib`, `swap_usage_percent` | `/proc/meminfo` 기반 값 |
| `memory` | `dirty_kib`, `writeback_kib`, `slab_kib`, `pressure` | writeback 상태와 `/proc/pressure/memory` 파싱 결과 |
| `memory` | `oom_kill_detected` | kernel log 후보에서 OOM 문자열 탐지 |
| `process` | `process_count`, `zombie_process_count`, `pid_usage_percent` | `/proc` 기반 프로세스 상태 |
| `network` | `interfaces` | `/proc/net/dev`와 `/sys/class/net` 기반 NIC 상태 |
| `network` | `interface_rx_error_total`, `interface_tx_error_total`, `interface_rx_drop_total`, `interface_tx_drop_total` | NIC error/drop 합계 |
| `network` | `nic_link_flap_detected` | `carrier_changes`가 0보다 큰 인터페이스가 있는지 |
| `network` | `tcp_retrans_segments`, `tcp_attempt_fails`, `tcp_ext_listen_overflows`, `tcp_ext_listen_drops` | `/proc/net/snmp`, `/proc/net/netstat` 파싱 결과 |
| `network` | `conntrack_usage_percent` | conntrack count/max 기반 값 |
| `conntrack` | `available`, `near_limit` | conntrack 잔여량과 80% 이상 사용 여부 |
| `cni` | `config_count`, `plugin_types`, `mtu`, `mtu_values`, `parse_errors` | CNI config JSON 파싱 결과 |
| `dns` | `nameservers`, `dns_configured`, `ndots`, `timeout_seconds`, `attempts` | `resolv.conf` 파싱 결과 |

## 의도적으로 비워두는 필드

| 필드 | 이유 |
| --- | --- |
| `disk.io_wait_percent` | 단일 snapshot으로 현재 rate를 계산할 수 없음 |
| `network.mtu_mismatch_suspected` | 노드, CNI, pod 경로를 함께 비교해야 판단 가능 |
| `dns.dns_lookup_latency_ms` | 외부 네트워크 probe 대상과 timeout 정책이 필요 |
| `cni.plugin_errors_detected` | CNI별 로그 위치와 형식 차이가 큼 |

이 필드들은 실제 노드 검증 후 collector별로 안전한 측정 방법을 정한 뒤 채웁니다.
