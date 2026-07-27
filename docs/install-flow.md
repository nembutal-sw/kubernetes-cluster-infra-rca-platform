# 클러스터 등록 및 Agent 설치 흐름

사용자는 Web Console에서 클러스터를 등록하고, Platform이 해당 클러스터의 enrollment mode와 Agent
권한 mode에 맞는 설치 명령을 생성합니다. Console이 생성한 명령을 사용하는 방식이 기본입니다.

## 클러스터 등록

1. 사용자가 Web UI에서 새 클러스터를 생성합니다.
2. `bootstrap-token` 또는 `kubernetes-token-review` enrollment mode를 선택합니다.
3. `safe`, `node-diagnostics`, `ebpf` 중 Agent 권한 mode를 선택합니다.
4. Platform이 짧은 TTL의 1회용 manifest token을 포함한 설치 명령을 생성합니다.
5. 운영자가 대상 클러스터에서 명령을 실행합니다.
6. DaemonSet Agent가 노드 identity를 등록하고 node-scoped token으로 heartbeat를 전송합니다.

## Bootstrap Token 설치 예시

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=cluster-prod-01 \
  --from-literal=agent-token='<bootstrap-token>' \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f "https://rca.example.com/api/clusters/cluster-prod-01/agent-manifest?backend_url=https%3A%2F%2Frca.example.com&image=ghcr.io%2Facme%2Fcluster-infra-rca-agent%3Av1&namespace=rca-system&agent_mode=safe&manifest_token=<one-time-token>"
```

bootstrap manifest는 `cluster-id`와 등록 전용 `agent-token`을 Secret에서 읽고,
`BACKEND_URL`과 timeout은 ConfigMap에서 읽습니다. `manifest_token`은 manifest를 한 번
내려받으면 재사용할 수 없으며 bootstrap token은 manifest URL 인증에 사용할 수 없습니다.

Agent protocol v2에서 bootstrap token은 최초 노드 등록에만 사용하고 기본 30분 후 만료됩니다.
등록이 끝난 Agent는 node-scoped token만 사용합니다. TTL이 지난 뒤 노드를 증설하거나 Agent state가
초기화된 경우 Web Console에서 bootstrap token을 회전하고 Kubernetes Secret을 갱신한 다음
DaemonSet Pod를 순차 재생성합니다.

## Kubernetes TokenReview 설치

이 모드는 Agent token Secret을 배포하지 않습니다.

1. Platform에 Kubernetes API endpoint, CA, 별도 reviewer credential을 설정합니다.
2. Console에서 profile을 `staged`로 저장합니다.
3. 전용 enrollment audience, ServiceAccount subject·UID, namespace, 필수 label을 설정합니다.
4. Agent Helm chart를 먼저 배포합니다.
5. DaemonSet UID와 허용 image digest를 profile에 바인딩해 활성화합니다.
6. canary node의 register, heartbeat, workload identity를 확인한 뒤 전체 노드로 확장합니다.

Agent projected token은 `cluster-infra-rca-agent-enrollment` 같은 전용 audience를 사용해야 하며
Kubernetes API audience와 겹치면 profile 저장, Platform 기동, Helm 렌더링이 거부됩니다.
TokenReview와 Pod 조회는 Agent가 아닌 Platform의 reviewer credential로 수행합니다.

기존 cluster를 전용 audience로 전환할 때는 [Agent Enrollment Upgrade](agent-enrollment-upgrade.md)의
Agent 선배포, one-shot allowlist migration, cluster별 canary, 최종 audit, Platform upgrade 순서를
따릅니다. Platform Helm upgrade의 audit-only pre-upgrade hook은 위험 profile을 찾으면 rollout 전에
배포를 중단합니다.

운영 환경에서는 HTTPS URL만 사용합니다. 기본 `agent_mode=safe`는 host namespace와
hostPath를 사용하지 않습니다. Linux node 진단이 필요할 때만 `node-diagnostics`, eBPF가
필요할 때만 `ebpf`를 명시적으로 선택합니다.

설치 후 Console에서 node identity, Agent protocol, enrollment profile version, heartbeat,
collector capability를 확인합니다. node token은 기본 30일마다 자동 회전하며 bootstrap credential을
다시 사용하지 않습니다.

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
