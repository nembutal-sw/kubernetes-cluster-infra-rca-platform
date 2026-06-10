# 클러스터 등록 및 Agent 설치 흐름

사용자는 Web UI에서 클러스터를 등록하고, Backend는 해당 클러스터에 맞는 Agent 설치 명령어를 제공합니다.

## 클러스터 등록

1. 사용자가 Web UI에서 새 클러스터를 생성합니다.
2. Backend가 `cluster_id`와 agent bootstrap token을 발급합니다.
3. Web UI가 설치 명령어를 표시합니다.
4. 운영자가 대상 클러스터에서 명령어를 실행합니다.
5. DaemonSet Agent가 각 노드에 배포되고 Backend에 heartbeat를 전송합니다.

## 설치 명령어 예시

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=cluster-prod-01 \
  --from-literal=agent-token=bootstrap-token \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f "https://rca.example.com/api/clusters/cluster-prod-01/agent-manifest?backend_url=https%3A%2F%2Frca.example.com&image=ghcr.io%2Facme%2Fcluster-infra-rca-agent%3Av1&namespace=rca-system"
```

현재 manifest는 `cluster-id`와 `agent-token`을 Secret에서 읽고, `BACKEND_URL`과 timeout 값은 ConfigMap에서 읽습니다. Backend는 `/api/clusters/{cluster_id}/agent-manifest`로 클러스터별 DaemonSet manifest를 생성합니다. Secret은 manifest에 포함하지 않으므로 설치 명령어에서 별도로 생성합니다.

로컬 개발에서는 repo의 `manifests/agent-daemonset.yaml`을 직접 수정해 적용할 수 있습니다. 운영 배포에서는 backend URL, image tag, namespace를 query parameter로 넘겨 manifest를 생성하는 쪽이 안전합니다.

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
            credentials: ${RCA_WEBHOOK_TOKEN}
```

## Agent 등록 상태

Web UI는 다음 상태를 표시합니다.

- cluster registered
- agent install command generated
- node agent connected
- last heartbeat
- supported collector list
- last evidence collection status
