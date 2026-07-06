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
                "type": "llm_analysis",
                "analysis": {
                    "status": "completed",
                    "result": {
                        "summary": "Inode exhaustion is the likely trigger.",
                        "root_cause_candidates": [{"cause": "inode exhaustion"}],
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
