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

`recommended_actions`와 `policy_decisions`의 항목은 아래 형태를 사용합니다.

```json
{
  "action_key": "restart_kubelet",
  "action": "kubelet 상태가 failed/restarting이면 운영자 승인 후 kubelet 재시작을 검토합니다.",
  "policy": "APPROVAL_REQUIRED",
  "reason": "노드 상태 회복에 도움이 될 수 있지만 workload 영향이 있어 승인이 필요합니다.",
  "source": "rule_based",
  "automation_mode": "operator_approval",
  "automation_allowed": false,
  "requires_approval": true,
  "review_required": true,
  "guardrails": [],
  "risk_factors": ["node_agent_disruption", "workload_status_change"]
}
```

## 보고서 구성

- 장애 요약
- 타임라인
- 영향 범위
- 수집된 증거
- LLM 입력용 `preprocessed_evidence`
- provider 응답과 상태를 담는 `llm_analysis`
- evidence field에서 도출한 `derived_signals`
- 운영자가 원인 확정에 사용할 `resolution_checklist`
- 원인 후보와 근거
- 배제된 원인
- 추가 확인 명령어
- 권장 조치
- Policy Engine 분류
- 운영자 메모

`preprocessed_evidence`, `llm_analysis`, `derived_signals`, `resolution_checklist`는 report의 `evidence` 배열 안에 별도 section으로 들어갑니다. 저장 스키마는 그대로 두고, report 소비자가 원본 collector 결과, LLM 입력 payload, provider 응답, 분석 결과를 같은 응답에서 볼 수 있게 하기 위한 구조입니다.

`preprocessed_evidence.payload`는 `preprocessed-evidence/v2` 기준으로 `evidence_quality`, `incident_focus`, `component_health`, `log_summary`를 포함합니다. 이 값은 LLM이 raw collector 전체를 보지 않고도 수집 품질, 우선 component, 주요 failure mode, 로그 집계를 판단하기 위한 요약입니다.

LLM Analyzer를 붙일 때는 raw collector 결과가 아니라 `preprocessed_evidence.payload`만 입력으로 사용합니다.
