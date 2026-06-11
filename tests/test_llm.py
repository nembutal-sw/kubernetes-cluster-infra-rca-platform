import json
from typing import Any

from backend.app.config import LlmSettings
from backend.app.models import EvidenceBundle
from backend.app.services import llm as llm_module
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.llm import HttpLlmClient, LlmAnalyzer, build_llm_analyzer
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


def test_llm_analyzer_redacts_sensitive_provider_errors() -> None:
    class FailingClient:
        def complete_json(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
            raise RuntimeError(
                "provider rejected request: Authorization: Bearer secret-token api_key=secret-key key=gemini-key"
            )

    llm_analyzer = LlmAnalyzer(
        LlmSettings(provider="self_hosted", model="local-rca", base_url="http://llm.local/v1"),
        client=FailingClient(),
    )

    result = llm_analyzer.analyze({"alert": {}}, {"rule_candidates": []})

    assert result["status"] == "failed"
    assert "secret-token" not in result["error"]
    assert "secret-key" not in result["error"]
    assert "gemini-key" not in result["error"]
    assert result["error"].count("<redacted>") >= 3


def test_llm_analyzer_normalizes_untrusted_model_output() -> None:
    class UnsafeClient:
        def complete_json(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
            return {
                "summary": {
                    "most_likely_cause": "containerd hang",
                    "confidence": "certain",
                    "reasoning": "based on runtime evidence",
                },
                "root_cause_candidates": [
                    {
                        "cause": "containerd hang",
                        "confidence": "high",
                        "supporting_signals": ["containerd_socket_unhealthy"],
                        "evidence_paths": [
                            "preprocessed_evidence.key_metrics.runtime",
                            "raw_collectors.runtime.stderr",
                        ],
                    }
                ],
                "additional_checks": [
                    {
                        "component": "kubelet",
                        "reason": "unsafe mutation must be dropped",
                        "command": "systemctl restart kubelet",
                    }
                ],
                "action_suggestions": [
                    {
                        "action_key": "delete_node",
                        "action": "delete the node immediately",
                        "reason": "unsupported action key should become manual investigation",
                    }
                ],
                "risk_notes": ["never execute provider output directly"],
            }

    analyzer = LlmAnalyzer(
        LlmSettings(provider="self_hosted", model="local", base_url="http://llm.local/v1"),
        client=UnsafeClient(),
    )
    result = analyzer.analyze({"alert": {"alert_name": "NodeNotReady"}}, {"rule_candidates": []})

    normalized = result["result"]
    assert normalized["summary"]["confidence"] == "low"
    assert normalized["root_cause_candidates"][0]["evidence_paths"] == [
        "preprocessed_evidence.key_metrics.runtime"
    ]
    assert normalized["additional_checks"][0]["command"] == ""
    assert normalized["action_suggestions"][0]["action_key"] == "manual_investigation"


def test_openai_compatible_client_uses_chat_completions_contract(monkeypatch) -> None:
    calls: list[dict[str, Any]] = []

    def fake_post_json(endpoint: str, headers: dict[str, str], body: dict[str, Any], timeout: float) -> dict[str, Any]:
        calls.append({"endpoint": endpoint, "headers": headers, "body": body, "timeout": timeout})
        return {"choices": [{"message": {"content": json.dumps(_minimal_llm_result())}}]}

    monkeypatch.setattr(llm_module, "_post_json", fake_post_json)
    client = HttpLlmClient(
        LlmSettings(
            provider="openai_compatible",
            model="local-rca",
            base_url="http://llm.local/v1",
            timeout_seconds=3.0,
            max_output_tokens=512,
        )
    )

    result = client.complete_json("system", {"payload": True})

    assert result["summary"]["most_likely_cause"] == "containerd hang"
    assert calls[0]["endpoint"] == "http://llm.local/v1/chat/completions"
    assert calls[0]["headers"] == {"Content-Type": "application/json"}
    assert calls[0]["body"]["model"] == "local-rca"
    assert calls[0]["body"]["response_format"] == {"type": "json_object"}
    assert calls[0]["body"]["max_tokens"] == 512
    assert calls[0]["timeout"] == 3.0


def test_anthropic_client_uses_messages_contract(monkeypatch) -> None:
    calls: list[dict[str, Any]] = []

    def fake_post_json(endpoint: str, headers: dict[str, str], body: dict[str, Any], timeout: float) -> dict[str, Any]:
        calls.append({"endpoint": endpoint, "headers": headers, "body": body, "timeout": timeout})
        return {"content": [{"type": "text", "text": json.dumps(_minimal_llm_result())}]}

    monkeypatch.setattr(llm_module, "_post_json", fake_post_json)
    client = HttpLlmClient(LlmSettings(provider="anthropic", model="claude-test", api_key="secret"))

    result = client.complete_json("system", {"payload": True})

    assert result["summary"]["most_likely_cause"] == "containerd hang"
    assert calls[0]["endpoint"] == "https://api.anthropic.com/v1/messages"
    assert calls[0]["headers"]["x-api-key"] == "secret"
    assert calls[0]["headers"]["anthropic-version"] == "2023-06-01"
    assert calls[0]["body"]["system"] == "system"
    assert calls[0]["body"]["messages"][0]["role"] == "user"


def test_gemini_client_uses_generate_content_contract(monkeypatch) -> None:
    calls: list[dict[str, Any]] = []

    def fake_post_json(endpoint: str, headers: dict[str, str], body: dict[str, Any], timeout: float) -> dict[str, Any]:
        calls.append({"endpoint": endpoint, "headers": headers, "body": body, "timeout": timeout})
        return {"candidates": [{"content": {"parts": [{"text": json.dumps(_minimal_llm_result())}]}}]}

    monkeypatch.setattr(llm_module, "_post_json", fake_post_json)
    client = HttpLlmClient(LlmSettings(provider="gemini", model="gemini-test", api_key="secret key"))

    result = client.complete_json("system", {"payload": True})

    assert result["summary"]["most_likely_cause"] == "containerd hang"
    assert calls[0]["endpoint"].startswith(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"
    )
    assert "key=secret%20key" in calls[0]["endpoint"]
    assert calls[0]["headers"] == {"Content-Type": "application/json"}
    assert calls[0]["body"]["generationConfig"]["response_mime_type"] == "application/json"
    assert calls[0]["body"]["contents"][0]["parts"][0]["text"].startswith("system")


def _section(evidence: list[dict[str, Any]], section_type: str) -> dict[str, Any]:
    for section in evidence:
        if section.get("type") == section_type:
            return section
    raise AssertionError(f"missing section: {section_type}")


def _minimal_llm_result() -> dict[str, Any]:
    return {
        "summary": {
            "most_likely_cause": "containerd hang",
            "confidence": "medium",
            "reasoning": "runtime socket is unhealthy",
        },
        "root_cause_candidates": [],
        "additional_checks": [],
        "action_suggestions": [],
        "risk_notes": [],
    }
