# DaemonSet 운영 검증 체크리스트

이 문서는 node agent를 실제 Kubernetes 클러스터에 배포하기 전에 확인할 항목과, 배포 후 장애를 만들지 않기 위한 검증 순서를 정리한 것이다. agent는 노드의 `/proc`, `/sys`, `/run`, `/var/log`, `/etc`를 읽고 `hostNetwork`, `hostPID`를 사용하므로 일반 애플리케이션보다 더 신중하게 배포한다.

## 기본 원칙

- 재부팅은 하지 않는다.
- 기존 Docker 컨테이너와 Docker 네트워크를 변경하지 않는다.
- 배포 전에는 `kubectl diff`, `kubectl apply --dry-run=server`, Helm template 출력 확인을 먼저 한다.
- LLM은 진단과 설명만 담당한다. 조치 실행은 policy engine과 운영자 승인 흐름을 따른다.
- Secret에는 `cluster-id`, `agent-token`이 들어가므로 명령어 출력, CI 로그, 스크린샷에 노출하지 않는다.
- 운영 첫 배포는 전체 노드가 아니라 테스트 노드 1개에 `nodeSelector` 또는 label을 걸어 canary로 시작한다.

## 배포 전 점검

1. 클러스터 접근 권한 확인

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl auth can-i get nodes
kubectl auth can-i get --raw /readyz
kubectl auth can-i list pods --all-namespaces
```

2. 노드 런타임 확인

```bash
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
```

containerd, CRI-O, cri-dockerd, Docker 기반 클러스터가 섞일 수 있다. 기본 collector는 여러 표준 socket 경로를 자동 탐색하지만, 비표준 경로를 쓰는 플랫폼은 `CONTAINER_RUNTIME_SOCKET_PATHS`를 명시한다.

예:

```text
containerd=/run/containerd/containerd.sock,crio=/run/crio/crio.sock
```

3. Namespace와 Secret 준비

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=<cluster-id> \
  --from-literal=agent-token=<agent-token> \
  --dry-run=client -o yaml | kubectl apply -f -
```

4. 백엔드 연결성 확인

```bash
kubectl run rca-agent-connectivity-check \
  -n rca-system \
  --rm -i --restart=Never \
  --image=curlimages/curl:8.10.1 \
  -- curl -fsS <backend-url>/health
```

운영 클러스터에서 임시 Pod 생성이 부담스럽다면, 같은 네트워크 경로를 쓰는 별도 테스트 namespace에서 먼저 확인한다.

## 정적 manifest 배포

정적 manifest는 빠르게 검증할 때 사용한다. 실제 운영에서는 backend가 생성한 cluster별 manifest나 Helm chart 사용을 우선한다.

```bash
kubectl apply --dry-run=server -f manifests/agent-daemonset.yaml
kubectl diff -f manifests/agent-daemonset.yaml
kubectl apply -f manifests/agent-daemonset.yaml
```

배포 확인:

```bash
kubectl -n rca-system rollout status daemonset/cluster-infra-rca-agent
kubectl -n rca-system get pods -o wide
kubectl -n rca-system logs -l app.kubernetes.io/name=cluster-infra-rca-agent --tail=100
```

## Helm 배포

기본값은 Secret을 chart가 만들지 않고 이미 존재하는 Secret을 참조한다. 운영에서는 이 방식이 안전하다.

```bash
helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=<backend-url>

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=<backend-url>
```

canary 배포 예:

```bash
kubectl label node <node-name> cluster-infra-rca.io/agent-canary=true

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=<backend-url> \
  --set nodeSelector.cluster-infra-rca\\.io/agent-canary=true
```

비표준 runtime socket 예:

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=<backend-url> \
  --set runtimeSocketPaths='crio=/run/crio/crio.sock'
```

테스트 환경에서만 chart가 Secret을 생성하게 할 수 있다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=<backend-url> \
  --set secret.create=true \
  --set secret.clusterId=<cluster-id> \
  --set secret.agentToken=<agent-token>
```

## 운영 검증

배포 후 다음 항목을 확인한다.

