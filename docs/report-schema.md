# RCA Report Schema

RCA 보고서는 장애 증상, 수집 증거, 원인 후보, 신뢰도, 영향 범위, 권장 확인 사항, 정책 분류 결과를 포함합니다.

## 필드

```json
{
  "report_id": "rca-20260610-0001",
  "cluster_id": "cluster-prod-01",
  "status": "completed",
  "trigger": {
    "source": "alertmanager",
    "alert_name": "NodeNotReady",
    "severity": "critical",
    "started_at": "2026-06-10T09:15:00+09:00"
  },
  "scope": {
    "nodes": ["worker-3"],
    "namespaces": ["kube-system"],
    "components": ["kubelet", "containerd", "cni"]
  },
  "summary": {
    "symptom": "worker-3 노드가 NotReady 상태로 전환됨",
    "most_likely_cause": "containerd socket 응답 지연과 kubelet 반복 재시작",
    "confidence": "medium"
  },
  "evidence": [],
  "root_cause_candidates": [],
  "recommended_actions": [],
  "policy_decisions": [],
  "operator_notes": []
}
```

## 보고서 구성

- 장애 요약
- 타임라인
- 영향 범위
- 수집된 증거
- evidence field에서 도출한 `derived_signals`
- 운영자가 원인 확정에 사용할 `resolution_checklist`
- 원인 후보와 근거
- 배제된 원인
- 추가 확인 명령어
- 권장 조치
- Policy Engine 분류
- 운영자 메모

`derived_signals`와 `resolution_checklist`는 report의 `evidence` 배열 안에 별도 section으로 들어갑니다. 저장 스키마는 그대로 두고, report 소비자가 원본 collector 결과와 분석 결과를 같은 응답에서 볼 수 있게 하기 위한 구조입니다.
