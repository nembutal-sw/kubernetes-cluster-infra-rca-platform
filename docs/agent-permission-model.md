# Agent Permission Model

Agent는 설치 모드에 따라 권한과 수집 범위를 분리합니다. 어떤 모드에서도 승인 조치 실행기는 포함하지 않으며, hostPath는 읽기 전용입니다.

| Mode | 기본값 | 권한 | 주요 수집 범위 |
| --- | --- | --- | --- |
| `safe` | 예 | 비 root, capability 없음, hostPath 없음 | Kubernetes API, 기본 node/DNS 상태 |
| `node-diagnostics` | 아니오 | hostNetwork, hostPID, root, read-only hostPath | kernel, systemd, runtime, disk, memory, network |
| `ebpf` | 아니오 | node-diagnostics + BPF/PERFMON/NET_ADMIN | OOM kill, TCP retransmit, DNS timeout |

## Safe Mode

최소 권한 운영 배포의 기본값입니다.

- `runAsNonRoot=true`
- `runAsUser=65532`
- `capabilities.drop=ALL`
- `hostNetwork=false`
- `hostPID=false`
- host filesystem mount 없음

Safe Mode에서 host collector가 요청되면 실행하지 않고 `status=disabled` evidence를 반환합니다.

## Node Diagnostics Mode

노드 로컬 원인 분석이 필요한 환경에서 명시적으로 사용합니다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set mode=node-diagnostics
```

`kernelLog.readDmesg=true`일 때 `CAP_SYSLOG`만 추가합니다. 이는 `kernel.dmesg_restrict=1`인 호스트에서 dmesg를 읽기 위한 권한이며, 변경 명령을 허용하지 않습니다. 보안 정책상 허용할 수 없다면 다음과 같이 끌 수 있지만 kernel log 수집은 제한될 수 있습니다.

```bash
--set kernelLog.readDmesg=false
```

systemd는 기본적으로 `SYSTEMD_COLLECTOR_MODE=file`을 사용합니다. DaemonSet에서 host DBus를 공유하지 않으므로 journal 전용 호스트에서는 systemd/kubelet 로그가 `limited`로 표시될 수 있습니다. unit 파일, `/host/proc`, runtime socket, 커널·스토리지·네트워크 상태 수집은 계속 동작합니다.

## eBPF Mode

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set mode=ebpf \
  --set ebpf.enabled=true
```

기본 capability는 `BPF`, `PERFMON`, `NET_ADMIN`, `SYS_RESOURCE`입니다. 구형 커널에서만 `ebpf.legacySysAdmin=true`를 검토하고, 먼저 canary 노드에서 보안 정책과 커널 호환성을 검증합니다.

## Kubernetes API

Agent는 기본적으로 Kubernetes가 주입한 Service host와 ServiceAccount token을 사용합니다. 일시적인 transport 오류는 최대 두 번 시도합니다.

hostNetwork 또는 배포판 네트워크 구성 때문에 Service ClusterIP를 사용할 수 없다면 `kubernetesApiUrl`에 관리 가능한 API endpoint를 명시할 수 있습니다.

```bash
--set kubernetesApiUrl=https://control-plane.internal:6443
```

URL만 override되며 인증은 기존 ServiceAccount token과 CA bundle을 계속 사용합니다.

## Enrollment Token Permission

`enrollment.mode=kubernetes-token-review`는 일반 Kubernetes API 수집 token과 같은 ServiceAccount의
별도 projected token을 지정 audience로 mount합니다. Agent는 이 파일을 등록할 때마다 다시 읽습니다.
Chart는 해당 mode에서만 `authentication.k8s.io/tokenreviews`의 `create` 권한을 추가합니다.

TokenReview 결과만으로 node identity를 확정하지 않습니다. Platform이 신뢰한 API Server에서 bound
Pod를 다시 조회해 Pod UID, ServiceAccount, node name과 삭제 상태를 검증합니다. 자세한 설정은
[Agent Enrollment](agent-enrollment.md)를 참고합니다.
