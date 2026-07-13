import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-staging-smoke.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_staging_smoke", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_llm_config_requires_enabled_provider_model_and_credential():
    smoke = load_module()

    errors = smoke.validate_llm_configuration(
        {
            "llm": {
                "enabled": True,
                "provider": "openai",
                "model": "",
                "spring_ai_chat_model": "openai-sdk",
                "credential_required": True,
                "credential_configured": False,
            }
        },
        allow_disabled=False,
    )

    assert "LLM model is not configured" in errors
    assert any("SPRING_AI" in error or "credential" in error for error in errors)


def test_llm_config_allows_disabled_only_when_explicit():
    smoke = load_module()

    strict_errors = smoke.validate_llm_configuration({"llm": {"enabled": False}}, allow_disabled=False)
    allowed_errors = smoke.validate_llm_configuration({"llm": {"enabled": False}}, allow_disabled=True)

    assert strict_errors == ["LLM is disabled on the target platform"]
    assert allowed_errors == []


def test_llm_connectivity_enforces_outcome_latency_and_content():
    smoke = load_module()

    valid = smoke.validate_llm_connectivity(
        {"outcome": "completed", "latency_ms": 1200, "response_chars": 2, "error": ""},
        allow_disabled=False,
        max_latency_ms=5000,
    )
    invalid = smoke.validate_llm_connectivity(
        {"outcome": "failed", "latency_ms": 6001, "response_chars": 0, "error": "provider error"},
        allow_disabled=False,
        max_latency_ms=5000,
    )

    assert valid == []
    assert "LLM connectivity test outcome is 'failed'" in invalid
    assert "LLM connectivity latency 6001ms exceeds 5000ms" in invalid


def test_llm_connectivity_secret_check_allows_redaction_and_rejects_json_secrets():
    smoke = load_module()

    redacted = smoke.validate_llm_connectivity(
        {
            "outcome": "completed",
            "latency_ms": 100,
            "response_chars": 2,
            "authorization": "[redacted]",
        },
        allow_disabled=False,
        max_latency_ms=5000,
    )
    exposed = smoke.validate_llm_connectivity(
        {
            "outcome": "completed",
            "latency_ms": 100,
            "response_chars": 2,
            "authorization": "Bearer super-secret-value",
        },
        allow_disabled=False,
        max_latency_ms=5000,
    )

    assert redacted == []
    assert "LLM connectivity response appears to contain an unredacted secret-like value" in exposed


def test_llm_report_rejects_automated_llm_actions():
    smoke = load_module()
    report = {
        "evidence": [
            {
                "type": "llm_analysis",
                "analysis": {
                    "status": "completed",
                    "result": {
                        "summary": "Disk pressure likely caused kubelet delay.",
                        "root_cause_candidates": [],
                        "additional_checks": [],
                    },
                },
            }
        ],
        "recommended_actions": [
            {
                "source": "llm",
                "action_key": "restart_kubelet",
                "automation_allowed": True,
                "execution_plan": {"executable": True},
            }
        ],
    }

    errors = smoke.validate_llm_report(
        report,
        expected_statuses={"completed"},
        allow_disabled=False,
    )

    assert "LLM action restart_kubelet has automation_allowed=true" in errors
    assert "LLM action restart_kubelet has executable=true" in errors


def test_llm_report_accepts_completed_diagnostic_output():
    smoke = load_module()
    report = {
        "evidence": [
            {
                "type": "preprocessed_evidence",
                "payload": {
                    "evidence_catalog": [
                        {"evidence_id": "ev-inode", "signal": "inode_exhaustion"}
                    ]
                },
            },
            {
                "type": "llm_analysis",
                "analysis": {
                    "status": "completed",
                    "prompt_version": "llm-rca-analyzer/v2",
                    "latency_ms": 1500,
                    "usage": {
                        "usage_available": True,
                        "input_tokens": 100,
                        "output_tokens": 25,
                        "total_tokens": 125,
                        "cost_estimation_enabled": True,
                        "estimated_cost_usd": 0.001,
                    },
                    "result": {
                        "summary": "Inode exhaustion is the likely trigger.",
                        "root_cause_candidates": [
                            {
                                "cause": "inode exhaustion",
                                "supporting_evidence_ids": ["ev-inode"],
                            }
                        ],
                    },
                },
            }
        ],
        "recommended_actions": [
            {
                "source": "llm",
                "action_key": "inspect_storage_state",
                "automation_allowed": False,
                "execution_plan": {"executable": False},
            }
        ],
    }

    assert smoke.validate_llm_report(report, expected_statuses={"completed"}, allow_disabled=False) == []


def test_llm_report_enforces_usage_latency_and_cost_limits():
    smoke = load_module()
    report = {
        "evidence": [
            {
                "type": "llm_analysis",
                "analysis": {
                    "status": "completed",
                    "prompt_version": "llm-rca-analyzer/v2",
                    "latency_ms": 61000,
                    "usage": {
                        "usage_available": False,
                        "input_tokens": 0,
                        "output_tokens": 0,
                        "total_tokens": 0,
                        "cost_estimation_enabled": True,
                        "estimated_cost_usd": 0.02,
                    },
                    "result": {"summary": "diagnostic", "root_cause_candidates": []},
                },
            }
        ],
        "recommended_actions": [],
    }

    errors = smoke.validate_llm_report(
        report,
        expected_statuses={"completed"},
        allow_disabled=False,
        require_usage_metadata=True,
        max_latency_ms=60000,
        max_estimated_cost_usd=0.01,
    )

    assert "llm_analysis latency 61000ms exceeds 60000ms" in errors
    assert "LLM provider did not return required token usage metadata" in errors
    assert "llm_analysis estimated cost $0.02 exceeds $0.01" in errors
