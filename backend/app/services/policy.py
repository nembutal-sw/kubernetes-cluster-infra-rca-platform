from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from backend.app.models import PolicyLevel, RecommendedAction


@dataclass(frozen=True)
class ActionPolicyRule:
    policy: PolicyLevel
    automation_mode: str
    description: str
    base_risks: tuple[str, ...] = ()


ACTION_RULES: dict[str, ActionPolicyRule] = {
    "collect_more_evidence": ActionPolicyRule(
        policy=PolicyLevel.AUTO_SAFE,
        automation_mode="read_only",
        description="Read-only evidence collection or verification.",
    ),
    "collect_linux_low_level_evidence": ActionPolicyRule(
        policy=PolicyLevel.AUTO_SAFE,
        automation_mode="read_only",
        description="Read-only Linux kernel, procfs, sysfs, storage, process, and network diagnostics.",
    ),
    "inspect_kernel_state": ActionPolicyRule(
        policy=PolicyLevel.AUTO_SAFE,
        automation_mode="read_only",
        description="Read-only kernel log and kernel state inspection.",
    ),
    "inspect_network_state": ActionPolicyRule(
        policy=PolicyLevel.AUTO_SAFE,
        automation_mode="read_only",
        description="Read-only Linux network stack inspection.",
    ),
    "inspect_storage_state": ActionPolicyRule(
        policy=PolicyLevel.AUTO_SAFE,
        automation_mode="read_only",
        description="Read-only filesystem, mount, block device, and inode inspection.",
    ),
    "restart_kubelet": ActionPolicyRule(
        policy=PolicyLevel.APPROVAL_REQUIRED,
        automation_mode="operator_approval",
        description="Restart kubelet after operator approval.",
        base_risks=("node_agent_disruption", "workload_status_change"),
    ),
    "restart_containerd": ActionPolicyRule(
        policy=PolicyLevel.APPROVAL_REQUIRED,
        automation_mode="operator_approval",
        description="Restart container runtime after operator approval.",
        base_risks=("container_runtime_disruption", "workload_impact"),
    ),
    "cordon_node": ActionPolicyRule(
        policy=PolicyLevel.APPROVAL_REQUIRED,
        automation_mode="operator_approval",
        description="Cordon or drain node after operator approval.",
        base_risks=("workload_rescheduling", "capacity_reduction"),
    ),
    "drain_node": ActionPolicyRule(
        policy=PolicyLevel.APPROVAL_REQUIRED,
        automation_mode="operator_approval",
        description="Drain node after operator approval.",
        base_risks=("workload_rescheduling", "pod_eviction"),
    ),
    "cleanup_disk": ActionPolicyRule(
        policy=PolicyLevel.APPROVAL_REQUIRED,
        automation_mode="operator_approval",
        description="Clean disk only after target path review and approval.",
        base_risks=("data_loss", "runtime_cache_mutation"),
    ),
    "open_gitops_pr": ActionPolicyRule(
        policy=PolicyLevel.GITOPS_PR_ONLY,
        automation_mode="gitops_pr",
        description="Suggest configuration changes only through a reviewable GitOps PR.",
        base_risks=("configuration_change", "review_required"),
    ),
    "update_cni_mtu": ActionPolicyRule(
        policy=PolicyLevel.GITOPS_PR_ONLY,
        automation_mode="gitops_pr",
        description="Suggest CNI MTU changes through GitOps only.",
        base_risks=("network_partition", "configuration_change"),
    ),
    "update_dns_config": ActionPolicyRule(
        policy=PolicyLevel.GITOPS_PR_ONLY,
        automation_mode="gitops_pr",
        description="Suggest DNS/CoreDNS changes through GitOps only.",
        base_risks=("cluster_dns_disruption", "configuration_change"),
    ),
    "increase_conntrack_limit": ActionPolicyRule(
        policy=PolicyLevel.GITOPS_PR_ONLY,
        automation_mode="gitops_pr",
        description="Suggest conntrack/sysctl changes through GitOps only.",
        base_risks=("kernel_parameter_change", "configuration_change"),
    ),
    "reboot_node": ActionPolicyRule(
        policy=PolicyLevel.NEVER_AUTO_EXECUTE,
        automation_mode="prohibited",
        description="Node reboot is a last-resort operator decision.",
        base_risks=("node_reboot", "workload_outage"),
    ),
    "etcd_member_remove": ActionPolicyRule(
        policy=PolicyLevel.NEVER_AUTO_EXECUTE,
        automation_mode="prohibited",
        description="Etcd membership changes must never be automated by RCA.",
        base_risks=("quorum_loss", "data_loss"),
    ),
    "delete_workload": ActionPolicyRule(
        policy=PolicyLevel.NEVER_AUTO_EXECUTE,
        automation_mode="prohibited",
        description="Deleting workloads or cluster objects is prohibited for automatic remediation.",
        base_risks=("service_outage", "data_loss"),
    ),
    "manual_hardware_check": ActionPolicyRule(
        policy=PolicyLevel.MANUAL_INVESTIGATION,
        automation_mode="manual",
        description="External hardware, storage, or network investigation.",
        base_risks=("external_dependency", "human_judgment_required"),
    ),
    "manual_investigation": ActionPolicyRule(
        policy=PolicyLevel.MANUAL_INVESTIGATION,
        automation_mode="manual",
        description="Human investigation required.",
        base_risks=("human_judgment_required",),
    ),
}

