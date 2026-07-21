import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

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
            "process_start_ticks": 1234,
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


def test_agent_pod_target_requires_safe_namespace_and_name() -> None:
    soak = load_module()

    assert soak.parse_agent_pod("rca-system/cluster-rca-agent-abc12") == (
        "rca-system",
        "cluster-rca-agent-abc12",
    )
    with pytest.raises(ValueError, match="namespace/name"):
        soak.parse_agent_pod("cluster-rca-agent-abc12")
    with pytest.raises(ValueError, match="DNS label"):
        soak.parse_agent_pod("RCA-System/cluster-rca-agent-abc12")


def test_pod_runtime_snapshot_uses_fixed_kubectl_exec_and_validates_output(monkeypatch) -> None:
    soak = load_module()
    captured = {}
    payload = {
        "process": {
            "rss_bytes": 1024,
            "fd_count": 7,
            "thread_count": 3,
            "cpu_seconds": 1.25,
            "sampled_at_monotonic": 5.0,
            "process_start_ticks": 1234,
        },
        "spool": {"pending_files": 1, "pending_bytes": 20, "quarantine_files": 0},
    }

    def fake_run(command, **kwargs):
        captured["command"] = command
        captured["kwargs"] = kwargs
        return SimpleNamespace(returncode=0, stdout=json.dumps(payload), stderr="")

    monkeypatch.setattr(soak.subprocess, "run", fake_run)
    process, spool, error = soak.pod_runtime_snapshot(
        "rca-system/cluster-rca-agent-abc12",
        "agent",
        "k3s-demo",
        30,
    )

    assert error is None
    assert process["rss_bytes"] == 1024
    assert spool["pending_files"] == 1
    assert captured["command"][:4] == ["kubectl", "--request-timeout=10s", "--context", "k3s-demo"]
    assert captured["command"][4:10] == [
        "exec",
        "--namespace",
        "rca-system",
        "cluster-rca-agent-abc12",
        "--container",
        "agent",
    ]
    assert captured["command"][-3:-1] == ["python", "-c"]
    assert "node_agent.main" in captured["command"][-1]
    assert "/proc/self/cgroup" in captured["command"][-1]
    assert "shell" not in captured["kwargs"]


def test_agent_pod_discovery_rejects_ambiguous_ready_daemonset(monkeypatch) -> None:
    soak = load_module()
    items = []
    for name in ("agent-a", "agent-b"):
        items.append(
            {
                "metadata": {"namespace": "rca-system", "name": name},
                "status": {
                    "phase": "Running",
                    "conditions": [{"type": "Ready", "status": "True"}],
                    "containerStatuses": [{"name": "agent", "ready": True}],
                },
            }
        )
    monkeypatch.setattr(
        soak.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout=json.dumps({"items": items}), stderr=""),
    )

    target, error = soak.discover_agent_pod("", "agent", 10)

    assert target is None
    assert "multiple Ready Agent Pods" in error


def test_summary_fails_when_observed_agent_process_restarts() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    points = [checkpoint(1), checkpoint(2), checkpoint(3)]
    points[2]["process"]["process_start_ticks"] = 9999

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
        runtime_observation_required=True,
        runtime_observation_source="pod",
    )

    assert summary["status"] == "failed"
    assert "Agent process restarted during validation" in summary["failures"]
    assert summary["metrics"]["process"]["identity"]["stable"] is False
