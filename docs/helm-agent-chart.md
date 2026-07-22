# Agent Helm Chart

`charts/cluster-infra-rca-agent`는 node agent DaemonSet을 운영 환경에 맞게 설치하기 위한 Helm chart다. 정적 manifest와 백엔드 manifest 생성 API가 같은 필드를 사용하도록 맞춰져 있다.

## 필수 값

`backendUrl`은 반드시 지정한다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com
```

기본 `bootstrap-token` mode에서는 chart가 Secret을 생성하지 않는다. 백엔드에서 받은 cluster별
token으로 먼저 Secret을 만든다.

```bash
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=<cluster-id> \
  --from-literal=agent-token=<agent-token> \
  --dry-run=client -o yaml | kubectl apply -f -
```

## 주요 값

| 값 | 기본값 | 설명 |
| --- | --- | --- |
| `image.repository` | `ghcr.io/example/cluster-infra-rca-agent` | agent 이미지 repository |
| `image.tag` | `latest` | agent 이미지 tag |
| `backendUrl` | `""` | backend API URL |
| `enrollment.mode` | `bootstrap-token` | `bootstrap-token` 또는 `kubernetes-token-review` |
| `enrollment.audience` | `""` | TokenReview와 projected token audience |
| `enrollment.tokenExpirationSeconds` | `3600` | projected token lifetime, 600~86400초 |
| `secret.create` | `false` | chart가 agent Secret을 만들지 여부 |
| `secret.existingSecret.name` | `cluster-infra-rca-agent` | 기존 Secret 이름 |
| `runtimeSocketPaths` | `""` | 비표준 CRI socket 경로 override |
| `hostNetwork` | `true` | API/control-plane probe를 위해 host network 사용 |
| `hostPID` | `true` | 노드 프로세스 상태 확인용 host PID 사용 |
| `tolerations` | `Exists` | control-plane, tainted node까지 수집 대상 포함 |
| `nodeSelector` | `{}` | canary나 특정 node pool 제한 |

## Kubernetes TokenReview 등록

TokenReview profile을 Platform에 먼저 저장한 뒤 cluster ID만 포함한 Secret으로 설치한다.

```bash
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=<cluster-id> \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set enrollment.mode=kubernetes-token-review \
  --set enrollment.audience=https://kubernetes.default.svc \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

이 mode에서는 `agent-token` Secret key를 렌더링하지 않습니다. audience는 대상 API Server가
수락하는 값이어야 하며, Agent ServiceAccount에는 TokenReview 생성 권한이 추가됩니다. 세부 보안
경계는 [Agent Enrollment](agent-enrollment.md)를 참고합니다.

## Canary 설치

```bash
kubectl label node <node-name> cluster-infra-rca.io/agent-canary=true

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set nodeSelector.cluster-infra-rca\\.io/agent-canary=true
```

검증이 끝나면 nodeSelector를 제거하고 다시 배포한다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com
```

## Runtime socket override

기본 collector는 containerd, RKE2/K3s, K0s, MicroK8s, CRI-O, cri-dockerd, Docker의 표준 socket 후보를 탐색한다. 플랫폼이 다른 경로를 쓰면 `runtimeSocketPaths`를 지정한다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set runtimeSocketPaths='containerd=/run/containerd/containerd.sock,crio=/run/crio/crio.sock'
```

## Secret 생성 옵션

테스트 클러스터에서는 chart가 Secret을 만들게 할 수 있다. 운영에서는 설치 명령 기록에 토큰이 남을 수 있으므로 기존 Secret 참조 방식을 권장한다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=<cluster-id> \
  --set secret.agentToken=<agent-token>
```

## 검증 명령

```bash
helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com

kubectl -n rca-system rollout status daemonset/rca-agent-cluster-infra-rca-agent
kubectl -n rca-system logs -l app.kubernetes.io/instance=rca-agent --tail=100
```

2026-06-13 기준으로 비공개 Linux 검증 서버에서 임시 Helm v3.15.4 바이너리로 다음 검증을 통과했다.

- `helm template`
- `helm lint`
- `secret.create=true` 분기 렌더링
