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

## 현재 구현된 MVP

현재 `node_agent` 패키지는 다음 흐름을 수행합니다.

1. 환경변수에서 `BACKEND_URL`, `CLUSTER_ID`, `AGENT_TOKEN`, `NODE_NAME`을 읽습니다.
2. `/api/agents/register`로 Agent를 등록합니다.
3. 주기적으로 heartbeat를 보냅니다.
4. `/api/agents/evidence-requests`에서 pending request를 poll합니다.
5. 요청된 collector를 실행하고 `/api/agents/evidence-responses`로 결과를 제출합니다.

실제 Linux 노드에서는 hostPath mount를 통해 `/host/proc`, `/host/sys`, `/host/etc`, `/host/var/log`, `/host/run`을 읽습니다. collector 실행 중 권한 부족, 명령어 부재, 파일 부재가 발생해도 Agent 프로세스는 종료하지 않고 해당 collector 결과를 `status: "error"` 또는 명령 실패 결과로 남깁니다.

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

MVP collector는 외부 네트워크에 능동적으로 요청하지 않습니다. DNS collector도 우선 `resolv.conf` 파싱까지만 수행합니다. 실제 lookup latency 측정은 운영 환경에서 timeout과 대상 도메인을 정한 뒤 추가하는 편이 안전합니다.

필드별 기준은 [docs/agent-evidence-fields.md](agent-evidence-fields.md)에 정리합니다. 측정할 수 없는 값은 추정하지 않고 `null`로 남깁니다.

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
