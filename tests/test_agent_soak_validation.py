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


def test_legacy_threshold_profile_receives_conservative_fleet_defaults(tmp_path: Path) -> None:
    soak = load_module()
    payload = json.loads((ROOT / "config" / "agent-soak-thresholds.json").read_text(encoding="utf-8"))
    for profile in payload["profiles"].values():
        for key in soak.FLEET_PROFILE_DEFAULTS:
            profile.pop(key)
        for key in soak.STEADY_STATE_PROFILE_DEFAULTS:
            profile.pop(key)
    path = tmp_path / "legacy-thresholds.json"
    path.write_text(json.dumps(payload), encoding="utf-8")

    _, profile = soak.load_configuration(path, "smoke")

    assert profile["maximum_fleet_rss_peak_spread_mb"] == 128
    assert profile["maximum_fleet_p95_cpu_spread_percent"] == 100
    assert profile["rss_steady_state_warmup_fraction"] == 0.5
    assert profile["enforce_rss_steady_state"] is False
    assert profile["maximum_rss_steady_state_slope_mb_per_hour"] == 1024


def test_rss_steady_state_excludes_warmup_and_reports_recent_windows() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "standard")
    mib = 1024 * 1024
    points = []
    for iteration in range(1, 61):
        rss = (32 + min(iteration, 30) * 0.7) * mib
        if iteration > 30:
            rss = (53 + (iteration % 3) * 0.1) * mib
        point = checkpoint(iteration, rss=int(rss))
        point["process"]["sampled_at_monotonic"] = float(iteration * 60)
        points.append(point)

    summary = soak.build_summary(
        profile_name="standard",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=points,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=False,
        process_configured=True,
        spool_configured=True,
        interrupted=False,
    )

    steady = summary["metrics"]["process"]["rss_bytes"]["steady_state"]
    assert summary["status"] == "passed"
    assert steady["warmup_samples_excluded"] == 30
    assert steady["sample_count"] == 30
    assert steady["sufficient_samples"] is True
    assert steady["recent_windows"]["last_10"]["sample_count"] == 10
    assert steady["recent_windows"]["last_30"]["sample_count"] == 30
    assert abs(steady["slope_bytes_per_hour"] / mib) < 1


