# Node Agent 설계

Node Agent는 각 Kubernetes 노드에 DaemonSet으로 배포되는 로컬 증거 수집기입니다. 목적은 장애 발생 시 운영자가 노드에 직접 SSH로 접속해 확인하던 정보를 자동으로 수집하는 것입니다.

## 역할

- 노드 로컬 로그 수집
- systemd unit 상태 확인
- 커널 로그 확인
- 디스크, inode, 메모리, PID, 네트워크 상태 수집
- container runtime 상태 확인
- kubelet 상태 확인
- CNI, DNS, conntrack 관련 증거 수집
- Backend의 evidence request에 응답

## 현재 실행 흐름

현재 `node_agent` 패키지는 다음 흐름을 수행합니다.

1. `bootstrap-token` 또는 `kubernetes-token-review` identity로 최초 등록합니다.
2. 등록 응답의 node-scoped token을 권한 `0600`인 state 파일에 원자적으로 저장합니다.
3. bootstrap credential은 환경과 메모리에서 제거하고 이후 요청에는 사용하지 않습니다.
4. 주기적으로 heartbeat를 보내고 pending evidence request를 poll합니다.
5. 요청된 collector를 실행해 bounded evidence response를 제출합니다.
6. 전송 실패 payload는 크기와 파일 수가 제한된 spool에 보관하고 이후 다시 전송합니다.
7. node token은 기본 30일마다 2단계 방식으로 자동 회전합니다.

`node-diagnostics`와 `ebpf` 모드는 `/host/proc`, `/host/sys`, `/host/etc`, `/host/var/log`,
`/host/run` 등 필요한 host 자원만 읽기 전용으로 mount합니다. 권한 부족, 명령어·파일 부재,
출력 제한 초과는 Agent 프로세스를 중단하지 않고 collector별 `status`와 오류 metadata로 남깁니다.
수집 결과가 계약을 충족하지 못하면 degraded 또는 quarantine 상태로 분리합니다.

Agent는 Platform이나 host의 운영 상태를 변경하는 명령을 실행하지 않습니다.

## Collector 목록

| Collector | 수집 대상 |
| --- | --- |
| `node` | node name, OS, kernel, load, uptime, Kubernetes node identity |
| `kubernetes` | Node condition, Pod·DaemonSet·Service 영향 단서, event, metrics |
| systemd | kubelet, containerd, network 관련 unit 상태와 restart count |
| kernel | dmesg, I/O error, filesystem error, NIC driver error |
| disk | mount, capacity, I/O latency, filesystem 상태 |
| inode | filesystem별 inode 사용량과 고갈 단서 |
| memory | free memory, swap, OOM event, top memory process |
| process | pid usage, process count, zombie process |
| network | NIC link, route, DNS resolver, MTU, packet error, retransmit |
| conntrack | conntrack table usage, max, drop hint |
| runtime | containerd socket, task/container 상태, runtime journal |
| kubelet | kubelet health, journal, node lease/API Server 연결 hint |
| CNI | CNI config, plugin log, iptables/ipvs hint, pod CIDR, MTU |
| dns | resolver 설정, CoreDNS·upstream lookup 결과와 latency |

독립된 `journal` collector는 없습니다. journal과 systemd evidence는 `systemd`, `kernel`,
`runtime`, `kubelet` collector 안에서 수집합니다. DaemonSet은 host DBus를 직접 제어하지 않고
기본 `SYSTEMD_COLLECTOR_MODE=file`로 host journal 파일을 읽습니다.

## Permission Modes

| Mode | Collector | 권한 기준 |
| --- | --- | --- |
| `safe` | `node`, `kubernetes`, `dns` | 비-root, capability drop, hostPath 없음 |
| `node-diagnostics` | 등록된 14개 collector | Linux host 자원 read-only mount |
| `ebpf` | 14개 collector + 선택적 realtime tracing | 명시적 capability와 kernel 사전 검사 |

eBPF OOM, TCP retransmission, DNS timeout event는 collector registry 항목이 아니라 별도 realtime
event 경로로 전송합니다. `mode=ebpf`와 `EBPF_ENABLED=true`를 함께 설정하고 kernel·capability
사전 검사를 통과해야 활성화됩니다.

필드별 기준은 [Agent Evidence Fields](agent-evidence-fields.md), envelope과 degraded 계약은
[Collector Output Contract](collector-output-contract.md)에 정리합니다. 측정할 수 없는 값은
추정하지 않고 `null` 또는 명시적 unavailable 상태로 남깁니다.

## Evidence bundle 예시

```json
{
  "cluster_id": "cluster-prod-01",
  "node_name": "worker-3",
  "alert_name": "NodeNotReady",
  "time_range": {
    "from": "2026-06-10T09:10:00+09:00",
    "to": "2026-06-10T09:25:00+09:00"
  },
  "collectors": {
    "kubelet": {
      "status": "ok",
      "kubelet_status": "failed",
      "kubelet_restart_count": 7,
      "journal": {
        "ok": true,
        "stdout": "..."
      }
    },
    "runtime": {
      "status": "ok",
      "runtime": "containerd",
      "socket_healthy": false
    },
    "disk": {
      "status": "ok",
      "root_usage_percent": 78,
      "io_error_detected": false
    },
    "inode": {
      "status": "ok",
      "root_inode_usage_percent": 99
    },
    "conntrack": {
      "status": "ok",
      "usage_percent": 91
    }
  }
}
```

## 보안 원칙

- Agent는 조치를 실행하지 않습니다.
- Agent는 등록, heartbeat, token 회전, evidence와 realtime event 전송 API만 사용합니다.
- hostPath mount는 읽기 전용을 기본으로 합니다.
- 민감한 값은 Backend 전송 전에 마스킹합니다.
- Kubernetes Secret, token, kubeconfig 원문은 보고서에 포함하지 않습니다.
- 수집 범위는 alert type과 time window 기준으로 제한합니다.
- node token은 cluster, node, enrollment profile version에 결합합니다.
- spool과 state는 최소 권한, 크기 제한, 원자적 쓰기를 적용합니다.
