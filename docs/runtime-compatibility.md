# Runtime Compatibility

Node Agent는 RKE2나 containerd만 가정하지 않습니다. 런타임 evidence는 범용
`runtime_*` 필드를 우선 사용하고, 기존 `containerd_*` 필드는 입력 호환 목적으로만 유지합니다.

## Detection

`scripts/real-cluster-readiness-check.py`는 read-only Kubernetes API 응답으로 다음 fingerprint를 생성합니다.

- 배포판: RKE2, K3s, kubeadm, EKS, AKS, GKE, OpenShift, MicroK8s, K0s
- 런타임: containerd, CRI-O, cri-dockerd, Docker
- CNI: Cilium, Calico, Flannel, AWS VPC CNI, Azure CNI, GKE Dataplane V2, OVN-Kubernetes 등
- 노드 아키텍처, OS, 커널, kubelet 버전과 이기종 구성 여부

전체 node label, annotation 값과 provider ID는 compatibility report에 저장하지 않습니다.
판별 근거는 배포판 신호, provider scheme, CNI workload처럼 축약된 값만 기록합니다.

## Validation Level

| Platform | Detection fixture | Real Agent E2E | 현재 판정 |
| --- | --- | --- | --- |
| RKE2 / containerd / Cilium | 완료 | ARM64/amd64 완료, 2026-07-21 검증 | `verified_real` |
| K3s / containerd / Flannel | 완료 | amd64 완료 | `verified_real` |
| kubeadm / containerd / Flannel | 완료 | amd64 완료, 2026-07-21 검증 | `verified_real` |
| EKS Managed Node / Bottlerocket / Auto Mode | 공식 문서 기반 fixture 완료 | 대기 | `contract_fixture_only` |
| EKS Fargate | 탐지 및 배포 차단 fixture 완료 | DaemonSet 미지원 | `contract_fixture_only` |
| AKS / GKE | 완료 | 대기 | `contract_fixture_only` |
| OpenShift / CRI-O / OVN-Kubernetes | 완료 | 대기 | `contract_fixture_only` |
| MicroK8s / K0s | 기본 신호만 지원 | 대기 | `planned` |

fixture 통과는 실제 DaemonSet lifecycle 지원을 의미하지 않습니다. 운영 배포 전에는 node-scoped canary로
register, heartbeat, evidence response와 필수 collector 14개를 다시 검증해야 합니다.

kubeadm 실검증은 Ubuntu 24.04.4, Kubernetes 1.33.13, containerd, Flannel v0.28.4
단일 노드에서 수행했습니다. 다른 아키텍처, 런타임, CNI 조합은 별도 canary 전까지
검증 완료로 판정하지 않습니다.

## EKS Document-Backed Contract

실제 EKS 노드가 없는 상태에서는 합성 Kubernetes API snapshot만 사용합니다. Fixture는
`tests/fixtures/managed-platforms/eks-contracts.json`에 있으며 공식 문서 URL과 확인일을 함께
보관합니다. 확인일이 180일을 넘으면 CI가 실패하므로 문서와 계약을 다시 검토해야 합니다.
`scripts/managed-platform-contract-check.py`는 다음 구성을 독립적으로 검사합니다.

| EKS 구성 | 탐지 신호 | Agent 계약 |
| --- | --- | --- |
| Managed Node Group / AL2023 | `eks.amazonaws.com/nodegroup`, AWS provider, `aws-node` | `node-diagnostics` 후보, real canary 필수 |
| Managed Node Group / Bottlerocket arm64 | EKS node group, Bottlerocket OS, containerd, `aws-node` | `node-diagnostics` 후보, host file 접근 real canary 필수 |
| Auto Mode | `eks.amazonaws.com/compute-type=auto`, 관리형 네트워크 구성 | 먼저 `safe`, host evidence 접근 미검증 |
| Fargate | `eks.amazonaws.com/compute-type=fargate` | DaemonSet 미지원, Agent 배포 차단 |