def test_rss_steady_state_fails_persistent_post_warmup_growth() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "standard")
    mib = 1024 * 1024
    points = []
    for iteration in range(1, 61):
        point = checkpoint(iteration, rss=(32 + max(0, iteration - 30)) * mib)
        point["process"]["sampled_at_monotonic"] = float(iteration * 60)
        points.append(point)

    summary = soak.build_summary(
        profile_name="standard",
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
    assert "Agent RSS steady-state slope exceeds threshold" in summary["failures"]
    assert "Agent RSS steady-state range exceeds threshold" in summary["failures"]


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


def test_fleet_runtime_snapshots_collect_each_target_without_pod_refs(monkeypatch) -> None:
    soak = load_module()
    observed_pods = []

    def fake_snapshot(agent_pod, container, context, timeout_seconds):
        observed_pods.append((agent_pod, container, context, timeout_seconds))
        suffix = 1 if agent_pod.endswith("agent-a") else 2
        return (
            {
                "rss_bytes": 1024 * suffix,
                "fd_count": 5,
                "thread_count": 2,
                "cpu_seconds": float(suffix),
                "sampled_at_monotonic": 5.0,
                "process_start_ticks": 1000 + suffix,
            },
            {"pending_files": 0, "pending_bytes": 0, "quarantine_files": 0},
            None,
        )

    monkeypatch.setattr(soak, "pod_runtime_snapshot", fake_snapshot)
    observations = soak.fleet_runtime_snapshots(
        [
            {"agent_pod": "rca-system/agent-b", "target_id": "2222222222222222"},
            {"agent_pod": "rca-system/agent-a", "target_id": "1111111111111111"},
        ],
        "agent",
        "kind-rca",
        10,
    )

    assert [item[0] for item in sorted(observed_pods)] == [
        "rca-system/agent-a",
        "rca-system/agent-b",
    ]
    assert all(item[1:] == ("agent", "kind-rca", 10) for item in observed_pods)
    assert [item["target_id"] for item in observations] == [
        "1111111111111111",
        "2222222222222222",
    ]
    assert all("agent_pod" not in item for item in observations)


def test_agent_pod_discovery_rejects_ambiguous_ready_daemonset(monkeypatch) -> None:
    soak = load_module()
    items = []
    for name in ("agent-a", "agent-b"):
        items.append(
                {
                    "metadata": {"namespace": "rca-system", "name": name},
                    "spec": {"nodeName": f"worker-{name[-1]}"},
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


def fleet_checkpoints(target_ids: list[str], *, rss_peaks: list[int] | None = None) -> list[dict]:
    rss_peaks = rss_peaks or [1000 + index * 100 for index in range(len(target_ids))]
    points = []
    for iteration in range(1, 4):
        point = checkpoint(iteration)
        point["targets"] = []
        point["runtime_observation_error"] = None
        for index, target_id in enumerate(target_ids):
            point["targets"].append(
                {
                    "target_id": target_id,
                    "process": {
                        "rss_bytes": rss_peaks[index] - 20 + iteration * 5,
                        "fd_count": 5 + index,
                        "thread_count": 2,
                        "cpu_seconds": iteration * (0.01 + index * 0.01),
                        "sampled_at_monotonic": float(iteration),
                        "process_start_ticks": 1000 + index,
                    },
                    "spool": {"pending_files": 0, "pending_bytes": 0, "quarantine_files": 0},
                    "runtime_observation_error": None,
                }
            )
        points.append(point)
    return points


def test_ready_agent_pod_targets_are_deterministic_and_redacted() -> None:
    soak = load_module()
    payload = {
        "items": [
                {
                    "metadata": {"namespace": "rca-system", "name": name},
                    "spec": {"nodeName": f"worker-{name[-1]}"},
                    "status": {
                    "phase": "Running",
                    "conditions": [{"type": "Ready", "status": "True"}],
                    "containerStatuses": [{"name": "agent", "ready": True}],
                },
            }
            for name in ("agent-b", "agent-a")
        ]
    }

    targets = soak.ready_agent_pod_targets(payload, "agent", b"phase-19-test-salt")
    other_run_targets = soak.ready_agent_pod_targets(payload, "agent", b"another-run-salt")

    assert [item["agent_pod"] for item in targets] == ["rca-system/agent-a", "rca-system/agent-b"]
    assert [item["node_name"] for item in targets] == ["worker-a", "worker-b"]
    assert all(len(item["target_id"]) == 16 for item in targets)
    assert all("agent" not in item["target_id"] for item in targets)
    assert [item["target_id"] for item in targets] != [item["target_id"] for item in other_run_targets]


def test_fleet_summary_passes_three_stable_targets_without_exposing_pod_names() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    target_ids = ["1111111111111111", "2222222222222222", "3333333333333333"]

    summary = soak.build_fleet_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=fleet_checkpoints(target_ids),
        target_ids=target_ids,
        minimum_target_count=3,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=True,
        interrupted=False,
    )

    assert summary["status"] == "passed"
    assert summary["observability"]["runtime_observation_source"] == "fleet"
    assert summary["metrics"]["fleet"]["target_count"] == 3
    assert summary["metrics"]["fleet"]["passed_target_count"] == 3
    assert summary["metrics"]["fleet"]["variation"]["rss_peak_bytes"]["spread"] == 200
    assert summary["metrics"]["fleet"]["worst_rss_steady_state"]["minimum_sample_count"] == 3
    assert summary["metrics"]["fleet"]["worst_rss_steady_state"]["maximum_range_bytes"] == 10
    assert summary["metrics"]["process"]["identity"]["stable"] is True
    rendered = json.dumps(summary)
    assert "rca-system" not in rendered
    assert "agent-a" not in rendered


def test_fleet_summary_fails_minimum_count_and_rss_variation() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    profile["maximum_fleet_rss_peak_spread_mb"] = 1
    target_ids = ["1111111111111111", "2222222222222222"]

    summary = soak.build_fleet_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=fleet_checkpoints(target_ids, rss_peaks=[1000, 3 * 1024 * 1024]),
        target_ids=target_ids,
        minimum_target_count=3,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=False,
        interrupted=False,
    )

    assert summary["status"] == "failed"
    assert "discovered 2 of 3 required Agent Pods" in summary["failures"]
    assert "Agent fleet RSS peak spread exceeds threshold" in summary["failures"]


def test_platform_fleet_summary_uses_per_agent_collection_observations() -> None:
    soak = load_module()
    collectors, profile = soak.load_configuration(ROOT / "config" / "agent-soak-thresholds.json", "smoke")
    target_ids = ["1111111111111111", "2222222222222222", "3333333333333333"]
    points = fleet_checkpoints(target_ids)
    for point in points:
        for target in point["targets"]:
            target["collection"] = {
                "success": True,
                "evidence_quality": True,
                "collector_count": len(collectors),
                "missing_collectors": [],
                "invalid_schema_collectors": [],
                "degraded_collectors": [],
                "duration_seconds": 0.5 + point["iteration"] * 0.1,
                "payload_bytes": 2048,
                "error": None,
            }

    summary = soak.build_fleet_summary(
        profile_name="smoke",
        profile=profile,
        requested_collectors=collectors,
        checkpoints=points,
        target_ids=target_ids,
        minimum_target_count=3,
        started_at="2026-07-21T00:00:00+00:00",
        health_configured=True,
        interrupted=False,
        collector_execution_source="platform_evidence_request",
    )

    collector = summary["metrics"]["collector_execution"]
    runtime = summary["metrics"]["fleet_runtime"]
    assert summary["status"] == "passed"
    assert collector["source"] == "platform_evidence_request"
    assert collector["target_count"] == 3
    assert collector["observation_count"] == 9
    assert collector["successful_observations"] == 9
    assert collector["evidence_quality_rate"] == 1.0
    assert collector["collection_duration_seconds"]["p95"] == pytest.approx(0.8)
    assert runtime["source"] == "kubernetes_pod"
    assert runtime["target_count"] == 3


def test_fleet_evidence_observations_discard_raw_collector_payload() -> None:
    soak = load_module()

    class FakeClient:
        def collect_fleet(self, targets, collectors, **kwargs):
            return [
                {
                    "target_id": target["target_id"],
                    "success": True,
                    "duration_seconds": 0.4,
                    "payload_bytes": 512,
                    "collectors": {
                        name: {"_schema_version": "collector-evidence/v1", "status": "ok", "secret": "raw"}
                        for name in collectors
                    },
                    "error": None,
                }
                for target in targets
            ]

    result = soak.fleet_evidence_observations(
        FakeClient(),
        [{"node_name": "worker-a", "target_id": "1111111111111111"}],
        ["node", "disk"],
        iteration=1,
        timeout_seconds=10,
    )

    assert result[0]["evidence_quality"] is True
    assert result[0]["collector_count"] == 2
    assert "collectors" not in result[0]
    assert "raw" not in json.dumps(result)
