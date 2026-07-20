# Real Cluster Validation

실제 Kubernetes 클러스터에서 플랫폼과 Node Agent를 검증할 때 사용하는 절차입니다.
검증은 기본적으로 read-only입니다. 리소스 생성은 Helm/Kubectl server dry-run까지만 수행합니다.

## Quick Check

클러스터 접근, 노드 상태, 이벤트, Helm render, Agent manifest server dry-run을 한 번에 확인합니다.

```bash
python3 scripts/real-cluster-readiness-check.py \
  --agent-namespace default \
  --backend-url https://rca.example.com \
  --output validation-results/real-cluster-readiness.json
```

실제 Linux 노드에서 host collector까지 같이 확인하려면:

```bash
sudo -E python3 scripts/real-cluster-readiness-check.py \
  --agent-namespace default \
  --backend-url https://rca.example.com \
  --agent-local \
  --output validation-results/real-cluster-readiness.json
```

`--agent-local`은 다음 collector를 실행합니다.

- `node`, `kubernetes`, `systemd`, `runtime`, `kubelet`
- `kernel`, `network`, `conntrack`, `disk`, `inode`, `memory`
- `process`, `cni`, `dns`

`kubernetes` collector는 DaemonSet 환경 밖에서는 in-cluster ServiceAccount가 없어서 `api_error`를 반환할 수 있습니다.
이 경우에도 전체 agent local collect는 실패가 아니라 제한 신호로 취급합니다.

## Prometheus Operator Delivery Canary

Operator가 `PrometheusRule`과 `AlertmanagerConfig`를 실제로 선택하는지 확인하려면
고유 namespace canary를 실행합니다. 기본 실행은 cluster를 변경하지 않습니다.

```bash
scripts/prometheus-operator-delivery-e2e.sh
```

실제 검증은 현재 context를 명시적으로 확인해야 합니다.

```bash
context="$(kubectl config current-context)"
scripts/prometheus-operator-delivery-e2e.sh \
  --apply \
  --confirm-context "${context}" \
  --selector-label release=monitoring
```

`--selector-label`은 대상 Prometheus와 Alertmanager의 resource selector에 맞춥니다.
스크립트는 기존 namespace 사용을 거부하고, 자신이 생성한 ownership label이
일치할 때만 namespace를 정리합니다. 운영 리소스를 수정하지 않습니다.

2026-07-21 GitHub Actions의 격리된 Kind 클러스터에서 고정 버전
`kube-prometheus-stack`을 설치해 Operator selector/reconciliation, runbook URL,
Bearer 인증과 firing/resolved webhook 전달을 검증했습니다. 이 결과는 CI 기준선이며,
운영 클러스터에서는 실제 selector와 보안 정책으로 canary를 다시 실행해야 합니다.

## Output

스크립트는 JSON 리포트를 생성합니다.

주요 필드:

- `status`: `passed`, `warning`, `failed`
- `checks`: kubectl 접근, node/pod/event 상태, Helm lint, server dry-run, agent local collect 결과
- `signals.nodes`: Ready/Pressure 조건, 런타임, 커널, OS 정보
- `signals.cluster_compatibility`: 배포판, 런타임, CNI, 아키텍처 fingerprint와 검증 등급
- `signals.events`: RCA와 관련 있는 최근 warning 이벤트
- `signals.agent_local_collect`: disk/inode/conntrack/runtime/kernel/systemd 요약
- `warnings`: 운영자가 판단해야 하는 제한 또는 이상 신호
- `failures`: 배포 전 반드시 해결해야 하는 실패

## Previous Real-Cluster Findings

초기 RKE2 기반 클러스터에서 확인한 신호:

- 일부 노드에서 control-plane peer 포트 연결 실패
- RKE2 node certificate expiration warning 이벤트 반복
- 특정 노드의 Cilium agent restart count 증가
- Metrics API가 일부 노드에서 `<unknown>` 반환
- 로컬 disk, inode, memory, conntrack는 샘플 시점에 정상

2026-07-15 compatibility 검증 결과:

- RKE2 5-node: containerd, Cilium, amd64/arm64 혼합 구성 탐지
- RKE2 ARM64 Agent E2E 완료, amd64 profile은 추가 canary 필요
- K3s 1-node: openSUSE, containerd, embedded Flannel, amd64 탐지 및 Agent E2E 완료
- kubeadm, EKS, AKS, GKE, OpenShift는 fixture 탐지만 완료했으며 실제 지원 완료로 표시하지 않음

2026-07-21 현재 `main` 기준 RKE2 ARM64/amd64 검증 결과:

- ARM64 `core-a`와 amd64 `edbe-b` 단일 노드 canary에서 Agent 등록과 heartbeat 정상
- node, Kubernetes, systemd, runtime, kubelet, kernel, network, conntrack, disk,
  inode, memory, process, CNI, DNS collector 요청 완료
- RCA report, incident, evidence bundle 5개 항목의 SHA-256 무결성 검증 통과
- 파일 기반 systemd/kubelet 로그가 없는 노드에서는 해당 collector가 `limited`로 보고됨
- 기존 Rancher `ext.cattle.io/v1` stale discovery로 namespace 정상 삭제가 지연됨
- namespace 콘텐츠가 비어 있고 ownership이 일치함을 확인한 후 canary namespace만
  finalize했으며, 임시 RBAC, Platform cluster, 프로세스, 포트와 파일을 모두 제거함
- amd64 노드에 새로 받은 Python canary 이미지도 digest를 확인한 후 제거함

이 관찰을 바탕으로 다음 collector와 rule이 보강되었습니다.

- Kubernetes API collector
- node condition, pressure, pod restart, CNI restart, metrics API, readyz
- node certificate warning, control-plane peer TCP probe
- RKE2 `rke2-server`, `rke2-agent` systemd 상태
- control-plane peer connectivity, CNI restart, metrics unavailable, API readyz failure, node certificate warning rule

## Safe Boundary

실클러스터 검증 중 금지 사항:

- 노드 재부팅
- kubelet/containerd/crio/docker 재시작
- Docker network 변경
- 운영 workload 삭제 또는 재배포
- 자동 조치 실행 활성화

`APPROVED_ACTIONS_ENABLED`는 운영 검증에서도 기본 `false`를 유지합니다.
조치가 필요하면 runbook 또는 GitOps PR 흐름으로 안내하고, 실행 여부는 사람이 별도로 판단합니다.
