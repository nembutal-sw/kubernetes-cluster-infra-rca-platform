# Policy Engine

## 한국어 요약

Policy Engine은 RCA 결과와 LLM 제안을 운영자가 안전하게 다룰 수 있도록 조치의 위험도를 분류하는 계층입니다.

현재 핵심 원칙은 명확합니다.

> RCA는 자동화하지만, 위험 조치는 자동 실행하지 않는다.

LLM은 조치 실행자가 아닙니다. LLM이 제안한 action은 항상 diagnostic suggestion으로 취급되며 `automation_allowed=false`입니다.

Rule-based action도 host mutation을 실행하지 않습니다. `restart_kubelet`, `restart_containerd`, `restart_container_runtime` 같은 조치는 runbook/manual workflow 대상으로만 표시됩니다.

---

## English Reference

## Policy Levels

```text
AUTO_SAFE
APPROVAL_REQUIRED
GITOPS_PR_ONLY
NEVER_AUTO_EXECUTE
MANUAL_INVESTIGATION
```

## Source Types

```text
rule_based
llm
demo
system
```

LLM-origin actions are never executable.

## RecommendedAction Fields

```json
{
  "action": "Restart kubelet after manual verification",
  "policy": "APPROVAL_REQUIRED",
  "reason": "kubelet service appears unhealthy",
  "action_key": "restart_kubelet",
  "source": "rule_based",
  "automation_mode": "manual",
  "automation_allowed": false,
  "requires_approval": true,
  "review_required": true,
  "guardrails": ["manual_runbook_only"],
  "risks": ["node_disruption"],
  "execution_plan": {
    "command_key": "restart_systemd_unit",
    "parameters": {"unit": "kubelet"},
    "preview_commands": ["systemctl restart kubelet"],
    "executable": false,
    "timeout_seconds": 60
  }
}
```

`execution_plan.preview_commands` is documentation for human review. It is not executed by the platform or agent.

## Current Action Semantics

### Read-only action

Some actions may create a read-only evidence request.

Examples:

```text
inspect_storage_state
inspect_network_state
inspect_kernel_state
collect_linux_low_level_evidence
```

### Manual action

Risky actions become action requests that require approval and manual completion.

Examples:

```text
restart_kubelet
restart_containerd
restart_container_runtime
cordon_node
drain_node
```

### GitOps-only action

Changes that should be reviewed through GitOps are marked as `GITOPS_PR_ONLY`.

Example:

```text
update_cni_mtu
```

The platform may show a YAML preview, but it does not patch the cluster directly.

## Disabled Host Mutation

Agent-side mutation execution has been removed:

```text
node_agent/actions.py removed
ApprovedActionExecutor removed from agent loop
ActionExecution queue no longer drives host commands
```

Database migration `V6__disable_agent_action_execution.sql` expires legacy queued/leased executions and converts queued/executing action requests to manual approval state.

## Guardrail Rules

Recommended guardrails:

```text
manual_runbook_only
llm_action_never_auto
requires_human_approval
requires_gitops_review
requires_evidence_bundle
requires_audit_record
no_host_mutation
```

## Design Rationale

The platform is an RCA and decision-support system, not a self-healing root agent. This keeps the project enterprise-friendly because it preserves separation of duties, auditability, and operator control.
