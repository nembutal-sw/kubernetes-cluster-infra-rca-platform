# Policy Engine

## 한국어 요약

Policy Engine은 RCA 결과와 LLM 제안을 운영자가 안전하게 다룰 수 있도록 조치의 위험도를 분류하는 계층입니다.

이 프로젝트에서 LLM은 실행 주체가 아닙니다. LLM은 설명과 제안만 만들고, 모든 조치는 Policy Engine을 거쳐 `automation_allowed=false` 또는 manual approval 중심으로 처리됩니다.

현재 Platform과 Agent는 운영 환경 변경 작업을 직접 실행하지 않습니다. Policy Engine의 역할은 “실행”이 아니라 **분류, guardrail 부여, approval/manual workflow 연결**입니다.

---

## English Reference

## Policy Levels

| Policy | Meaning |
| --- | --- |
| `AUTO_SAFE` | read-only verification may be requested |
| `APPROVAL_REQUIRED` | human approval and manual handling required |
| `GITOPS_PR_ONLY` | change should be reviewed through GitOps PR |
| `NEVER_AUTO_EXECUTE` | must not be automated |
| `MANUAL_INVESTIGATION` | investigation-only recommendation |

## Key Guarantees

- LLM-origin actions are never automatically executable.
- High-risk actions receive guardrails and manual workflow guidance.
- Approval does not cause agent-side execution.
- Recommended action plans can include commands or YAML previews as documentation only.
- The node agent remains focused on evidence collection.

## Recommended Action Fields

A report action includes:

```json
{
  "action": "Inspect kubelet status and recent logs",
  "policy": "APPROVAL_REQUIRED",
  "reason": "Kubelet failure signals were detected",
  "action_key": "inspect_kernel_state",
  "source": "rule_based",
  "automation_mode": "manual_only",
  "automation_allowed": false,
  "requires_approval": true,
  "review_required": true,
  "guardrails": ["manual runbook required"],
  "risks": ["service disruption if handled incorrectly"],
  "execution_plan": {
    "command_key": "documentation_only",
    "parameters": {},
    "commands": ["review runbook and verify state manually"],
    "patch_preview": null,
    "executable": false,
    "timeout_seconds": 60
  }
}
```

## Rule-Based vs LLM Source

Rule-based recommendations can create manual action requests. LLM recommendations remain diagnostic suggestions and cannot trigger automation.

```text
source=llm -> automation_allowed=false
source=llm -> no automatic execution
source=rule_based -> may create approval/manual workflow
```

## Manual-Only Workflow

For `APPROVAL_REQUIRED` actions:

```text
recommended action
  -> action request
  -> pending approval
  -> approved_manual or rejected
  -> external runbook / GitOps workflow
  -> completed
```

For `GITOPS_PR_ONLY` actions, the platform may show a YAML preview, but the actual change should happen through an external GitOps review process.

## Guardrail Examples

Guardrails should communicate operational constraints:

- verify evidence before applying changes
- confirm maintenance window when needed
- keep rollback plan outside the RCA platform
- record decision reason
- keep LLM suggestions diagnostic-only

## Portfolio Message

> Policy Engine은 자동 실행을 가능하게 하는 장치가 아니라, RCA 결과를 안전한 운영 절차로 연결하는 guardrail 계층입니다.
