# DaemonSet Production Validation

실제 Kubernetes 클러스터에 Node Agent DaemonSet을 배포하기 전후로 확인할 운영 체크리스트입니다.
기본 원칙은 read-only 검증입니다. Docker 네트워크, 기존 컨테이너, kubelet/containerd 재시작, 노드 재부팅은 수행하지 않습니다.

## 1. Preflight

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl auth can-i get nodes
kubectl auth can-i list pods --all-namespaces
kubectl get --raw /readyz
```

런타임 분포 확인:

```bash
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
```

권장되는 자동 점검:

```bash
python3 scripts/real-cluster-readiness-check.py \
  --agent-namespace default \
  --backend-url https://rca.example.com \
  --agent-local \
  --output validation-results/real-cluster-readiness.json
```

## 2. Helm Render

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform

helm lint charts/cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-test \
  --set secret.agentToken=agent-token

helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

확인 기준:

- Secret 값이 rendered manifest에 불필요하게 노출되지 않음
- `APPROVED_ACTIONS_ENABLED=false`
- hostPath mount는 필요한 경로만 read-only
- `systemdCollectorMode=file`
- agent image repository/tag가 운영 values에서 명시됨
- eBPF는 기본 비활성화, 별도 승인 후 canary에서만 활성화

## 3. Server Dry-Run

실제 리소스를 만들지 않고 API 서버 validation만 확인합니다.

```bash
helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace default \
  --set namespace.name=default \
  --set backendUrl=https://rca.example.com \
  --set secret.create=true \
  --set secret.clusterId=cluster-test \
  --set secret.agentToken=agent-token |
kubectl apply --dry-run=server -f -
```

## 4. Canary Deploy

먼저 노드 하나에만 배포합니다.

```bash
kubectl label node <node-name> cluster-infra-rca.io/agent-canary=true

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set nodeSelector.cluster-infra-rca\\.io/agent-canary=true \
  --set systemdCollectorMode=file
```

## 5. Read-Only Operational Check

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

검증 항목:

- DaemonSet desired/ready/available 일치
- Agent pod restart count 증가 없음
- Agent heartbeat 정상
- collector capability와 배포 mode 일치
- 권한 부족 collector는 프로세스를 죽이지 않고 `limited`, `unsupported`, `error`로 보고
- traceback, authentication failure, permission denied 로그 없음

## 6. Evidence Request Smoke

```bash
curl -fsS -X POST "$RCA_BASE_URL/api/clusters/$CLUSTER_ID/collection-runs" \
  -H "Authorization: Bearer $RCA_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "confirmed": true,
    "alert_name": "NodeNotReady",
    "requested_collectors": ["node","kubernetes","systemd","runtime","kubelet","kernel","disk","inode","memory","network","conntrack"],
    "reason": "DaemonSet production validation"
  }'
```

확인 기준:

- evidence request가 `completed`
- RCA report 생성
- `evidence_quality`와 `quality_gate` 포함
- `quality_gate.status`가 `pass` 또는 `limited`
- 위험하거나 disruptive한 action은 `automation_allowed=false`
- evidence bundle export는 manifest/signature 검증 가능

## 7. Rollout

Canary 검증 후 nodeSelector를 제거해 전체 노드로 확장합니다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set systemdCollectorMode=file
```

## 8. Rollback

```bash
helm -n rca-system rollback rca-agent
helm -n rca-system uninstall rca-agent
```

Rollback도 workload, Docker network, host runtime을 직접 변경하지 않는 범위에서 수행합니다.
