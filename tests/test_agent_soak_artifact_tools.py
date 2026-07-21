import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]


def load_script(name: str):
    path = ROOT / "scripts" / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def checkpoint(iteration: int) -> dict:
    targets = []
    for index, target_id in enumerate(("1" * 16, "2" * 16, "3" * 16)):
        targets.append(
            {
                "target_id": target_id,
                "process": {
                    "rss_bytes": 32 * 1024 * 1024 + iteration * 4096 + index * 1024,
                    "fd_count": 3,
                    "thread_count": 1,
                    "cpu_seconds": iteration * 0.01,
                    "sampled_at_monotonic": iteration * 60.0,
                    "process_start_ticks": 1000 + index,
                },
                "spool": {"pending_files": 0, "pending_bytes": 0, "quarantine_files": 0},
                "collection": {
                    "success": True,
                    "evidence_quality": True,
                    "duration_seconds": 0.1,
                    "payload_bytes": 1024,
                    "collector_count": 1,
                    "missing_collectors": [],
                    "invalid_schema_collectors": [],
                    "degraded_collectors": [],
                },
                "runtime_observation_error": None,
            }
        )
    return {
        "schema_version": "agent-soak-validation/v1",
        "iteration": iteration,
        "success": True,
        "evidence_quality": True,
        "degraded_collectors": [],
        "duration_seconds": 0.1,
        "payload_bytes": 1024,
        "health_probe_ok": True,
        "process": None,
        "spool": None,
        "targets": targets,
        "runtime_observation_error": None,
    }


def original_summary() -> dict:
    return {
        "schema_version": "agent-soak-validation/v1",
        "status": "passed",
        "profile": "smoke",
        "started_at": "2026-07-21T00:00:00+00:00",
        "read_only": True,
        "requested_collectors": ["node"],
        "observability": {
            "health_probe_configured": True,
            "agent_process_configured": True,
            "state_dir_configured": True,
            "runtime_observation_required": True,
            "runtime_observation_source": "fleet",
            "collector_execution_source": "platform_evidence_request",
            "minimum_fleet_target_count": 3,
        },
        "comparison_metadata": {
            "schema_version": "agent-soak-comparison-metadata/v1",
            "platform_family": "kind",
            "architecture": "amd64",
            "agent_version": "test-version",
        },
    }


def test_revalidation_rebuilds_redacted_fleet_with_current_thresholds() -> None:
    tool = load_script("agent-soak-revalidate.py")

    result = tool.revalidate(
        original_summary(),
        [checkpoint(1), checkpoint(2), checkpoint(3)],
        ROOT / "config" / "agent-soak-thresholds.json",
    )

    assert result["status"] == "passed"
    assert result["metrics"]["fleet"]["target_count"] == 3
    assert result["metrics"]["fleet"]["worst_rss_steady_state"]["minimum_sample_count"] == 3


def test_revalidation_rejects_infrastructure_identifiers(tmp_path: Path) -> None:
    tool = load_script("agent-soak-revalidate.py")
    point = checkpoint(1)
    point["targets"][0]["node_name"] = "sensitive-node"
    path = tmp_path / "checkpoints.jsonl"
    path.write_text(json.dumps(point) + "\n", encoding="utf-8")

    with pytest.raises(ValueError, match="forbidden infrastructure identifier"):
        tool.load_checkpoints(path, "agent-soak-validation/v1")


def test_comparison_contains_only_aggregate_metrics() -> None:
    comparison = load_script("agent-soak-comparison.py")
    revalidator = load_script("agent-soak-revalidate.py")
    baseline = revalidator.revalidate(
        original_summary(),
        [checkpoint(1), checkpoint(2), checkpoint(3)],
        ROOT / "config" / "agent-soak-thresholds.json",
    )
    candidate = json.loads(json.dumps(baseline))
    candidate["metrics"]["collector_execution"]["collection_duration_seconds"]["p95"] = 0.2

    result = comparison.compare(baseline, candidate)

    assert result["status"] == "passed"
    assert result["candidate_minus_baseline"]["p95_collection_seconds"] == pytest.approx(0.1)
    rendered = json.dumps(result)
    assert "target_id" not in rendered
    assert "1111111111111111" not in rendered


def test_comparison_rejects_incompatible_runtime_metadata() -> None:
    comparison = load_script("agent-soak-comparison.py")
    revalidator = load_script("agent-soak-revalidate.py")
    baseline = revalidator.revalidate(
        original_summary(),
        [checkpoint(1), checkpoint(2), checkpoint(3)],
        ROOT / "config" / "agent-soak-thresholds.json",
    )
    candidate = json.loads(json.dumps(baseline))
    candidate["comparison_metadata"]["architecture"] = "arm64"

    result = comparison.compare(baseline, candidate)

    assert result["status"] == "failed"
    assert result["compatibility"]["comparable"] is False
    assert "comparison metadata differs: architecture" in result["compatibility"]["reasons"]
    assert result["regression_gate"]["status"] == "not_evaluated"


def test_comparison_blocks_collection_latency_regression() -> None:
    comparison = load_script("agent-soak-comparison.py")
    revalidator = load_script("agent-soak-revalidate.py")
    baseline = revalidator.revalidate(
        original_summary(),
        [checkpoint(1), checkpoint(2), checkpoint(3)],
        ROOT / "config" / "agent-soak-thresholds.json",
    )
    candidate = json.loads(json.dumps(baseline))
    candidate["metrics"]["collector_execution"]["collection_duration_seconds"]["p95"] = 1.0

    result = comparison.compare(baseline, candidate)

    assert result["status"] == "failed"
    assert result["compatibility"]["status"] == "passed"
    assert "p95_collection_seconds" in result["regression_gate"]["violations"]
