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

## Managed Cluster Canary Workflow

EKS, AKS, GKE, OpenShift 검증은 수동 `Managed Cluster Canary` workflow를 사용합니다. Workflow는 선택한 플랫폼과 실제 fingerprint가 일치하지 않으면 Agent lifecycle 전에 실패합니다.

플랫폼별 GitHub Environment:

- `managed-canary-eks`
- `managed-canary-aks`
- `managed-canary-gke`
- `managed-canary-openshift`

각 Environment에는 required reviewer와 다음 값을 설정합니다.

| 종류 | 이름 | 용도 |
| --- | --- | --- |
| Variable | `RCA_MANAGED_CANARY_ENVIRONMENT` | Environment가 담당하는 플랫폼명, 예: `eks` |
| Secret | `RCA_MANAGED_CANARY_KUBECONFIG` | 전용 kubeconfig의 base64 값 |
| Secret | `RCA_MANAGED_CANARY_PASSWORD` | Platform 관리자 비밀번호, applied canary에서만 필요 |
| Variable | `RCA_MANAGED_CANARY_BASE_URL` | Agent 노드에서 접근 가능한 HTTPS Platform URL |
| Variable | `RCA_MANAGED_CANARY_USERNAME` | Platform 관리자 ID, 기본값 `admin` |
| Variable | `RCA_MANAGED_CANARY_IMAGE_REPOSITORY` | Python canary image 또는 승인된 내부 mirror |
| Variable | `RCA_MANAGED_CANARY_IMAGE_TAG` | digest가 포함된 image tag |

Kubeconfig secret은 다음과 같이 준비합니다. 명령 출력은 terminal history나 문서에 남기지 않습니다.

```bash
base64 -w 0 managed-canary.kubeconfig
```

Runner에는 `self-hosted`, `linux`, `managed-canary`, 플랫폼명(`eks`, `aks`, `gke`, `openshift`) label을 지정합니다. Environment marker가 선택 플랫폼과 다르거나 비어 있으면 workflow가 시작 단계에서 실패합니다. Kubeconfig와 Platform은 runner에서 접근 가능해야 하며, 적용형 canary에 필요한 RBAC는 사전에 최소 권한으로 부여합니다. Workflow 자체는 cloud IAM이나 SCC를 추가하지 않습니다.

EKS에서는 Ready node를 고를 때 `eks.amazonaws.com/compute-type=fargate` 노드를 제외합니다. Fargate-only
클러스터는 DaemonSet을 지원하지 않으므로 canary를 실패 처리합니다. Auto Mode node를 선택한 경우에는
immutable OS와 SELinux 경계를 실제로 확인하기 전까지 `mode=safe`만 허용합니다. Managed Node Group과
Bottlerocket의 `node-diagnostics`도 host file과 runtime socket 접근이 실제 canary에서 확인되기 전에는
지원 완료로 표시하지 않습니다.

실행 순서:

1. `apply=false`, confirmation=`PREFLIGHT-<PLATFORM>`으로 fingerprint, readiness, Helm server dry-run을 확인합니다.
2. preflight artifact와 warning을 검토합니다.
3. change reference를 등록하고 `apply=true`, confirmation=`RUN-<PLATFORM>-CANARY`로 승인 요청합니다.
4. 단일 Ready node에 격리 namespace Agent를 배포하고 등록, evidence, RCA, incident, bundle 검증을 수행합니다.
5. Helm release, namespace, Platform test cluster가 정리된 경우에만 성공합니다.

업로드 artifact에는 `managed-cluster-canary/v1` attestation만 포함합니다. Node 이름, namespace, cluster ID, Platform URL, kubeconfig, evidence 원문은 업로드하지 않습니다. Attestation의 `promotion.eligible_for_manual_review=true`는 matrix 자동 변경 허가가 아닙니다. Platform owner와 security owner가 artifact를 검토한 뒤 별도 PR로 `config/platform-compatibility-matrix.json`을 수정해야 합니다.

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

2026-07-21 kubeadm amd64 검증 결과:

- 격리된 Ubuntu 24.04.4 VM에 Kubernetes 1.33.13, containerd, Flannel v0.28.4 구성
- 단일 노드와 모든 system pod가 `Ready`인 상태에서 Agent 등록과 heartbeat 정상
- 필수 collector 14개 등록 및 evidence request 완료, evidence 8건 수집
- RCA report, analysis task, incident, topology와 서명된 evidence bundle 생성 확인
- 권장 조치 3건은 모두 rule-based read-only `AUTO_SAFE`로 판정됐으며 직접 변경 조치는 실행하지 않음
- canary namespace와 Platform 테스트 cluster 삭제 완료
- 검증용 VM, LXD bridge, storage pool, 프록시와 임시 파일은 결과 회수 후 제거

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