NEVER_AUTO_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(reboot|shutdown|poweroff|halt)\b", re.I), "node_power_action"),
    (re.compile(r"(재부팅|전원\s*종료|강제\s*종료)"), "node_power_action"),
    (re.compile(r"\brm\s+-[^\n]*[rf]", re.I), "recursive_delete"),
    (re.compile(r"\b(mkfs|wipefs|shred|fdisk|parted)\b", re.I), "filesystem_destructive_command"),
    (re.compile(r"\bdd\s+(if|of)=", re.I), "block_device_write"),
    (re.compile(r"\bkubeadm\s+reset\b", re.I), "cluster_reset"),
    (re.compile(r"\betcd(?:ctl)?\b.*\b(member\s+)?(remove|delete)\b", re.I), "etcd_membership_change"),
    (re.compile(r"\bkubectl\s+(delete|replace)\b", re.I), "kubernetes_object_mutation"),
    (re.compile(r"\bkubectl\s+drain\b.*\b(--force|--delete-emptydir-data)\b", re.I), "forceful_node_drain"),
    (re.compile(r"\b(crictl|docker)\s+(rm|rmi|stop|kill)\b", re.I), "runtime_object_mutation"),
    (re.compile(r"\bdelete\s+(node|pod|pvc|pv|volume|disk|namespace)\b", re.I), "cluster_object_delete"),
    (re.compile(r"(노드|파드|PVC|PV|볼륨|디스크|네임스페이스).*(삭제|제거)"), "cluster_object_delete"),
    (re.compile(r"(삭제|제거).*(노드|파드|PVC|PV|볼륨|디스크|네임스페이스)"), "cluster_object_delete"),
    (re.compile(r"\bmount\s+-o\s+remount\b", re.I), "filesystem_remount"),
)

GITOPS_ONLY_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(
            r"\b(CNI|CoreDNS|DNS|MTU|conntrack|sysctl|kubelet)\b.*\b(config|configuration|setting|settings|change|update|patch|limit)\b",
            re.I,
        ),
        "configuration_change",
    ),
    (
        re.compile(
            r"\b(config|configuration|setting|settings|change|update|patch|limit)\b.*\b(CNI|CoreDNS|DNS|MTU|conntrack|sysctl|kubelet)\b",
            re.I,
        ),
        "configuration_change",
    ),
    (re.compile(r"\b(ConfigMap|DaemonSet|Deployment|Helm|manifest)\b.*\b(change|update|patch|apply|PR)\b", re.I), "configuration_change"),
    (re.compile(r"(설정|한도|리밋|매니페스트|헬름).*(변경|수정|조정|적용|PR|풀리퀘스트)"), "configuration_change"),
    (re.compile(r"(변경|수정|조정|적용).*(설정|한도|리밋|매니페스트|헬름)"), "configuration_change"),
    (re.compile(r"(깃옵스|GitOps|PR|풀리퀘스트)"), "configuration_change"),
    (re.compile(r"\bkubectl\s+(apply|patch|scale|rollout|edit)\b", re.I), "direct_kubernetes_configuration_change"),
    (re.compile(r"\bsysctl\s+-w\b", re.I), "direct_kernel_parameter_change"),
)

