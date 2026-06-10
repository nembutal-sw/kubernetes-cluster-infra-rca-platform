# Linux Node Collector 검증 절차

실제 Linux 노드에서 collector 필드를 보강하기 전에 먼저 local collect 모드로 현재 수집 결과를 확인합니다. 이 모드는 Backend 등록 없이 동작하므로 운영 클러스터에 붙이기 전 단일 노드에서 안전하게 확인할 수 있습니다.

## 1. 로컬 수집 실행

노드에 repo 또는 agent image가 준비되어 있다면 아래처럼 실행합니다.

```bash
python -m node_agent.main \
  --collect-local \
  --collectors node,systemd,kernel,disk,inode,memory,process,network,conntrack,runtime,kubelet,cni,dns \
  --output /tmp/cluster-infra-rca-evidence.json
```

hostPath를 직접 지정해야 하는 환경에서는 다음 값을 지정합니다.

```bash
HOST_ROOT=/ \
HOST_PROC=/proc \
HOST_SYS=/sys \
HOST_ETC=/etc \
HOST_VAR_LOG=/var/log \
HOST_RUN=/run \
python -m node_agent.main --collect-local --output /tmp/cluster-infra-rca-evidence.json
```

DaemonSet container 안에서 확인할 때는 기본값인 `/host/root`, `/host/proc`, `/host/sys`, `/host/etc`, `/host/var/log`, `/host/run`을 사용합니다.

## 2. 기본 확인 항목

결과 파일에서 아래 항목을 먼저 확인합니다.

```bash
python -m json.tool /tmp/cluster-infra-rca-evidence.json >/tmp/cluster-infra-rca-evidence.pretty.json
```

- 모든 요청 collector가 `collectors` 아래에 있는지 확인합니다.
- `status: "error"` collector가 있으면 `error` 값을 확인합니다.
- `systemd.kubelet_status`, `systemd.containerd_status`가 노드 상태와 맞는지 확인합니다.
- `systemd.kubelet_sub_state`, `systemd.containerd_sub_state`, `systemd.failed_units`가 `systemctl` 결과와 맞는지 확인합니다.
- `kubelet.kubelet_status`, `kubelet.kubelet_restart_count`, `kubelet.journal`이 kubelet unit과 journal 상태를 반영하는지 확인합니다.
- `runtime.containerd_socket_exists`, `runtime.containerd_socket_healthy`가 실제 socket 상태와 맞는지 확인합니다.
- `node.boot_id`, `node.kernel_tainted`, `kernel.kernel_tainted_raw`가 `/proc/sys/kernel/*` 값과 맞는지 확인합니다.
- `disk.root_path_available`이 `true`인지 확인합니다.
- `disk.root_usage_percent`, `disk.inode_usage_percent`가 `df`, `df -i`와 크게 다르지 않은지 확인합니다.
- `disk.root_mount_read_only`, `disk.io_pressure`가 `/proc/mounts`, `/proc/pressure/io`와 맞는지 확인합니다.
- `memory.usage_percent`가 `/proc/meminfo` 기준으로 계산 가능한지 확인합니다.
- `memory.swap_usage_percent`, `memory.dirty_kib`, `memory.writeback_kib`, `memory.pressure`가 `/proc/meminfo`, `/proc/pressure/memory`와 맞는지 확인합니다.
- `network.interfaces[].mtu`, `network.default_route_interfaces`, `network.conntrack_usage_percent`가 실제 노드와 맞는지 확인합니다.
- `network.interface_*_total`, `network.tcp_retrans_segments`, `network.tcp_ext_listen_overflows`가 `/proc/net/dev`, `/proc/net/snmp`, `/proc/net/netstat`와 맞는지 확인합니다.
- `conntrack.available`, `conntrack.near_limit`가 `nf_conntrack_count/max` 기준으로 계산되는지 확인합니다.
- `cni.plugin_types`, `cni.mtu_values`가 `/etc/cni/net.d` 설정과 맞는지 확인합니다.
- `cni.config_count`, `cni.parse_errors`가 CNI 설정 파일 개수와 JSON 파싱 상태를 반영하는지 확인합니다.
- `dns.nameservers`, `dns.search`, `dns.options`가 `/etc/resolv.conf`와 맞는지 확인합니다.
- `dns.ndots`, `dns.timeout_seconds`, `dns.attempts`가 `options` 값과 맞는지 확인합니다.

## 3. 보강 우선순위

필드를 보강할 때는 아래 순서로 진행합니다.

1. 기존 collector가 `status: "error"`를 반환하는 원인 제거
2. analyzer가 이미 사용하는 안정 필드 정확도 개선
3. 운영자가 원문으로 검증할 수 있는 excerpt 추가
4. active probe 추가

active probe는 timeout과 대상이 명확할 때만 추가합니다. 예를 들어 DNS latency는 lookup 대상 도메인과 timeout 정책이 정해진 뒤 넣습니다.

## 4. 비교 명령

노드에서 사람이 직접 비교할 때 사용할 수 있는 명령입니다.

```bash
systemctl show kubelet --property=ActiveState,SubState,NRestarts,Result --no-pager
systemctl show containerd --property=ActiveState,SubState,NRestarts,Result --no-pager
systemctl --failed --no-legend --plain --no-pager
cat /proc/sys/kernel/random/boot_id
cat /proc/sys/kernel/tainted
df -h /
df -i /
cat /proc/meminfo
cat /proc/pressure/io
cat /proc/pressure/memory
cat /proc/net/dev
cat /proc/net/route
cat /proc/net/snmp
cat /proc/net/netstat
cat /proc/sys/net/netfilter/nf_conntrack_count
cat /proc/sys/net/netfilter/nf_conntrack_max
find /etc/cni/net.d -maxdepth 1 -type f -print
cat /etc/resolv.conf
```

## 5. 수집 결과 공유 시 주의

수집 결과에는 kernel log, systemd 명령 결과, CNI config excerpt가 포함됩니다. Agent가 token/password 계열 문자열을 마스킹하지만, 외부 공유 전에는 한 번 더 확인해야 합니다.