Auto Mode는 일반 EKS와 달리 VPC CNI와 node-local DNS 같은 구성요소가 AWS 관리 영역에 있어
`aws-node`나 전통적인 CoreDNS Pod가 보이지 않을 수 있습니다. 이를 CNI 장애로 오판하지 않고
fixture에서는 `unknown`으로 보존합니다. Fargate는 DaemonSet, privileged container,
HostNetwork를 지원하지 않으므로 node-level Agent 대상이 아닙니다.

참고한 공식 문서:

- [EKS managed node groups](https://docs.aws.amazon.com/eks/latest/userguide/managed-node-groups.html)
- [EKS optimized Amazon Linux AMIs](https://docs.aws.amazon.com/eks/latest/userguide/eks-optimized-ami.html)
- [EKS Bottlerocket AMIs](https://docs.aws.amazon.com/eks/latest/userguide/eks-optimized-ami-bottlerocket.html)
- [Amazon VPC CNI](https://docs.aws.amazon.com/eks/latest/best-practices/vpc-cni.html)
- [EKS Auto Mode](https://docs.aws.amazon.com/eks/latest/userguide/automode.html)
- [EKS Auto Mode networking](https://docs.aws.amazon.com/eks/latest/userguide/auto-networking.html)
- [EKS Fargate considerations](https://docs.aws.amazon.com/eks/latest/userguide/fargate.html)
- [Kubernetes DaemonSet](https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/)
- [Kubernetes Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
- [Kubernetes volumes and hostPath](https://kubernetes.io/docs/concepts/storage/volumes/)

오프라인 검증 명령:

```bash
python3 scripts/managed-platform-contract-check.py
```

호환성 기준 데이터는 `config/platform-compatibility-matrix.json`에 있으며 다음 명령으로 검증합니다.

```bash
python3 scripts/cluster_compatibility.py --validate-catalog
```

저장된 Kubernetes API 응답으로 오프라인 리포트를 생성할 수도 있습니다.

```bash
python3 scripts/cluster_compatibility.py \
  --nodes-json nodes.json \
  --pods-json pods.json \
  --output validation-results/compatibility.json
```

## Runtime Socket

기본 탐지 경로:

- kubeadm/containerd: `/run/containerd/containerd.sock`
- RKE2/K3s: `/run/k3s/containerd/containerd.sock`, `/run/rke2/containerd/containerd.sock`
- K0s: `/run/k0s/containerd.sock`, `/var/lib/k0s/run/containerd.sock`
- MicroK8s: `/var/snap/microk8s/common/run/containerd.sock`
- CRI-O: `/run/crio/crio.sock`, `/var/run/crio/crio.sock`
- cri-dockerd: `/run/cri-dockerd.sock`, `/var/run/cri-dockerd.sock`
- Docker socket evidence: `/run/docker.sock`, `/var/run/docker.sock`

운영자는 `CONTAINER_RUNTIME_SOCKET_PATHS`로 탐지 경로를 덮어쓸 수 있습니다.

Example:

```text
CONTAINER_RUNTIME_SOCKET_PATHS=crio=/run/crio/crio.sock,containerd=/run/containerd/containerd.sock
```

분석 동작:

- `containerd_*` signal은 실제 탐지 런타임이 containerd일 때만 생성합니다.
- 그 외 런타임은 `container_runtime_*` signal과 `crio`, `cri-dockerd`, `docker` component를 사용합니다.
- 소켓 권한 오류와 런타임 장애를 분리합니다.
- 사용하지 않는 `crio`, `docker` systemd unit이 inactive라는 이유만으로 장애 처리하지 않습니다.
- journal은 DaemonSet에서 직접 호출하지 않고 host journal 파일 기반 수집을 기본으로 합니다.
- `APPROVED_ACTIONS_ENABLED=false`는 모든 호환성 검증에서 유지합니다.