- DaemonSet desired/current/ready 수가 일치하는지 확인한다.
- agent Pod가 모든 대상 노드에 1개씩 배치되는지 확인한다.
- agent 로그에 backend 인증 실패, evidence submit 실패, command timeout이 반복되는지 확인한다.
- 백엔드의 agent heartbeat 시간이 갱신되는지 확인한다.
- evidence request를 하나 만든 뒤 해당 노드의 evidence가 수집되고 RCA report가 생성되는지 확인한다.
- 수집된 evidence에 `runtime_kind`, `runtime_socket_path`, `kubernetes.node_ready`, `kernel`, `disk`, `memory`, `network`, `systemd`, `kubelet` 계열 필드가 들어오는지 확인한다.
- `containerd`, `crio`, `cri-dockerd`, `docker` 중 실제 클러스터 runtime과 맞지 않는 값이 선택되면 `runtimeSocketPaths`를 명시하고 재배포한다.

## 롤백

Helm 배포:

```bash
helm -n rca-system rollback rca-agent
helm -n rca-system uninstall rca-agent
```

정적 manifest 배포:

```bash
kubectl delete -f manifests/agent-daemonset.yaml
```

Secret과 namespace는 다른 리소스가 같이 쓰고 있을 수 있으므로 바로 삭제하지 않는다.

## Linux 검증 서버 상태

비공개 Linux 검증 서버에서 읽기 전용으로 확인한 결과는 다음과 같다. 서버 주소, 계정명, 내부 네트워크 식별자는 저장소 문서에 남기지 않는다.

- OS: Linux 6.12.8-1-default, SUSE 계열
- `kubectl`: `/usr/local/bin/kubectl`, client v1.30.0
- `kind`: `/usr/local/bin/kind`, v0.23.0
- `helm`: 시스템에는 설치되어 있지 않음. 검증 시 `/tmp`에 임시 Helm v3.15.4 바이너리를 받아 사용했고 검증 후 삭제함
- `docker`: `/usr/bin/docker`
- 현재 사용자 kubeconfig에는 context가 없음

따라서 이 서버에서 실제 클러스터 배포 검증을 하려면 kubeconfig를 먼저 제공하거나, 별도 테스트 Kubernetes 환경을 만들지 결정해야 한다. Docker 기반 `kind`나 `k3d`는 새 Docker 컨테이너와 Docker 네트워크를 만들기 때문에 기존 Docker 사용 정책을 다시 확인한 뒤 진행한다.

## 2026-06-13 kind 검증 결과

비공개 Linux 검증 서버에서 기존 Docker 컨테이너는 수정하지 않고, `kind` 테스트 클러스터 `rca-agent-validation`을 새로 만들어 DaemonSet manifest를 검증했다. 검증 후 테스트 클러스터와 임시 agent 이미지는 삭제했다.

검증 결과:

- `kind create cluster --name rca-agent-validation --wait 120s` 성공
- kind node: Kubernetes v1.30.0, containerd 1.7.15
- `kubectl apply --dry-run=server -f /tmp/rca-agent-daemonset.yaml` 성공
- 테스트 이미지 `cluster-infra-rca-agent:test` 빌드 후 kind에 로드 성공
- 실제 `kubectl apply -f` 성공
- DaemonSet rollout 성공: desired/current/ready/available 모두 1
- agent Pod 상태: `Running`, `Ready=True`, restart 0
- `hostPath` mount: `/`, `/var/log`, `/run`, `/etc`, `/proc`, `/sys` 모두 read-only mount 확인
- env: `BACKEND_URL`, `CLUSTER_ID`, `AGENT_TOKEN`, `NODE_NAME`, timeout 계열, `CONTROL_PLANE_PROBE_PORTS`, `CONTAINER_RUNTIME_SOCKET_PATHS` 주입 확인
- 로그에서는 `https://rca.example.com` 더미 backend DNS 실패가 반복됐다. 이는 실제 backend를 연결하지 않은 테스트이므로 예상된 결과다.

주의할 점:

- 같은 파일 안에 Namespace와 namespaced 리소스가 같이 있을 때 server-side dry-run은 Namespace를 실제 생성하지 않는다. dry-run 검증만 하려면 namespace를 먼저 만들거나 리소스를 단계별로 검증한다.
- Helm chart는 임시 Helm v3.15.4 바이너리로 `helm template`, `helm lint`, `secret.create=true` 렌더링 분기까지 검증했다.
