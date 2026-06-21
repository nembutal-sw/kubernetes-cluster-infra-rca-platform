# Agent Permission Model

Agent는 설치 모드에 따라 권한과 수집 범위를 구분합니다.

| Mode | 기본 여부 | 권한 | 주요 수집 범위 |
| --- | --- | --- | --- |
| `safe` | 예 | 비-root, capability 없음, hostPath 없음 | Kubernetes API, 기본 node/DNS 상태 |
| `node-diagnostics` | 아니오 | hostNetwork, hostPID, root, read-only hostPath | kernel, systemd, runtime, disk, memory, network |
| `ebpf` | 아니오 | node-diagnostics + BPF/PERFMON/NET_ADMIN | OOM kill, TCP retransmit, DNS timeout |

## Safe Mode

운영 배포의 기본값입니다.

- `runAsNonRoot=true`
- `runAsUser=65532`
- `capabilities.drop=ALL`
- `hostNetwork=false`
- `hostPID=false`
- host filesystem mount 없음

Safe Mode에서 고권한 collector가 요청되면 Agent는 실행하지 않고
`status=disabled` Evidence를 반환합니다.

## Node Diagnostics Mode

노드 로컬 원인 분석이 필요한 환경에서 명시적으로 사용합니다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set mode=node-diagnostics
```

host mount는 읽기 전용이며 Agent action executor는 존재하지 않습니다.

## eBPF Mode

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set mode=ebpf \
  --set ebpf.enabled=true
```

커널과 배포 환경에 따라 추가 capability가 필요할 수 있습니다.
이 모드는 보안 검토 후 제한된 노드에만 적용하는 것을 권장합니다.
