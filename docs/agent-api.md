# Agent API

## 한국어 요약

Node Agent API는 각 Kubernetes 노드에 배포된 Python Agent가 Platform에 등록하고, heartbeat를 보내고, read-only evidence 요청을 받아 결과를 제출하는 경계입니다.

현재 설계에서 Agent는 **host mutation을 실행하지 않습니다.** 예전 승인 조치 실행 API는 호환성 목적으로 남아 있지만, action polling은 빈 목록을 반환하고 action result submit은 `410 Gone`으로 차단됩니다. 운영 조치는 approval/audit/manual runbook 또는 GitOps PR 흐름으로만 처리합니다.

Agent는 `agent_token`과 노드별 `node_token`을 함께 사용합니다. 등록 시에는 bootstrap token 역할의 `agent_token`만 필요하고, 등록 후에는 backend가 발급한 `node_token`을 로컬 state directory에 저장한 뒤 heartbeat/evidence/realtime event 요청에 함께 보냅니다.

---

## English Reference

Base paths are served by the Spring Boot platform.

```text
POST /api/agents/register
POST /api/agents/heartbeat
POST /api/agents/evidence-requests
POST /api/agents/evidence-responses
POST /api/agents/realtime-events
POST /api/agents/action-executions   # deprecated compatibility endpoint
POST /api/agents/action-results      # disabled, returns 410 Gone
```

All agent endpoints are permitted by the HTTP authorization rules but are authenticated by `AgentAuthenticationFilter` before reaching controllers.

## Authentication Contract

### Register

`/api/agents/register` requires:

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_token": "cluster-bootstrap-token",
  "agent_version": "0.1.0",
  "agent_protocol_version": "1",
  "supported_collectors": ["node", "disk", "kernel", "kubelet"],
  "metadata": {
    "kernel": "6.8.0",
    "container_runtime": "containerd"
  }
}
```

The response includes a node-specific token.

```json
{
  "agent_id": "agent-...",
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_version": "0.1.0",
  "agent_protocol_version": "1",
  "status": "registered",
  "node_token": "node-specific-token"
}
```

### Heartbeat

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_token": "cluster-bootstrap-token",
  "node_token": "node-specific-token",
  "status": "healthy",
  "agent_version": "0.1.0",
  "agent_protocol_version": "1",
  "supported_collectors": ["node", "disk", "kernel"],
  "health": {
    "agent": "running",
    "ebpf": "disabled",
    "capabilities": {
      "schema_version": "1.0",
      "mode": "node-diagnostics",
      "overall_status": "limited",
      "summary": {
        "available": 11,
        "limited": 2,
        "unavailable": 0,
        "disabled": 1
      },
      "collectors": {
        "runtime": {
          "status": "limited",
          "reason": "Some prerequisites are present but incomplete.",
          "checks": ["host_run", "runtime_socket"]
        }
      }
    }
  }
}
```

Heartbeat updates:

- `last_heartbeat_at`
- agent status
- agent version
- agent protocol version
- supported collector list
- health summary
- capability self-check summary
- cluster `last_seen_at`

## Agent Protocol Versioning

The platform exposes compatibility information at:

```text
GET /api/v1/platform/info
```

Response shape:

```json
{
  "platform_version": "0.1.0",
  "api_version": "v1",
  "agent_protocol_version": "1",
  "minimum_supported_agent_protocol_version": "1",
  "minimum_supported_agent_version": "0.1.0"
}
```

Agents that omit `agent_protocol_version` are treated as protocol `1`. Unsupported versions are not immediately rejected in the current soft-compatibility mode; they appear as `version_mismatch` in Agent Health.

## Evidence Polling

Agents poll read-only evidence work with:

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_token": "cluster-bootstrap-token",
  "node_token": "node-specific-token",
  "limit": 10
}
```

The platform returns pending evidence requests assigned to the node.

## Evidence Submit

```json
{
  "request_id": "request-...",
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_token": "cluster-bootstrap-token",
  "node_token": "node-specific-token",
  "status": "completed",
  "collectors": {
    "disk": {"root_usage_percent": 96.0},
    "kernel": {"messages": ["..."]}
  },
  "error_message": null
}
```

Only `completed` and `failed` are valid response states. Submitted evidence may enqueue an RCA analysis task.

## Realtime Events

`/api/agents/realtime-events` accepts eBPF or realtime event batches. eBPF is optional and disabled by default.

## Deprecated Action Endpoints

Agent-side mutation execution has been disabled.

```text
POST /api/agents/action-executions -> []
POST /api/agents/action-results    -> 410 Gone
```

The platform preserves these endpoints only for backward compatibility. Future major versions may remove them completely.

## Error Handling

Common failures:

- `400` malformed JSON
- `401` missing or invalid agent credentials
- `403` request assigned to another agent
- `404` cluster, agent, or request not found
- `409` evidence request already closed
- `410` agent-side action result endpoint disabled
- `422` invalid evidence response status
