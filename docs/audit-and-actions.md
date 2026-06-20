# Audit And Action Workflow

## 한국어 요약

이 프로젝트의 Action Workflow는 **자동 복구 실행 기능이 아니라 안전한 운영 절차를 기록하는 기능**입니다.

현재 Platform과 Node Agent는 `systemctl restart`, `kubectl delete`, `kubectl drain`, `rm -rf` 같은 host mutation을 직접 실행하지 않습니다. RCA report가 추천 조치를 만들면 사용자는 action request를 생성하고, 승인자는 승인/거절을 기록합니다. 승인 이후에도 실행은 사람이 runbook 또는 GitOps PR로 처리하고, Platform에는 `complete-manual`로 완료 사실만 기록합니다.

즉, 이 기능의 목적은 다음입니다.

- 위험 조치 자동 실행 방지
- 승인/거절/수동 완료 이력 보존
- LLM 제안과 rule-based 제안의 안전 경계 분리
- 운영자가 판단 가능한 audit trail 제공

---

## English Reference

## Audit Event Scope

The platform records important user, agent, and system events into `audit_events`.

Typical event categories:

- login success/failure
- logout
- password change
- user approval/rejection
- cluster create/update/delete
- agent register
- agent authentication failure
- evidence submit
- Alertmanager webhook ingest
- incident created/correlated
- report generated
- evidence bundle exported
- action request created
- action approved/rejected
- manual action completed
- demo scenario queued
- notification sent/failed

Audit events should answer:

```text
who did what, to which resource, when, and with what result?
```

## Action Safety Model

Recommended actions can have policies such as:

```text
AUTO_SAFE
APPROVAL_REQUIRED
GITOPS_PR_ONLY
NEVER_AUTO_EXECUTE
MANUAL_INVESTIGATION
```

In the current implementation, `automation_allowed` is only used for **read-only evidence collection requests**. Host mutation execution is disabled.

### Manual-only policy

For risky actions, the platform records approval state only.

```text
pending_approval -> approved_manual -> completed_manual
pending_approval -> rejected
blocked
```

The approval message explicitly states that approval authorizes a human-operated runbook only. The platform and node agent do not execute the command.

## Endpoints

### Create action request or read-only evidence action

```text
POST /api/rca/reports/{report_id}/actions/{action_index}/execute
```

Despite the endpoint name, this does not run host mutation commands. Outcomes:

- `blocked`: policy disallows automatic handling.
- `pending_approval`: manual approval is required.
- `accepted`: a read-only evidence collection request was created.

### Approve or reject manual action request

```text
POST /api/rca/action-requests/{action_request_id}/approve
POST /api/rca/action-requests/{action_request_id}/reject
```

Only `ADMIN` and `APPROVER` can approve or reject.

### Mark manual handling complete

```text
POST /api/rca/action-requests/{action_request_id}/complete-manual
```

Only `ADMIN` and `OPERATOR` can mark an approved manual action as completed.

## Deprecated Agent Execution Path

Agent-side action execution has been permanently disabled.

- `node_agent/actions.py` was removed.
- `POST /api/agents/action-executions` returns an empty list.
- `POST /api/agents/action-results` returns `410 Gone`.
- Existing queued/leased action executions are expired by migration `V6__disable_agent_action_execution.sql`.

## RBAC Summary

```text
ADMIN     full operational control
OPERATOR  operate incidents, reports, read-only action requests, manual completion
APPROVER  approve/reject manual requests
VIEWER    read dashboard/report data, no mutation/export
AUDITOR   audit/metrics visibility
METRICS   metrics scraping only
```

Export is intentionally restricted:

- Report JSON export: `ADMIN`, `OPERATOR`
- Evidence bundle export: `ADMIN`, `OPERATOR`
- Viewer/Approver export: denied

## Audit Fields

A typical audit event should contain:

```json
{
  "actor_type": "user|agent|system",
  "actor_id": "operator@example.com",
  "action": "rca.action_request",
  "resource_type": "action_request",
  "resource_id": "action-request-...",
  "result": "pending_approval",
  "metadata": {
    "report_id": "report-...",
    "action_key": "restart_kubelet",
    "policy": "APPROVAL_REQUIRED",
    "source": "rule_based"
  }
}
```

## Security Principle

The audit/action subsystem is designed to show enterprise readiness without making the platform a risky self-healing agent. RCA is automated; dangerous remediation is not.
