# RCA Analysis Rules

Analyzer는 alert 이름만 보고 결론을 만들지 않습니다. Agent evidence에서 장애 해결에 필요한 신호를 추출하고, 신호별 원인 후보, 확인 명령, 정책 분류된 조치를 생성합니다.

## 보고서에 추가되는 분석 섹션

RCA report의 `evidence`에는 원본 collector 결과 외에 두 가지 섹션이 추가됩니다.

- `derived_signals`: 수집값에서 도출한 `critical`, `warning` 신호
- `resolution_checklist`: 운영자가 원인을 확정하거나 조치를 준비할 때 실행할 확인 명령

예시:

```json
{
  "type": "derived_signals",
  "signals": [
    {
      "signal": "containerd_socket_unhealthy",
      "component": "containerd",
      "severity": "critical",
      "supporting_evidence": ["runtime"]
    }
  ]
}
```

## 주요 판정 기준

| 영역 | 신호 | 기준 |
| --- | --- | --- |
| kubelet | `kubelet_unit_unhealthy` | kubelet unit이 `failed`, `restarting`, `dead`, `auto-restart` 계열 상태 |
| kubelet | `kubelet_restarting` | kubelet restart count가 5 이상 |
| containerd | `containerd_socket_unhealthy` | containerd Unix socket probe 실패 |
| containerd | `containerd_socket_latency_high` | containerd socket latency 1000ms 이상 |
| disk | `disk_usage_high` / `disk_usage_critical` | root usage 90% 이상 / 95% 이상 |
| disk | `inode_usage_high` / `inode_usage_critical` | inode usage 90% 이상 / 95% 이상 |
| disk/kernel | `root_filesystem_read_only` | root mount가 read-only |
| disk/kernel | `kernel_io_error` | kernel log 또는 disk collector에서 I/O error 감지 |
| memory | `memory_pressure_high` / `memory_pressure_critical` | memory usage 90% 이상 / 95% 이상 |
| memory | `oom_kill_detected` | OOM kill 문자열 감지 |
| process | `pid_usage_high` | PID usage 80% 이상 |
| network | `interface_down` | down 상태 NIC 존재 |
| network | `nic_link_flap` | carrier_changes 증가 |
| conntrack | `conntrack_near_limit` | conntrack usage 80% 이상 또는 near_limit true |
| CNI | `cni_config_invalid` | CNI config JSON 파싱 오류 |
| CNI | `cni_mtu_values_inconsistent` | CNI config 안에 서로 다른 MTU 값 존재 |
| DNS | `dns_unconfigured` | nameserver 없음 |
| DNS | `dns_latency_high` | DNS lookup latency 500ms 이상 |

## 조치 분류

Analyzer는 조치를 직접 실행하지 않습니다. 조치 문구를 만들고 Policy Engine이 등급을 붙입니다.

| 상황 | 권장 조치 | Policy |
| --- | --- | --- |
| 추가 로그와 상태 확인 필요 | 장애 시간대 journal, kernel log 추가 수집 | `AUTO_SAFE` |
| kubelet unit 비정상 | kubelet 재시작 검토 | `APPROVAL_REQUIRED` |
| containerd socket/unit 비정상 | containerd 재시작 검토 | `APPROVAL_REQUIRED` |
| disk/inode 고갈 | 정리 또는 증설 | `APPROVAL_REQUIRED` |
| memory pressure 지속 | node cordon/drain 검토 | `APPROVAL_REQUIRED` |
| conntrack/CNI/DNS 설정 변경 필요 | GitOps PR 생성 | `GITOPS_PR_ONLY` |
| read-only filesystem 또는 blocked task 지속 | node reboot 검토 | `NEVER_AUTO_EXECUTE` |
| NIC flap, kernel I/O error | 하드웨어/스토리지/네트워크 장비 점검 | `MANUAL_INVESTIGATION` |

## 원칙

- 단일 값으로 판단할 수 없는 항목은 결론으로 단정하지 않습니다.
- `null`은 정상값이 아니라 수집 불가 또는 판단 불가로 취급합니다.
- confidence는 alert 이름보다 실제 evidence signal을 우선합니다.
- 조치는 모두 보고서 제안이며, backend나 LLM이 직접 실행하지 않습니다.
