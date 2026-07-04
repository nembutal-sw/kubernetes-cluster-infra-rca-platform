# DaemonSet Production Validation

실제 Kubernetes 클러스터에 Agent DaemonSet을 배포하기 전후로 확인할 운영 체크리스트입니다.
이 절차는 read-only 검증만 수행합니다. Docker network, 기존 container, kubelet/containerd 재시작, 노드 재부팅은 하지 않습니다.

## 1. Preflight

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl auth can-i get nodes
kubectl auth can-i list pods --all-namespaces
kubectl auth can-i get --raw /readyz
```

Runtime 분포 확인:

```bash
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
```

## 2. Helm Render

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform

helm lint charts/cluster-infra-rca-agent --set backendUrl=https://rca.example.com
helm template rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

확인 기준:

- Secret 값이 rendered manifest에 직접 노출되지 않음
- `APPROVED_ACTIONS_ENABLED`가 `false`
- hostPath mount는 필요한 경로만 read-only
- `systemdCollectorMode=file`
- agent image repository/tag는 운영 values에서 명시

## 3. Canary Deploy

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

## 4. Read-Only Operational Check

```bash
python3 scripts/daemonset_operational_check.py \
  --namespace rca-system \
  --output validation-results/daemonset-check.json
```

검증 항목:

- DaemonSet desired/ready/available 일치
- Agent pod restart count 증가 없음
- Agent heartbeat 정상
- collector capability가 mode와 일치
- 권한 부족 collector는 Agent 프로세스를 죽이지 않고 `limited`, `unsupported`, `error`로 보고
- traceback, authentication failure, permission denied 로그 없음

## 5. Evidence Request Smoke

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
- report 생성
- `evidence_quality`와 `quality_gate`가 report에 포함
- `quality_gate.status`가 `pass` 또는 `limited`
- disruptive action은 `automation_allowed=false`
- evidence bundle export가 manifest/signature 검증 가능

## 6. Rollout

Canary 검증 후 nodeSelector를 제거해 전체 노드로 확장합니다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set backendUrl=https://rca.example.com \
  --set secret.existingSecret.name=cluster-infra-rca-agent \
  --set systemdCollectorMode=file
```

## 7. Rollback

```bash
helm -n rca-system rollback rca-agent
helm -n rca-system uninstall rca-agent
```

Rollback도 workload, Docker network, host runtime을 직접 변경하지 않습니다.
