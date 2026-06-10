from backend.app.models import PolicyLevel
from backend.app.services.policy import PolicyEngine


def test_policy_allows_only_read_only_rule_based_automation() -> None:
    action = PolicyEngine().classify(
        "collect_more_evidence",
        "장애 시간대의 kubelet journal과 dmesg 로그를 수집합니다.",
        "읽기 전용 증거 수집입니다.",
    )

    assert action.policy == PolicyLevel.AUTO_SAFE
    assert action.action_key == "collect_more_evidence"
    assert action.automation_mode == "read_only"
    assert action.automation_allowed is True
    assert action.requires_approval is False
    assert action.review_required is False
    assert action.guardrails == []


def test_policy_allows_linux_low_level_read_only_diagnostics() -> None:
    action = PolicyEngine().classify(
        "collect_linux_low_level_evidence",
        (
            "dmesg, cat /proc/meminfo, sysctl net.netfilter.nf_conntrack_count, "
            "ss -tanp, ip -s link, ethtool eth0, conntrack -S 결과를 수집합니다."
        ),
        "Linux low-level 상태 확인이며 커널, 네트워크, 파일시스템 상태를 변경하지 않습니다.",
    )

    assert action.policy == PolicyLevel.AUTO_SAFE
    assert action.action_key == "collect_linux_low_level_evidence"
    assert action.automation_mode == "read_only"
    assert action.automation_allowed is True
    assert action.guardrails == []


def test_policy_distinguishes_sysctl_read_from_sysctl_write() -> None:
    read_action = PolicyEngine().classify(
        "inspect_kernel_state",
        "sysctl net.ipv4.tcp_retries2 값을 조회합니다.",
        "읽기 전용 kernel parameter 확인입니다.",
    )
    write_action = PolicyEngine().classify(
        "inspect_kernel_state",
        "sysctl -w net.ipv4.tcp_retries2=5 값을 적용합니다.",
        "kernel parameter를 직접 변경합니다.",
    )

    assert read_action.policy == PolicyLevel.AUTO_SAFE
    assert read_action.automation_allowed is True
    assert write_action.policy == PolicyLevel.GITOPS_PR_ONLY
    assert write_action.automation_allowed is False
    assert "configuration_change_requires_gitops_pr" in write_action.guardrails


def test_policy_escalates_linux_low_level_mutation() -> None:
    action = PolicyEngine().classify(
        "collect_linux_low_level_evidence",
        "ip link set eth0 down 명령을 실행합니다.",
        "네트워크 인터페이스 상태를 직접 변경합니다.",
    )

    assert action.policy == PolicyLevel.APPROVAL_REQUIRED
    assert action.automation_mode == "operator_approval"
    assert action.automation_allowed is False
    assert "mutation_requires_operator_approval" in action.guardrails
    assert "direct_network_mutation" in action.risk_factors


def test_policy_escalates_mutation_even_when_action_key_claims_auto_safe() -> None:
    action = PolicyEngine().classify(
        "collect_more_evidence",
        "systemctl restart kubelet 명령을 실행합니다.",
        "서비스 상태를 변경합니다.",
    )

    assert action.policy == PolicyLevel.APPROVAL_REQUIRED
    assert action.automation_mode == "operator_approval"
    assert action.automation_allowed is False
    assert action.requires_approval is True
    assert action.review_required is True
    assert "mutation_requires_operator_approval" in action.guardrails
    assert "systemd_unit_mutation" in action.risk_factors


def test_policy_blocks_destructive_commands_before_other_rules() -> None:
    action = PolicyEngine().classify(
        "collect_more_evidence",
        "kubectl delete node worker-3",
        "LLM이 빠른 복구 방법으로 제안했습니다.",
    )

    assert action.policy == PolicyLevel.NEVER_AUTO_EXECUTE
    assert action.automation_mode == "prohibited"
    assert action.automation_allowed is False
    assert "never_auto_execute_pattern" in action.guardrails
    assert "kubernetes_object_mutation" in action.risk_factors


def test_policy_forces_configuration_changes_through_gitops() -> None:
    action = PolicyEngine().classify(
        "collect_more_evidence",
        "CoreDNS ConfigMap 설정 변경 PR을 생성합니다.",
        "DNS timeout 완화를 위한 설정 변경입니다.",
    )

    assert action.policy == PolicyLevel.GITOPS_PR_ONLY
    assert action.automation_mode == "gitops_pr"
    assert action.automation_allowed is False
    assert action.requires_approval is False
    assert action.review_required is True
    assert "configuration_change_requires_gitops_pr" in action.guardrails


def test_policy_keeps_unknown_actions_manual() -> None:
    action = PolicyEngine().classify(
        "delete_node_now",
        "노드 상태를 운영자가 직접 확인합니다.",
        "정의되지 않은 action key입니다.",
    )

    assert action.policy == PolicyLevel.MANUAL_INVESTIGATION
    assert action.action_key == "manual_investigation"
    assert action.automation_mode == "manual"
    assert action.automation_allowed is False
    assert "unknown_action_key" in action.guardrails


def test_policy_marks_llm_output_as_non_automation_source() -> None:
    action = PolicyEngine().classify(
        "collect_more_evidence",
        "containerd 상태와 journal 로그를 추가 수집합니다.",
        "읽기 전용 확인입니다.",
        source="llm",
    )

    assert action.policy == PolicyLevel.AUTO_SAFE
    assert action.source == "llm"
    assert action.automation_mode == "read_only"
    assert action.automation_allowed is False
    assert "llm_output_cannot_trigger_direct_automation" in action.guardrails
