# RCA Report Schema

## 한국어 요약

RCA Report는 단순히 “원인 하나”를 보여주는 문서가 아니라, **어떤 evidence가 어떤 signal로 해석되었고, 어떤 근거로 원인 후보와 권장 조치가 나왔는지** 보여주는 결과물입니다.

현재 report는 다음 정보를 포함합니다.

- 장애 증상 요약
- 가장 가능성 높은 원인
- confidence level 및 candidate별 confidence score
- derived signals
- root cause candidates
- impact scope
- observed services와 service impact caveat
- recommended actions
- policy/guardrail 결과
- timeline과 evidence bundle export 연결

---

## English Reference

## Top-level Shape

```json
{
  "report_id": "report-...",
  "cluster_id": "cluster-...",
  "incident_id": "incident-...",
  "status": "completed",
  "trigger": {
    "alert_name": "DiskPressure",
    "source": "demo",
    "demo": true
  },
  "scope": {
    "nodes": ["worker-1"],
    "affected_pods": ["payments/payment-api-7d9f9c"],
    "affected_namespaces": ["payments"],
    "affected_services": [],
    "observed_services": ["payments/payment-api"],
    "service_impact_assessment": "Service objects were observed, but endpoint/selector correlation was not verified."
  },
  "summary": {
    "symptom": "DiskPressure",
    "most_likely_cause": "Filesystem capacity is critically high.",
    "confidence": "high"
  },
  "evidence": [],
  "root_cause_candidates": [],
  "recommended_actions": [],
  "created_at": "2026-06-21T00:00:00Z"
}
```

## Summary

```json
{
  "symptom": "DiskPressure",
  "most_likely_cause": "Filesystem capacity is critically high.",
  "confidence": "high"
}
```

Report-level confidence is derived from rule-based signals. LLM may improve explanation quality but must not override safety policies.

## Derived Signals

Signals explain how raw evidence was interpreted.

```json
{
  "signal": "disk_usage_critical",
  "component": "disk",
  "severity": "critical",
  "confidence": "high",
  "value": 96.0,
  "threshold": 90.0,
  "matched_fields": ["disk.root_usage_percent"],
  "interpretation": "Filesystem capacity is critically high.",
  "next_step": "Inspect df, container runtime storage, and kubelet image GC logs.",
  "supporting_evidence": ["disk.root_usage_percent=96.0 >= 90.0"]
}
```

## Root Cause Candidates

```json
{
  "cause": "Filesystem capacity is critically high.",
  "confidence": "high",
  "supporting_evidence": ["disk.root_usage_percent=96.0 >= 90.0"],
  "confidence_score": 85,
  "evidence_paths": ["disk.root_usage_percent"]
}
```

`confidence_score` is normalized to `0..100` and should be explainable from signals, severity, threshold matches, and evidence paths.

## Impact Scope

Impact scope is intentionally conservative.

```json
{
  "affected_pods": ["payments/payment-api-7d9f9c"],
  "affected_namespaces": ["payments"],
  "affected_workloads": ["ReplicaSet/payment-api-7d9f9c"],
  "affected_services": [],
  "observed_services": ["payments/payment-api"],
  "service_impact_assessment": "Endpoint, selector, and traffic correlation were not verified, so service impact is unconfirmed."
}
```

`affected_services` remains empty until endpoint/selector/traffic correlation is implemented. Use `observed_services` for inventory-level service objects found in evidence.

## Recommended Actions

```json
{
  "action": "Restart kubelet after manual verification",
  "policy": "APPROVAL_REQUIRED",
  "reason": "kubelet service appears unhealthy",
  "action_key": "restart_kubelet",
  "source": "rule_based",
  "automation_allowed": false,
  "requires_approval": true,
  "review_required": true,
  "guardrails": ["manual_runbook_only"],
  "risks": ["node_disruption"],
  "execution_plan": {
    "command_key": "restart_systemd_unit",
    "preview_commands": ["systemctl restart kubelet"],
    "executable": false
  }
}
```

Action plans may include command previews for operators, but the platform and agent do not execute host mutation commands.

## Evidence Bundle Export

Report and incident bundle export produce a redacted ZIP:

```text
summary.json
evidence/*.json
signals.json
timeline.json
rca-report.md
```

Only `ADMIN` and `OPERATOR` can export bundles.

## Schema Compatibility Notes

- Keep new fields additive where possible.
- Prefer `observed_*` when correlation is not fully proven.
- Keep LLM-derived text clearly marked by source.
- Do not allow report schema to imply automatic remediation.
