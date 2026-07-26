# Node Agent

## 한국어 요약

Node Agent는 각 Kubernetes 노드에서 Linux/Kubernetes evidence를 **read-only**로 수집하는 Python 프로세스입니다.

현재 Agent의 책임은 다음으로 제한됩니다.

- Platform 등록
- heartbeat 전송
- read-only evidence 수집
- evidence response spool/retry
- optional eBPF realtime event 제출
- agent version/protocol version 보고

Agent는 운영 환경을 직접 변경하지 않습니다. 승인 조치 실행 모듈은 제거되었고, action workflow는 Platform의 manual approval/audit 절차로 처리됩니다.

---

## English Reference

## Runtime Modes

### Permission Modes

`AGENT_MODE` controls collector availability and Kubernetes permissions.

```text
safe
node-diagnostics
ebpf
```

`safe` is the default. Details are documented in
[Agent Permission Model](agent-permission-model.md).

### Daemon Mode

The default mode registers the agent and continuously polls the platform.

```bash
python -m node_agent.main
```

### One-shot Mode

```bash
python -m node_agent.main --once
```

### Local Collection Mode

Collect evidence locally without backend registration.

```bash
python -m node_agent.main --collect-local --collectors node,disk,kernel --output evidence.json
```

### Capability Self-Check

Run a lightweight host access check without backend registration.

```bash
python -m node_agent.main --capability-check --output capabilities.json
```

The same report is sent in every heartbeat as `health.capabilities`.
It summarizes:

- enabled collectors that are `available`, `limited`, `unavailable`, or `disabled`
- host path readability for `/proc`, `/sys`, `/run`, `/var/log`, `/etc`, and `/`
- Kubernetes ServiceAccount API prerequisites
- runtime socket discovery
- conntrack, CNI, systemd file-mode, and optional eBPF prerequisites

Use this before or after DaemonSet rollout to confirm whether an Agent can collect enough evidence for RCA.

## Collector Package

Collectors are organized as a package and exposed through a registry.

```text
node_agent/collectors/
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

Collector metadata can describe risk, timeout, output size, and privilege needs.

## State And Spool

The agent stores node token and unsent evidence responses under `AGENT_STATE_DIR`.

Default:

```text
/tmp/cluster-infra-rca-agent
```

Important properties:

- node token is stored locally after registration with `0600` permissions
- active and pending node tokens are persisted with atomic write, file `fsync`, rename, and directory `fsync`
- pending rotation survives restart and is committed only after a successful heartbeat
- rejected pending credentials roll back to the previous active token
- evidence responses are spooled before submit
- successful submit acknowledges and removes spool entry
- closed requests can be acknowledged and discarded
- retry uses backoff to avoid tight failure loops

## Environment Variables

```text
BACKEND_URL
CLUSTER_ID
AGENT_TOKEN
NODE_NAME
AGENT_STATE_DIR
POLL_INTERVAL_SECONDS
AGENT_NODE_TOKEN_ROTATION_DAYS
AGENT_NODE_TOKEN_ROTATION_RETRY_SECONDS
AGENT_MAX_SPOOL_FILES
AGENT_MAX_SPOOL_BYTES
AGENT_MODE
AGENT_EVIDENCE_MAX_BYTES
KUBERNETES_API_CACHE_TTL_SECONDS
HOST_LOG_MAX_FILES
HOST_LOG_MAX_BYTES_PER_FILE
HOST_LOG_MAX_LINES
AGENT_CA_BUNDLE
AGENT_CLIENT_CERT
AGENT_CLIENT_KEY
EBPF_ENABLED
EBPF_EVENT_QUEUE_SIZE
```

## Agent Protocol

The agent reports:

```text
agent_version
agent_protocol_version
supported_collectors
health
```

Current protocol is defined by `node_agent.AGENT_PROTOCOL_VERSION` and exposed by the platform through `/api/v1/platform/info`.

Unsupported versions are shown as `version_mismatch` in Agent Health. The current compatibility mode is soft classification, not hard rejection.

## eBPF Events

eBPF collection is optional and disabled by default. When enabled, realtime events are submitted through:

```text
POST /api/agents/realtime-events
```

eBPF may require additional Linux capabilities depending on kernel and environment.

## Removed Action Execution

The agent no longer executes approved actions.

- no action executor is created in the main loop
- no host mutation command is run by the agent
- old action polling endpoint receives no executable work
- action result submission is disabled server-side

This keeps the agent focused on evidence collection.

## Security Notes

- Use TLS for backend communication.
- Use `AGENT_CA_BUNDLE` for private CA environments.
- Use `AGENT_CLIENT_CERT` and `AGENT_CLIENT_KEY` together for mTLS.
- Do not place bootstrap tokens in logs.
- Keep state directory permissions restrictive.
- Keep the Platform opaque-token key ring identical across replicas. Rotate it with the [rolling key runbook](opaque-token-key-rotation.md), never by replacing the pepper in one step.

## Portfolio Message

> Node Agent는 장애 조치를 수행하는 자동 복구 에이전트가 아니라, Kubernetes node와 Linux system evidence를 안전하게 수집하는 read-only agent입니다.