APPROVAL_REQUIRED_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bsystemctl\s+(restart|start|stop|reload)\b", re.I), "systemd_unit_mutation"),
    (re.compile(r"\b(restart|cordon|drain|evict|cleanup|clean up|truncate)\b", re.I), "node_or_workload_mutation"),
    (re.compile(r"(재시작|cordon|drain|정리|증설|축출|비우기)"), "node_or_workload_mutation"),
    (re.compile(r"\b(?:echo|printf)\b[^\n]*(?:>|>>)\s*/(?:proc|sys)/", re.I), "direct_procfs_or_sysfs_write"),
    (re.compile(r"\btee\b[^\n]*/(?:proc|sys)/", re.I), "direct_procfs_or_sysfs_write"),
    (re.compile(r"\bip\s+(?:link|route|addr|neigh|rule)\s+(?:set|add|del|delete|replace|flush)\b", re.I), "direct_network_mutation"),
    (re.compile(r"\bethtool\s+-(?:K|G|C|L|s|A)\b", re.I), "direct_nic_mutation"),
    (re.compile(r"\bconntrack\s+-(?:D|F)\b", re.I), "conntrack_table_mutation"),
    (re.compile(r"\btc\s+qdisc\s+(?:add|del|delete|replace|change)\b", re.I), "traffic_control_mutation"),
)

READ_ONLY_HINTS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\b(collect|check|inspect|list|get|read|status|describe|logs?|journalctl|dmesg|cat)\b", re.I),
    re.compile(r"(수집|확인|조회|점검|읽기|상태|로그)"),
)

LOW_LEVEL_READ_ONLY_HINTS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\b(?:dmesg|journalctl)\b", re.I),
    re.compile(r"\bsystemctl\s+(?:status|show|is-active|is-failed|list-units|list-timers)\b", re.I),
    re.compile(r"\b(?:cat|grep|awk|sed|head|tail|wc|stat|find)\b[^\n]*/(?:proc|sys|etc|var/log)/", re.I),
    re.compile(r"/(?:proc|sys)/(?:[a-z0-9_.-]+/?)+", re.I),
    re.compile(r"\bsysctl\s+(?!-w\b)(?:-a\b|[a-z0-9_.-]+)", re.I),
    re.compile(r"\b(?:df|du|findmnt|mount|lsblk|blkid|free|vmstat|iostat|mpstat|pidstat|sar|uptime|ps|top)\b", re.I),
    re.compile(r"\b(?:ss|netstat|nstat)\b", re.I),
    re.compile(r"\bip\s+(?:-s\s+)?(?:link|addr|address|route|neigh|neighbor|rule)\b", re.I),
    re.compile(r"\bethtool\s+(?!-(?:K|G|C|L|s|A)\b)", re.I),
    re.compile(r"\bconntrack\s+-(?:S|L|C)\b", re.I),
    re.compile(r"\btc\s+-s\s+qdisc\s+show\b", re.I),
)

POLICY_PRECEDENCE = {
    PolicyLevel.AUTO_SAFE: 0,
    PolicyLevel.MANUAL_INVESTIGATION: 1,
    PolicyLevel.APPROVAL_REQUIRED: 2,
    PolicyLevel.GITOPS_PR_ONLY: 3,
    PolicyLevel.NEVER_AUTO_EXECUTE: 4,
}


