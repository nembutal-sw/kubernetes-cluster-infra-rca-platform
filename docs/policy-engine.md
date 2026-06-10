# Policy Engine

Policy Engine은 LLM이 제안한 조치나 RCA 결과에서 도출된 운영 액션을 안전 등급으로 분류합니다.

LLM은 조치 실행자가 아닙니다. LLM의 출력은 Policy Engine의 입력일 뿐입니다.

## 조치 등급

| 등급 | 의미 | 예시 |
| --- | --- | --- |
| `AUTO_SAFE` | 자동 실행 가능성이 있는 읽기/검증 중심 조치 | 추가 상태 조회, 비파괴 health check, 보고서 갱신 |
| `APPROVAL_REQUIRED` | 운영자 승인 후 실행 가능한 조치 | kubelet restart, containerd restart, cordon, drain |
| `GITOPS_PR_ONLY` | 직접 실행하지 않고 PR로만 제안할 조치 | CNI MTU 설정 변경, CoreDNS config 변경, kubelet config 변경 |
| `NEVER_AUTO_EXECUTE` | 자동 실행 금지 | 노드 reboot, 데이터 삭제, etcd member 제거, 강제 drain |
| `MANUAL_INVESTIGATION` | 사람의 판단이 필요한 조치 | 하드웨어 장애 의심, 디스크 교체, 네트워크 장비 점검 |

## 분류 기준

- 데이터 손실 가능성
- 서비스 중단 가능성
- blast radius
- 롤백 가능성
- GitOps로 관리되는 설정인지 여부
- 운영 승인 정책
- 장애 severity
- 대상 클러스터 환경

## 예시

| 권장 조치 | Policy decision |
| --- | --- |
| kubelet journal 추가 수집 | `AUTO_SAFE` |
| kubelet 재시작 | `APPROVAL_REQUIRED` |
| containerd 재시작 | `APPROVAL_REQUIRED` |
| 디스크 정리 또는 증설 | `APPROVAL_REQUIRED` |
| 메모리 압박 지속 시 node cordon/drain 검토 | `APPROVAL_REQUIRED` |
| CoreDNS replica 증가 PR 생성 | `GITOPS_PR_ONLY` |
| etcd member 강제 제거 | `NEVER_AUTO_EXECUTE` |
| read-only filesystem 지속 시 node reboot 검토 | `NEVER_AUTO_EXECUTE` |
| NIC link flap 의심으로 스위치 포트 확인 | `MANUAL_INVESTIGATION` |
