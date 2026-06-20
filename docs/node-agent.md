# Node Agent

## 한국어 요약

Node Agent는 각 Kubernetes 노드에서 Linux/Kubernetes evidence를 **read-only**로 수집하는 Python 프로세스입니다.

현재 Agent의 역할은 다음으로 제한합니다.

- backend 등록
- heartbeat 전송
- evidence request polling
- collector 실행
- evidence response 제출
- backend 장애 시 spool/retry
- optional eBPF realtime event 제출

Agent는 host 명령을 변경하거나 재시작하지 않습니다. 승인 조치 실행기는 제거되었고, Agent-side action endpoint는 더 이상 실제 실행을 제공하지 않습니다.

---

## English Reference

## Package Layout

```text
node_agent/
  main.py
  client.py
  state.py
  ebpf.py
  collectors/
    registry.py
    common.py
    node.py
    kubernetes.py
    systemd.py
    kernel.py
    disk.py
    inode.py
    memory.py
    process.py
    network.py
    conntrack.py
    runtime.py
    kubelet.py
    cni.py
    dns.py
```

## Execution Modes

### Online agent mode

The normal daemon mode registers with the backend and polls for evidence requests.

Required environment variables:

```text
BACKEND_URL
CLUSTER_ID
AGENT_TOKEN
NODE_NAME optional, defaults to hostname
```

Optional security variables:

```text
AGENT_CA_BUNDLE
AGENT_CLIENT_CERT
AGENT_CLIENT_KEY
```

### Local collection mode

For debugging or demos:

```bash
python -m node_agent.main --collect-local --collectors node,disk,kernel --output evidence.json
```

This does not register with the backend.

## Registration And Heartbeat

The agent sends:

```text
agent_version
agent_protocol_version
supported_collectors
metadata
health
```

The platform stores `agent_protocol_version` and shows compatibility state in Agent Health. Missing protocol version is treated as `1` for backward compatibility.

## Collector Principles

Collectors must remain:

- read-only
- timeout-bounded
- output-size-aware
- tolerant of missing host tools
- safe for restricted Kubernetes nodes
- easy to disable or extend later

Collector metadata should describe risk and requirements.

```json
{
  "name": "conntrack",
  "risk_level": "read_only",
  "requires_host_network": true,
  "requires_privileged": false,
  "default_timeout_seconds": 5,
  "max_output_bytes": 1048576,
  "enabled_by_default": true
}
```

## Spool And Retry

When the backend is temporarily unavailable, evidence responses are stored under `AGENT_STATE_DIR` and retried later.

The state store should enforce:

```text
file permission hardening
atomic writes
spool file count limit
spool byte limit
invalid spool quarantine
acknowledge after backend success
```

## Optional eBPF

The agent can collect realtime eBPF signals such as OOM kill, TCP retransmit, and DNS latency events. eBPF is disabled by default.

Helm values should enable eBPF explicitly:

```yaml
ebpf:
  enabled: true
  legacySysAdmin: false
```

Only eBPF mode requires additional Linux capabilities. Approved actions no longer add `SYS_ADMIN`, `SYS_PTRACE`, or `SYS_CHROOT`.

## Removed Mutation Execution

The previous approved-action executor has been removed.

Current behavior:

```text
No node reboot
No systemctl restart
No kubectl delete/drain
No shell command execution
No host mutation from the agent
```

RCA recommendations remain report/runbook guidance. Operators execute changes outside the agent and then mark action requests as manually completed in the platform.

## Collector List

Typical collectors:

```text
node
kubernetes
systemd
kernel
disk
inode
memory
process
network
conntrack
runtime
kubelet
cni
dns
ebpf optional
```

## Testing

Run local Python validation:

```bash
python -m compileall node_agent tests
pytest
```

Recommended test focus:

- collector parsing
- missing host command behavior
- spool retry
- invalid spool file handling
- mTLS client configuration
- agent protocol version propagation