class PolicyEngine:
    def classify(
        self,
        action_key: str,
        action: str,
        reason: str,
        *,
        source: str = "rule_based",
        context: dict[str, Any] | None = None,
    ) -> RecommendedAction:
        normalized_key = _normalize_key(action_key)
        normalized_source = _normalize_source(source)
        rule = ACTION_RULES.get(normalized_key)
        guardrails: list[str] = []
        risk_factors: list[str] = []

        if rule is None:
            normalized_key = "manual_investigation"
            rule = ACTION_RULES[normalized_key]
            guardrails.append("unknown_action_key")

        policy = rule.policy
        automation_mode = rule.automation_mode
        risk_factors.extend(rule.base_risks)

        text = _policy_text(action, reason, context)
        matched_never = _matched_risks(text, NEVER_AUTO_PATTERNS)
        matched_gitops = _matched_risks(text, GITOPS_ONLY_PATTERNS)
        matched_approval = _matched_risks(text, APPROVAL_REQUIRED_PATTERNS)

        if matched_never:
            policy = PolicyLevel.NEVER_AUTO_EXECUTE
            automation_mode = "prohibited"
            guardrails.append("never_auto_execute_pattern")
            risk_factors.extend(matched_never)
        elif matched_gitops and _is_less_restrictive(policy, PolicyLevel.GITOPS_PR_ONLY):
            policy = PolicyLevel.GITOPS_PR_ONLY
            automation_mode = "gitops_pr"
            guardrails.append("configuration_change_requires_gitops_pr")
            risk_factors.extend(matched_gitops)
        elif matched_approval and _is_less_restrictive(policy, PolicyLevel.APPROVAL_REQUIRED):
            policy = PolicyLevel.APPROVAL_REQUIRED
            automation_mode = "operator_approval"
            guardrails.append("mutation_requires_operator_approval")
            risk_factors.extend(matched_approval)

        if policy == PolicyLevel.AUTO_SAFE and not _has_read_only_hint(text):
            policy = PolicyLevel.MANUAL_INVESTIGATION
            automation_mode = "manual"
            guardrails.append("auto_safe_requires_read_only_signal")
            risk_factors.append("insufficient_read_only_evidence")

        if normalized_source == "llm":
            guardrails.append("llm_output_cannot_trigger_direct_automation")

        automation_allowed = (
            policy == PolicyLevel.AUTO_SAFE
            and normalized_source != "llm"
            and not guardrails
        )
        requires_approval = policy == PolicyLevel.APPROVAL_REQUIRED
        review_required = policy in {PolicyLevel.APPROVAL_REQUIRED, PolicyLevel.GITOPS_PR_ONLY}

        return RecommendedAction(
            action=action,
            policy=policy,
            reason=reason,
            action_key=normalized_key,
            source=normalized_source,
            automation_mode=automation_mode,
            automation_allowed=automation_allowed,
            requires_approval=requires_approval,
            review_required=review_required,
            guardrails=_dedupe(guardrails),
            risk_factors=_dedupe(risk_factors),
        )


def _normalize_key(action_key: str) -> str:
    return re.sub(r"[^a-z0-9_]+", "_", str(action_key or "").strip().lower()).strip("_")


def _normalize_source(source: str) -> str:
    normalized = re.sub(r"[^a-z0-9_]+", "_", str(source or "").strip().lower()).strip("_")
    return normalized or "rule_based"


def _policy_text(action: str, reason: str, context: dict[str, Any] | None) -> str:
    parts = [str(action or ""), str(reason or "")]
    if context:
        parts.append(str(context))
    return "\n".join(parts)


def _matched_risks(text: str, patterns: tuple[tuple[re.Pattern[str], str], ...]) -> list[str]:
    return [risk for pattern, risk in patterns if pattern.search(text)]


def _has_read_only_hint(text: str) -> bool:
    return any(pattern.search(text) for pattern in READ_ONLY_HINTS + LOW_LEVEL_READ_ONLY_HINTS)


def _is_less_restrictive(current: PolicyLevel, candidate: PolicyLevel) -> bool:
    return POLICY_PRECEDENCE[current] < POLICY_PRECEDENCE[candidate]


def _dedupe(values: list[str] | tuple[str, ...]) -> list[str]:
    result = []
    seen = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result
