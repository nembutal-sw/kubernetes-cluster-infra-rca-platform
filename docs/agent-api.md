# Agent API

Node Agent는 DaemonSet으로 각 노드에 배포되고 Backend에 등록 및 heartbeat를 보냅니다.

현재 API는 실제 evidence streaming 전에 Agent identity와 상태 흐름을 고정하기 위한 MVP입니다.

현재 repo에는 `node_agent` Python 패키지로 Agent MVP가 들어 있습니다. 실행 진입점은 아래와 같습니다.

```powershell
python -m node_agent.main --once
```

DaemonSet에서는 같은 모듈을 장기 실행 프로세스로 실행합니다.

Backend 연결 없이 실제 Linux 노드에서 collector만 확인하려면 local collect 모드를 사용합니다.

```bash
python -m node_agent.main --collect-local --output /tmp/cluster-infra-rca-evidence.json
```

상세 절차는 [docs/linux-node-collector-validation.md](linux-node-collector-validation.md)를 참고합니다.

## Agent 환경변수

| 이름 | 설명 |
| --- | --- |
| `BACKEND_URL` | Backend API 주소 |
| `CLUSTER_ID` | 등록된 클러스터 ID |
| `AGENT_TOKEN` | 클러스터 bootstrap token |
| `NODE_NAME` | Kubernetes node name |
| `POLL_INTERVAL_SECONDS` | evidence request poll 주기, 기본 15초 |
| `HTTP_TIMEOUT_SECONDS` | Backend API 요청 timeout, 기본 10초 |
| `COMMAND_TIMEOUT_SECONDS` | 로컬 수집 명령 timeout, 기본 5초 |

hostPath 기본값:

| 이름 | 기본값 |
| --- | --- |
| `HOST_ROOT` | `/host/root` |
| `HOST_PROC` | `/host/proc` |
| `HOST_SYS` | `/host/sys` |
| `HOST_ETC` | `/host/etc` |
| `HOST_VAR_LOG` | `/host/var/log` |
| `HOST_RUN` | `/host/run` |

## 인증

Agent 등록 요청은 클러스터 등록 시 발급된 `bootstrap_token`을 `agent_token` 필드로 보냅니다.

등록이 성공하면 Backend는 해당 `cluster_id + node_name`에만 사용할 수 있는 `node_token`을 발급합니다.
이 값은 등록 응답에서만 raw 값으로 내려가고, Backend DB에는 hash만 저장합니다.

Heartbeat, evidence poll, evidence submit 요청은 `agent_token`과 `node_token`을 모두 보내야 합니다.

- `agent_token`이 틀리면 `401`
- 등록되지 않은 node면 `404`
- `node_token`이 해당 node와 맞지 않으면 `401`

## Register

`POST /api/agents/register`

요청:

```json
{
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "agent_token": "bootstrap-token",
  "agent_version": "0.1.0",
  "supported_collectors": ["systemd", "disk", "network", "kubelet"],
  "metadata": {
    "kernel": "6.8.0",
    "runtime": "containerd"
  }
}
```

응답:

```json
{
  "agent_id": "agent-12345678",
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "node_token": "node-specific-token",
  "agent_version": "0.1.0",
  "status": "registered",
  "supported_collectors": ["systemd", "disk", "network", "kubelet"],
  "metadata": {
    "kernel": "6.8.0",
    "runtime": "containerd"
  },
  "health": {},
  "registered_at": "2026-06-10T00:00:00Z",
  "last_heartbeat_at": null
}
```

동작:

- `cluster_id`가 없으면 `404`
- `agent_token`이 틀리면 `401`
- 같은 `cluster_id + node_name`으로 다시 등록하면 기존 Agent row를 갱신하고 `node_token`을 재발급
- 클러스터 상태를 `active`로 변경

## Heartbeat

`POST /api/agents/heartbeat`

요청:

```json
{
  "cluster_id": "cluster-12345678",
  "node_name": "worker-3",
  "agent_token": "bootstrap-token",
  "node_token": "node-specific-token",
  "status": "healthy",
  "agent_version": "0.1.1",
  "supported_collectors": ["systemd", "disk", "network", "kubelet"],
  "health": {
    "kubelet": "active",
    "containerd": "active"
  }
}
```

동작:

- 등록되지 않은 Agent면 `404`
- `last_heartbeat_at` 갱신
- Agent status, version, collector list, health summary 갱신
- 클러스터 `last_seen_at` 갱신

## 조회

클러스터의 Agent 목록:

```text
GET /api/clusters/{cluster_id}/agents
```

특정 노드 Agent:

```text
GET /api/clusters/{cluster_id}/agents/{node_name}
```

## Agent status

- `registered`
- `healthy`
- `degraded`
- `offline`

`offline` 판정은 아직 자동 계산하지 않습니다. 다음 단계에서 scheduler 또는 monitor job이 `last_heartbeat_at` 기준으로 갱신해야 합니다.

## Evidence poll/submit

Agent는 수집 요청을 받기 위해 pending evidence request를 poll합니다.

```text
POST /api/agents/evidence-requests
```

수집이 끝나면 결과를 제출합니다.

```text
POST /api/agents/evidence-responses
```

자세한 요청/응답 형식은 [docs/evidence-api.md](evidence-api.md)를 참고합니다.
