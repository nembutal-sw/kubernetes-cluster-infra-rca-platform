from typing import Any

from backend.app.config import LlmSettings
from backend.app.models import EvidenceBundle
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.llm import LlmAnalyzer, build_llm_analyzer
from backend.app.services.policy import PolicyEngine


class FakeLlmClient:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []

    def complete_json(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
        self.requests.append({"system_prompt": system_prompt, "user_payload": user_payload})
        return {
            "summary": {
                "most_likely_cause": "containerd socket failure blocks kubelet runtime operations",
                "confidence": "high",
                "reasoning": "runtime socket is unhealthy and kubelet is restarting",
            },
            "root_cause_candidates": [
                {
                    "cause": "containerd socket hang caused kubelet node status update failures",
                    "confidence": "high",
                    "supporting_signals": ["containerd_socket_unhealthy", "kubelet_unit_unhealthy"],
                    "evidence_paths": ["preprocessed_evidence.key_metrics.runtime"],
                }
            ],
            "additional_checks": [
                {
                    "component": "containerd",
                    "reason": "confirm runtime hang",
                    "command": "systemctl status containerd --no-pager",
                }
            ],
            "action_suggestions": [
                {
                    "action_key": "reboot_node",
                    "action": "노드 재부팅은 blocked task가 확인될 때만 최후 수단으로 검토합니다.",
                    "reason": "재부팅은 workload 영향이 커서 자동 실행하면 안 됩니다.",
                }
            ],
            "risk_notes": ["Do not execute remediation automatically."],
        }


def test_llm_analyzer_is_provider_neutral_and_report_safe() -> None:
    fake_client = FakeLlmClient()
    llm_analyzer = LlmAnalyzer(
        LlmSettings(provider="self_hosted", model="local-rca", base_url="http://llm.local/v1"),
        client=fake_client,
    )
    analyzer = RuleBasedRcaAnalyzer(PolicyEngine(), llm_analyzer=llm_analyzer)

    report = analyzer.analyze(
        "report-1",
        EvidenceBundle(
            cluster_id="cluster-1",
            node_name="worker-3",
            alert_name="NodeNotReady",
            collectors={
                "systemd": {"kubelet_status": "restarting", "kubelet_restart_count": 7},
                "runtime": {"containerd_socket_healthy": False},
            },
        ),
    )

    llm_section = _section(report.evidence, "llm_analysis")
    assert llm_section["analysis"]["status"] == "completed"
    assert llm_section["analysis"]["provider"] == "self_hosted"
    assert fake_client.requests[0]["user_payload"]["preprocessed_evidence"]["llm_input_policy"][
        "use_this_payload_only"
    ] is True
    assert any(candidate.cause.startswith("LLM 분석:") for candidate in report.root_cause_candidates)
    reboot_actions = [action for action in report.recommended_actions if "재부팅" in action.action]
    assert reboot_actions[0].policy == "NEVER_AUTO_EXECUTE"


def test_llm_analyzer_skips_when_configuration_is_incomplete() -> None:
    llm_analyzer = build_llm_analyzer(LlmSettings(provider="openai", model="gpt-test"))
    result = llm_analyzer.analyze({"alert": {}}, {"rule_candidates": []})

    assert result["status"] == "skipped"
    assert result["reason"] == "RCA_LLM_API_KEY is not configured"


def _section(evidence: list[dict[str, Any]], section_type: str) -> dict[str, Any]:
    for section in evidence:
        if section.get("type") == section_type:
            return section
    raise AssertionError(f"missing section: {section_type}")
