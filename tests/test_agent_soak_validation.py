import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "agent-soak-validation.py"


def load_module():
    spec = importlib.util.spec_from_file_location("agent_soak_validation", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def checkpoint(iteration: int, *, rss: int = 100, fd: int = 5, threads: int = 2) -> dict:
    return {
        "iteration": iteration,
        "success": True,
        "evidence_quality": True,
        "degraded_collectors": [],
        "duration_seconds": 1.0,
        "payload_bytes": 1024,
        "health_probe_ok": True,
        "process": {
            "rss_bytes": rss,
            "fd_count": fd,
            "thread_count": threads,
            "cpu_seconds": iteration * 0.1,
            "sampled_at_monotonic": float(iteration),
        },
        "spool": {"pending_files": 0, "pending_bytes": 0, "quarantine_files": 0},
    }


def test_threshold_catalog_has_bounded_workflow_and_local_profiles() -> None:
    soak = load_module()
    collectors, smoke = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    _, extended = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "extended")
    _, production = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "production")

    assert len(collectors) == 14
    assert smoke["iterations"] == 3
    assert extended["iterations"] * extended["interval_seconds"] <= 5 * 60 * 60
    assert production["iterations"] * production["interval_seconds"] >= 24 * 60 * 60


def test_evidence_quality_requires_every_requested_collector_schema() -> None:
    soak = load_module()
    valid = {
        "collectors": {
            "node": {"_schema_version": "collector-evidence/v1", "status": "ok"},
            "disk": {"_schema_version": "collector-evidence/v1", "status": "error"},
        }
    }

    result = soak.evaluate_evidence(valid, ["node", "disk"])
    assert result["quality"] is True
    assert result["degraded"] == ["disk"]

    invalid = soak.evaluate_evidence({"collectors": {"node": {"status": "ok"}}}, ["node", "disk"])
    assert invalid["quality"] is False
    assert invalid["missing"] == ["disk"]
    assert invalid["invalid_schema"] == ["disk", "node"]


def test_summary_passes_stable_observed_agent() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    points = [checkpoint(1), checkpoint(2, rss=110), checkpoint(3, rss=120)]

    summary = soak.build_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=points,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=True,
        process_configured=True,
        spool_configured=True,
        interrupted=False,
    )

    assert summary["status"] == "passed"
    assert summary["metrics"]["collection_success_rate"] == 1.0
    assert summary["metrics"]["process"]["rss_bytes"]["growth"] == 20
    assert summary["metrics"]["process"]["cpu_percent"]["p95"] == pytest.approx(10.0)


def test_summary_fails_resource_growth_and_missing_iterations() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    points = [checkpoint(1, rss=0), checkpoint(2, rss=70 * 1024 * 1024)]

    summary = soak.build_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=points,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=False,
        process_configured=True,
        spool_configured=True,
        interrupted=False,
    )

    assert summary["status"] == "failed"
    assert any("required iterations" in item for item in summary["failures"])
    assert any("RSS growth" in item for item in summary["failures"])


def test_summary_fails_when_agent_p95_cpu_exceeds_profile() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    points = [checkpoint(1), checkpoint(2), checkpoint(3)]
    points[1]["process"]["cpu_seconds"] = 2.0
    points[2]["process"]["cpu_seconds"] = 4.0

    summary = soak.build_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=points,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=False,
        process_configured=True,
        spool_configured=True,
        interrupted=False,
    )

    assert summary["status"] == "failed"
    assert "Agent p95 CPU usage exceeds threshold" in summary["failures"]


def test_health_probe_url_rejects_embedded_credentials_and_queries() -> None:
    soak = load_module()
    with pytest.raises(ValueError, match="credentials"):
        soak.validate_health_url("https://user:secret@example.test/health")
    with pytest.raises(ValueError, match="query"):
        soak.validate_health_url("https://example.test/health?token=secret")
