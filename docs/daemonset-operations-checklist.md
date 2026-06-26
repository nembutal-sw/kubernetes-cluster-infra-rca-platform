# DaemonSet Operations Checklist

Node Agent는 클러스터 노드의 Linux/Kernel/Runtime evidence를 읽기 위해 일반 애플리케이션보다 넓은 host 접근 권한을 사용합니다. 운영 배포 전에는 반드시 read-only 검증을 먼저 수행합니다.

## Guardrails

- 운영 서버와 기존 Docker container/network는 변경하지 않습니다.
- 시스템 재부팅, kubelet/containerd 재시작, 노드 drain/cordon은 자동화 대상이 아닙니다.
- Agent는 evidence 수집만 수행합니다. 조치는 승인 기록, runbook, GitOps PR 안내로 남깁니다.
- Secret 값은 명령 출력, 스크린샷, Git commit에 남기지 않습니다.
- 첫 배포는 canary node 1대로 시작하고, Agent heartbeat와 evidence 품질을 확인한 뒤 전체 노드로 확장합니다.

## Preflight

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl auth can-i get nodes
kubectl auth can-i list pods --all-namespaces
kubectl auth can-i get --raw /readyz
```

런타임 종류도 확인합니다.

```bash
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
```

비표준 runtime socket을 쓰는 클러스터는 Helm 값으로 명시합니다.

```text
runtimeSocketPaths='containerd=/run/containerd/containerd.sock,crio=/run/crio/crio.sock'
```

## Secret

운영 환경에서는 chart가 Secret을 만들기보다 기존 Secret을 참조하는 방식을 권장합니다.

```bash
kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id=<cluster-id> \
  --from-literal=agent-token=<agent-token> \
  --dry-run=client -o yaml | kubectl apply -f -
```

## Helm Render

배포 전에 render, lint, server dry-run을 확인합니다.

```bash
helm lint charts/cluster-infra-rca-agent --set backendUrl=https://rca.example.com
helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

canary 배포 예시:

```bash
kubectl label node <node-name> cluster-infra-rca.io/agent-canary=true

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set nodeSelector.cluster-infra-rca\\.io/agent-canary=true
```

전체 노드 배포 예시:

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set mode=node-diagnostics \
  --set systemdCollectorMode=file
```

## Read-Only Validation Script

배포 후에는 다음 스크립트로 DaemonSet 상태, RBAC, hostPath, 로그 패턴을 확인합니다.

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

검증 항목:

- DaemonSet desired/ready/available 일치
- Agent Pod Ready 상태와 restart count
- ServiceAccount RBAC: nodes, pods, events, readyz
- `safe`, `node-diagnostics`, `ebpf` mode 일관성
- hostPath read-only mount 상태
- `SYSTEMD_COLLECTOR_MODE=file`
- `APPROVED_ACTIONS_ENABLED=true` 금지
- Agent 로그의 인증 실패, 권한 실패, Python traceback 패턴

## Mode Guide

| Mode | 용도 | 특징 |
| --- | --- | --- |
| `safe` | 제한된 Kubernetes API 기반 점검 | hostNetwork/hostPID/hostPath 미사용 |
| `node-diagnostics` | 일반 운영 권장 | `/proc`, `/sys`, `/run`, `/var/log`, `/etc` read-only mount |
| `ebpf` | 실시간 kernel event 실험 | BPF/PERFMON capability와 kernel path 필요 |

기본 운영은 `node-diagnostics`와 `systemdCollectorMode=file`을 권장합니다. journal/systemd 직접 접근은 배포판과 컨테이너 권한 차이가 커서 기본값으로 두지 않습니다.

## Evidence Validation

Agent 등록 후 플랫폼에서 수집 요청을 실행합니다.

```bash
curl -fsS -X POST "$RCA_BASE_URL/api/clusters/$CLUSTER_ID/collection-runs" \
  -H "Authorization: Bearer $RCA_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "confirmed": true,
    "alert_name": "NodeNotReady",
    "requested_collectors": ["node","kubernetes","systemd","runtime","kubelet","kernel","disk","inode","memory","network","conntrack"],
    "reason": "DaemonSet operational validation"
  }'
```

확인 기준:

- Agent heartbeat가 최신 상태인지 확인
- evidence request가 `completed`로 닫히는지 확인
- RCA report가 생성되는지 확인
- report에 root cause candidate, evidence path, recommended action, policy decision이 비어 있지 않은지 확인
- disruptive action은 `automation_allowed=false`, `execution_plan.executable=false`인지 확인

## Rollback

Helm 배포:

```bash
helm -n rca-system rollback rca-agent
helm -n rca-system uninstall rca-agent
```

정적 manifest 배포:

```bash
kubectl delete -f manifests/agent-daemonset.yaml
```

Secret과 namespace는 다른 리소스와 공유될 수 있으므로 즉시 삭제하지 않습니다.
