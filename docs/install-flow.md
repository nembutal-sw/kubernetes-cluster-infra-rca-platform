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
kubectl create namespace rca-system
kubectl apply -f https://example.com/install/cluster-prod-01/agent-daemonset.yaml
```

실제 구현에서는 설치 manifest URL에 클러스터별 bootstrap token, backend endpoint, image tag, RBAC 설정을 포함합니다.

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

