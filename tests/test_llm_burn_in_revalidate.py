import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-revalidate.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_revalidate", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sample_payloads(error: str):
    analysis = {
        "status": "completed",
        "provider": "gemini",
        "model": "gemini-3.1-flash-lite",
        "prompt_version": "llm-rca-analyzer/v2",
        "latency_ms": 3267,
        "usage": {
            "usage_available": True,
            "input_tokens": 100,
            "output_tokens": 20,
            "total_tokens": 120,
            "cost_estimation_enabled": False,
            "estimated_cost_usd": 0.0,
        },
        "result": {
            "summary": "Disk pressure on burn-in-disk-pressure-node.",
            "root_cause_candidates": [],
            "action_suggestions": [],
            "additional_checks": [],
        },
    }
    report = {
        "evidence": [
            {"type": "llm_analysis", "analysis": analysis},
        ],
        "recommended_actions": [],
    }
    result = {
        "status": "failed",
        "started_at": "2026-07-21T02:34:46+00:00",
        "scenario": "disk-pressure",
        "task": {"status": "completed"},
        "report_id": "report-1",
        "llm": {
            "enabled": True,
            "provider": "gemini",
            "model": "gemini-3.1-flash-lite",
            "spring_ai_chat_model": "google-genai",
            "credential_required": True,
            "credential_configured": True,
            "max_attempts": 1,
            "provider_retry_max_attempts": 1,
        },
        "connectivity_test": {"outcome": "skipped"},
        "llm_analysis": analysis,
        "limits": {
            "provider_call_budget": 1,
            "require_usage_metadata": True,
            "max_llm_latency_ms": 60000,
            "max_estimated_cost_usd": 0,
        },
        "errors": [error],
    }
    return result, report


def test_revalidates_known_secret_scanner_false_positive_without_provider_call():
    module = load_module()
    smoke = module.load_smoke_module()
    result, report = sample_payloads(
        "llm_analysis appears to contain an unredacted secret-like value"
    )

    corrected, changed = module.revalidate_sample(smoke, result, report)

    assert changed is True
    assert corrected["status"] == "passed"
    assert corrected["errors"] == []
    assert corrected["revalidation"]["provider_reinvoked"] is False


def test_revalidation_rejects_unrelated_failures():
    module = load_module()
    smoke = module.load_smoke_module()
    result, report = sample_payloads("LLM provider request failed")

    with pytest.raises(ValueError, match="non-recoverable"):
        module.revalidate_sample(smoke, result, report)
