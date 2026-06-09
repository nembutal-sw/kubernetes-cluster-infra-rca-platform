from __future__ import annotations

from backend.app.models import PolicyLevel, RecommendedAction


class PolicyEngine:
    def classify(self, action_key: str, action: str, reason: str) -> RecommendedAction:
        policy = {
            "collect_more_evidence": PolicyLevel.AUTO_SAFE,
            "restart_kubelet": PolicyLevel.APPROVAL_REQUIRED,
            "restart_containerd": PolicyLevel.APPROVAL_REQUIRED,
            "open_gitops_pr": PolicyLevel.GITOPS_PR_ONLY,
            "reboot_node": PolicyLevel.NEVER_AUTO_EXECUTE,
            "manual_hardware_check": PolicyLevel.MANUAL_INVESTIGATION,
        }.get(action_key, PolicyLevel.MANUAL_INVESTIGATION)

        return RecommendedAction(action=action, policy=policy, reason=reason)

