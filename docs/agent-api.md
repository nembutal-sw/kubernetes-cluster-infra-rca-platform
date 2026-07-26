# Agent API

## 한국어 요약

Node Agent API는 각 Kubernetes 노드에 배포된 Python Agent가 Platform에 등록하고, heartbeat를 보내고, read-only evidence 요청을 받아 결과를 제출하는 경계입니다.

현재 설계에서 Agent는 **host mutation을 실행하지 않습니다.** 예전 승인 조치 실행 API는 호환성 목적으로 남아 있지만, action polling은 빈 목록을 반환하고 action result submit은 `410 Gone`으로 차단됩니다. 운영 조치는 approval/audit/manual runbook 또는 GitOps PR 흐름으로만 처리합니다.

Agent protocol v2는 enrollment identity와 node token의 수명을 분리합니다. 최초 등록은 짧은 TTL의
bootstrap token 또는 projected ServiceAccount token을 사용합니다. 등록 후에는 backend가 발급한
node token만 로컬 state directory에 저장해 heartbeat/evidence/realtime 요청의 Bearer credential로
사용합니다. 자세한 신뢰 경계와 전환 절차는 [Agent Enrollment](agent-enrollment.md)를 참고합니다.

---

## English Reference

Base paths are served by the Spring Boot platform.

```text
POST /api/agents/register
POST /api/agents/heartbeat
POST /api/agents/evidence-requests
POST /api/agents/evidence-responses
POST /api/agents/realtime-events
POST /api/agents/token/rotate
POST /api/agents/action-executions   # deprecated compatibility endpoint
POST /api/agents/action-results      # disabled, returns 410 Gone
```

All agent endpoints are permitted by the HTTP authorization rules but are authenticated by `AgentAuthenticationFilter` before reaching controllers.

## Authentication Contract

### Register

`/api/agents/register` requires one of these headers:

```text
X-RCA-Agent-Enrollment: bootstrap-token
Authorization: Bearer <bootstrap-token>
```

```text
X-RCA-Agent-Enrollment: kubernetes-token-review
Authorization: Bearer <projected-service-account-token>
```

The missing enrollment header defaults to `bootstrap-token` for rolling compatibility. The
TokenReview path rejects legacy body credentials. Both paths use the same registration payload:

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "agent_version": "0.1.0",
  "agent_protocol_version": "2",
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
  "agent_protocol_version": "2",
  "status": "registered",
  "node_token": "node-specific-token"
}
```

### Heartbeat

Registration 이후 요청은 `Authorization: Bearer <node-token>`을 사용합니다.

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "status": "healthy",
  "agent_version": "0.1.0",
  "agent_protocol_version": "2",
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
  "agent_protocol_version": "2",
  "minimum_supported_agent_protocol_version": "1",
  "minimum_supported_agent_version": "0.1.0",
  "export_security": {
    "max_bundle_bytes": 10485760,
    "hash_algorithm": "SHA-256",
    "bundle_signature_enabled": false,
    "bundle_signature_algorithm": "none",
    "bundle_signature_key_id": "",
    "offline_verifier": "scripts/verify_evidence_bundle.py"
  }
}
```

Agents that omit `agent_protocol_version` are treated as protocol `1`. Unsupported versions are not immediately rejected in the current soft-compatibility mode; they appear as `version_mismatch` in Agent Health.

Protocol v1 body credentials remain temporarily accepted during migration. If an Authorization header and a legacy body credential are both present but differ, the request is rejected. Protocol v2 never sends credentials in JSON.

## Evidence Polling

Agents poll read-only evidence work with:

```text
Authorization: Bearer <node-token>
```

```json
{
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
  "limit": 10
}
```

The platform returns pending evidence requests assigned to the node.

## Evidence Submit

Header: `Authorization: Bearer <node-token>`

```json
{
  "request_id": "request-...",
  "cluster_id": "cluster-1",
  "node_name": "worker-1",
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

## Token Lifecycle

- Bootstrap token TTL: `RCA_AGENT_BOOTSTRAP_TOKEN_TTL_SECONDS`, default `1800`
- Admin rotation: `POST /api/clusters/{cluster_id}/agent-token/rotate`
- Admin bootstrap revoke: `POST /api/clusters/{cluster_id}/agent-token/revoke`
- Node self-rotation: `POST /api/agents/token/rotate` (10분 pending token 발급, 새 token의 첫 인증 성공 시 승격)
- Admin node revoke: `POST /api/clusters/{cluster_id}/agents/{node_name}/token/revoke`

Agent는 `AGENT_NODE_TOKEN_ROTATION_DAYS`(기본 30일)가 지나면 self-rotation API를 호출합니다. 실패 시 `AGENT_NODE_TOKEN_ROTATION_RETRY_SECONDS`(기본 3600초) 동안 재시도를 제한합니다. 새 token은 state file에 먼저 원자적으로 기록하고, 다음 heartbeat가 성공한 뒤 active token으로 승격합니다. 그 사이 프로세스가 재시작되면 pending token을 우선 사용하며, pending token이 거부되면 기존 active token으로 되돌립니다. `AGENT_NODE_TOKEN_ROTATION_DAYS=0`이면 자동 교체를 비활성화합니다.

검증 중인 pending token만 거부되면 Agent는 이전 active token으로 복구합니다. 현재 active node token 자체가 거부되면 bootstrap token을 자동 재사용하지 않습니다. 이 경우 로컬 node identity를 삭제하고 종료하며, 운영자가 bootstrap token을 회전하고 Agent Secret/Pod를 갱신해 명시적으로 재등록해야 합니다.

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
