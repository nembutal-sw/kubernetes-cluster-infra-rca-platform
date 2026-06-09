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

## Collector 목록

| Collector | 수집 대상 |
| --- | --- |
| node identity | node name, OS, kernel version, container runtime, kubelet version |
| systemd | kubelet, containerd, network 관련 unit 상태와 restart count |
| journal | kubelet, containerd, kernel, CNI 관련 journal line |
| kernel | dmesg, I/O error, filesystem error, NIC driver error |
| disk | mount, capacity, inode, I/O latency, filesystem 상태 |
| memory | free memory, swap, OOM event, top memory process |
| pid | pid usage, process count, zombie process |
| network | NIC link, route, DNS resolver, MTU, packet error, retransmit |
| conntrack | conntrack table usage, max, drop hint |
| runtime | containerd socket, task/container 상태, runtime journal |
| kubelet | kubelet health, journal, node lease/API Server 연결 hint |
| CNI | CNI config, plugin log, iptables/ipvs hint, pod CIDR, MTU |
| DNS | `/etc/resolv.conf`, CoreDNS reachability, upstream lookup latency |

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
      "status": "failed",
      "restart_count": 7,
      "journal_excerpt": []
    },
    "containerd": {
      "socket_healthy": false,
      "journal_excerpt": []
    },
    "disk": {
      "root_usage_percent": 78,
      "inode_usage_percent": 99,
      "io_error_detected": false
    },
    "network": {
      "nic_link_flap_detected": true,
      "conntrack_usage_percent": 91
    }
  }
}
```

## 보안 원칙

- Agent는 조치를 실행하지 않습니다.
- Agent는 증거 수집 API만 제공합니다.
- hostPath mount는 읽기 전용을 기본으로 합니다.
- 민감한 값은 Backend 전송 전에 마스킹합니다.
- Kubernetes Secret, token, kubeconfig 원문은 보고서에 포함하지 않습니다.
- 수집 범위는 alert type과 time window 기준으로 제한합니다.

