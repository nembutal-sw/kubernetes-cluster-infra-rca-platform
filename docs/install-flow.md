# 클러스터 등록 및 Agent 설치 흐름

사용자는 Web UI에서 클러스터를 등록하고, Backend는 해당 클러스터에 맞는 Agent 설치 명령어를 제공합니다.

## 클러스터 등록

1. 사용자가 Web UI에서 새 클러스터를 생성합니다.
2. Backend가 `cluster_id`와 Agent bootstrap token을 발급합니다.
3. Web UI가 설치 명령어를 표시합니다.
4. 운영자가 대상 클러스터에서 명령어를 실행합니다.
5. DaemonSet Agent가 각 노드에 배포되고 Backend에 heartbeat를 전송합니다.

## 설치 명령어 예시

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=cluster-prod-01 \
  --from-literal=agent-token='<bootstrap-token>' \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f "https://rca.example.com/api/clusters/cluster-prod-01/agent-manifest?backend_url=https%3A%2F%2Frca.example.com&image=ghcr.io%2Facme%2Fcluster-infra-rca-agent%3Av1&namespace=rca-system&agent_mode=safe&manifest_token=<one-time-token>"
```

현재 manifest는 `cluster-id`와 `agent-token`을 Secret에서 읽고, `BACKEND_URL`과 timeout
값은 ConfigMap에서 읽습니다. Backend는 설치 명령을 만들 때 짧은 유효시간의
`manifest_token`을 발급하며, 이 token은 manifest를 한 번 내려받으면 재사용할 수 없습니다.
Bootstrap token은 manifest URL 인증에 사용할 수 없습니다.

Agent protocol v2에서 bootstrap token은 최초 노드 등록에만 사용하고 기본 30분 후 만료됩니다.
등록이 끝난 Agent는 node-scoped token만 사용합니다. TTL이 지난 뒤 노드를 증설하거나 Agent state가
초기화된 경우 Web Console에서 bootstrap token을 회전하고 Kubernetes Secret을 갱신한 다음
DaemonSet Pod를 재생성합니다.

운영 환경에서는 HTTPS URL만 사용합니다. 기본 `agent_mode=safe`는 host namespace와
hostPath를 사용하지 않습니다. Linux node 진단이 필요할 때만 `node-diagnostics`, eBPF가
필요할 때만 `ebpf`를 명시적으로 선택합니다.

## Alertmanager webhook 예시

```yaml
receivers:
  - name: cluster-infra-rca
    webhook_configs:
      - url: https://rca.example.com/api/webhooks/alertmanager
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials_file: /etc/alertmanager/secrets/rca-webhook-token
```

`credentials_file`에는 Backend의 `RCA_WEBHOOK_TOKEN`과 같은 값을 넣습니다. 다른 webhook sender를 붙일 때는 `X-Webhook-Token: <RCA_WEBHOOK_TOKEN>` header도 허용합니다.

## Agent 등록 상태

Web UI는 다음 상태를 표시합니다.

- cluster registered
- agent install command generated
- node agent connected
- last heartbeat
- supported collector list
- last evidence collection status
