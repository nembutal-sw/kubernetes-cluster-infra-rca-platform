# RCA Report Schema

## 한국어 요약

RCA Report는 단순히 “원인 하나”를 보여주는 문서가 아니라, **어떤 evidence가 어떤 signal로 해석되었고, 어떤 근거로 원인 후보와 권장 조치가 나왔는지** 보여주는 결과물입니다.

현재 report는 timeline, confidence score, impact scope, policy-classified actions, trigger metadata를 포함합니다. Evidence bundle export를 통해 report에 사용된 redacted evidence와 timeline을 ZIP으로 내려받을 수 있습니다.

---

## English Reference

## Top-Level Shape

```json
{
  "report_id": "report-...",
  "cluster_id": "cluster-...",
  "incident_id": "incident-...",
  "status": "completed",
  "trigger": {},
  "scope": {},
  "summary": {},
  "evidence": [],
  "root_cause_candidates": [],
  "recommended_actions": [],
  "resolution_checklist": [],
  "created_at": "..."
}
```

## Summary

```json
{
  "symptom": "DiskPressure",
  "most_likely_cause": "Filesystem capacity is critically high",
  "confidence": "high"
}
```

## Derived Signals

Signals are produced by rule-based detectors.

```json
{
  "signal": "disk_usage_critical",
  "component": "disk",
  "severity": "critical",
  "confidence": "high",
  "value": 96.0,
  "threshold": 90,
  "matched_fields": ["disk.root_usage_percent"],
  "interpretation": "Filesystem capacity is critically high.",
  "next_step": "Inspect disk usage, image storage, and logs.",
  "supporting_evidence": ["disk.root_usage_percent=96.0"]
}
```

## Root Cause Candidate

```json
{
  "cause": "Filesystem capacity is critically high.",
  "confidence": "high",
  "supporting_evidence": ["disk.root_usage_percent=96.0"],
  "confidence_score": 85,
  "evidence_paths": ["disk.root_usage_percent"]
}
```

`confidence_score` is rule-based and normalized to `0..100`. It is not an automation authorization mechanism.

## Impact Scope

```json
{
  "nodes": ["worker-1"],
  "affected_pods": ["payments/payment-api-7d9f9c"],
  "affected_namespaces": ["payments"],
  "affected_workloads": ["ReplicaSet/payment-api-7d9f9c"],
  "affected_services": [],
  "observed_services": ["payments/payment-api"],
  "service_impact_assessment": "Service objects were observed, but endpoint and traffic correlation were not verified."
}
```

Important wording:

- `affected_pods` are node-correlated from evidence.
- `observed_services` are inventory objects seen in evidence.
- `affected_services` remains empty until selector/endpoint/traffic correlation is implemented.

## Recommended Action

```json
{
  "action": "Inspect storage state and recent system logs",
  "policy": "APPROVAL_REQUIRED",
  "reason": "Disk pressure signals were detected",
  "action_key": "inspect_storage_state",
  "source": "rule_based",
  "automation_mode": "manual_only",
  "automation_allowed": false,
  "requires_approval": true,
  "review_required": true,
  "guardrails": ["manual review required"],
  "risks": [],
  "execution_plan": {
    "executable": false
  }
}
```

Actions are policy output and workflow input. They are not direct execution requests.

## Evidence Bundle Export

Report or incident bundle exports include:

```text
summary.json
evidence/*.json
signals.json
timeline.json
rca-report.md
```

Sensitive values are redacted before export, and the export action is audited.

## Compatibility Notes

The schema is intended to remain stable for UI and export consumers. When adding fields:

- keep existing field names
- prefer additive changes
- preserve redaction behavior
- avoid storing raw secrets or credentials
