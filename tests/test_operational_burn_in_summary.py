import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "operational-burn-in-summary.py"


def load_module():
    spec = importlib.util.spec_from_file_location("operational_burn_in_summary", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def agent(status: str = "passed") -> dict:
    return {
        "schema_version": "agent-soak-validation/v1",
        "status": status,
        "profile": "smoke",
        "observability": {"agent_process_configured": False, "state_dir_configured": False},
        "metrics": {
            "iterations_completed": 3,
            "iterations_target": 3,
            "collection_success_rate": 1.0,
            "evidence_quality_rate": 1.0,
            "degraded_collector_rate": 0.0,
            "collection_duration_seconds": {"p95": 1.0},
            "maximum_payload_bytes": 1024,
            "process": {
                "rss_bytes": None,
                "fd_count": None,
                "thread_count": None,
                "cpu_percent": None,
            },
        },
        "failures": [],
        "warnings": [],
    }


def llm() -> dict:
    return {
        "status": "dry_run",
        "dry_run": True,
        "provider_call_upper_bound_used": 0,
        "provider_calls_allowed": False,
        "readiness": {
            "ready": False,
            "sample_count": 1,
            "sample_target": 20,
            "scenario_count": 1,
            "scenario_target": 5,
            "time_bucket_count": 1,
            "time_bucket_target": 3,
        },
    }


def test_summary_keeps_pending_llm_and_managed_canaries_as_visible_warnings() -> None:
    summary_module = load_module()
    matrix = json.loads((ROOT / "config" / "platform-compatibility-matrix.json").read_text(encoding="utf-8"))

    summary = summary_module.build_summary(
        agent(),
        None,
        llm(),
        matrix,
        require_real_cluster=False,
        input_errors=[],
    )

    assert summary["status"] == "warning"
    assert summary["components"]["llm_burn_in"]["readiness"] == "pending"
    assert summary["components"]["agent_soak"]["p95_cpu_percent"] is None
    assert summary["components"]["llm_burn_in"]["provider_calls_used"] == 0
    assert summary["components"]["platform_coverage"]["managed_canary_pending"] == [
        "eks",
        "aks",
        "gke",
        "openshift",
    ]
    assert any("--agent-pid" in item for item in summary["next_actions"])


def test_summary_fails_when_required_cluster_report_is_missing() -> None:
    summary_module = load_module()
    matrix = json.loads((ROOT / "config" / "platform-compatibility-matrix.json").read_text(encoding="utf-8"))

    summary = summary_module.build_summary(
        agent(),
        None,
        llm(),
        matrix,
        require_real_cluster=True,
        input_errors=[],
    )

    assert summary["status"] == "failed"
    assert "real-cluster readiness is required but unavailable" in summary["failures"]


def test_cluster_failures_override_an_incorrect_legacy_status() -> None:
    summary_module = load_module()

    component = summary_module.cluster_component(
        {"status": "passed", "signals": {}, "failures": ["helm command is required"], "warnings": []}
    )

    assert component["status"] == "failed"
    assert component["failure_count"] == 1


def test_markdown_contains_only_compact_operational_status() -> None:
    summary_module = load_module()
    matrix = json.loads((ROOT / "config" / "platform-compatibility-matrix.json").read_text(encoding="utf-8"))
    summary = summary_module.build_summary(
        agent(),
        None,
        llm(),
        matrix,
        require_real_cluster=False,
        input_errors=[],
    )

    rendered = summary_module.markdown(summary)
    assert "Operational Burn-in" in rendered
    assert "provider calls used: `0`" in rendered
    assert "node_name" not in rendered
