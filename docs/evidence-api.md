# Evidence API

Evidence API는 Backend가 특정 노드 Agent에게 수집 요청을 만들고, Agent가 수집 결과를 반환하는 계약입니다.

현재 구현은 push 방식이 아니라 poll 방식입니다. Backend가 request를 만들면 Agent가 주기적으로 pending request를 조회하고 결과를 submit합니다.

## Backend: evidence request 생성

`POST /api/evidence/requests`

요청:

```json
{
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "alert_name": "NodeNotReady",
  "requested_collectors": ["systemd", "runtime", "network"],
  "time_range": {
    "from": "2026-06-10T09:10:00+09:00",
    "to": "2026-06-10T09:25:00+09:00"
  },
  "reason": "NodeNotReady fired",
  "context": {
    "severity": "critical"
  }
}
```

조건:

- cluster가 없으면 `404`
- 대상 node agent가 등록되어 있지 않으면 `404`

## Agent: pending request 조회

`POST /api/agents/evidence-requests`

요청:

```json
{
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "agent_token": "bootstrap-token",
  "limit": 10
}
```

응답은 해당 Agent에게 할당된 `pending` evidence request 목록입니다.

## Agent: 수집 결과 제출

`POST /api/agents/evidence-responses`

성공 응답:

```json
{
  "request_id": "evidence-request-12345678",
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "agent_token": "bootstrap-token",
  "status": "completed",
  "collectors": {
    "systemd": {
      "kubelet_status": "active"
    },
    "runtime": {
      "containerd_socket_healthy": true
    }
  }
}
```

실패 응답:

```json
{
  "request_id": "evidence-request-12345678",
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "agent_token": "bootstrap-token",
  "status": "failed",
  "error_message": "journalctl timed out"
}
```

제약:

- `status`는 `completed` 또는 `failed`만 허용합니다.
- request가 다른 node에 할당되어 있으면 `403`
- 이미 닫힌 request면 `409`
- 성공 응답은 `evidence_bundles` row를 만들고 request에 `evidence_id`를 연결합니다.

## 조회

클러스터 evidence request 목록:

```text
GET /api/clusters/{cluster_id}/evidence-requests
```

단일 evidence request:

```text
GET /api/evidence/requests/{request_id}
```

저장된 evidence bundle:

```text
GET /api/evidence/{evidence_id}
```

