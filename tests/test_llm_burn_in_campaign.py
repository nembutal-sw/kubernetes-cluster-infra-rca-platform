import importlib.util
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-campaign.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_campaign", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def write_result(
    root: Path,
    scenario: str,
    *,
    passed: bool = True,
    sample_id: str | None = None,
    started_at: str = "2026-07-21T00:00:00Z",
) -> Path:
    directory = root / (sample_id or scenario)
    directory.mkdir(parents=True)
    path = directory / "llm-staging-smoke-result.json"
    path.write_text(
        json.dumps(
            {
                "status": "passed" if passed else "failed",
                "started_at": started_at,
                "scenario": scenario,
                "errors": [] if passed else ["provider unavailable"],
            }
        ),
        encoding="utf-8",
    )
    return path


def test_parse_scenarios_normalizes_and_deduplicates() -> None:
    campaign = load_module()

    scenarios = campaign.parse_scenarios(" Disk-Pressure,node-not-ready,disk-pressure ")

    assert scenarios == ["disk-pressure", "node-not-ready"]


def test_parse_scenarios_rejects_shell_like_values() -> None:
    campaign = load_module()

    try:
        campaign.parse_scenarios("disk-pressure;echo-secret")
    except ValueError as exc:
        assert "invalid scenario key" in str(exc)
    else:
        raise AssertionError("invalid scenario key was accepted")


def test_successful_counts_ignore_failed_results(tmp_path: Path) -> None:
    campaign = load_module()
    passed = write_result(tmp_path, "disk-pressure")
    failed = write_result(tmp_path, "memory-pressure", passed=False)

    counts = campaign.successful_scenario_counts([passed, failed])

    assert counts == Counter({"disk-pressure": 1})


def test_successful_time_buckets_ignore_invalid_results(tmp_path: Path) -> None:
    campaign = load_module()
    first = write_result(tmp_path, "disk-pressure", started_at="2026-07-21T00:00:00Z")
    second = write_result(
        tmp_path,
        "memory-pressure",
        sample_id="memory-later",
        started_at="2026-07-21T08:30:00Z",
    )
    invalid = write_result(
        tmp_path,
        "node-not-ready",
        sample_id="invalid-time",
        started_at="invalid",
    )

    buckets = campaign.successful_time_buckets([first, second, invalid], 8)

    assert buckets == {"2026-07-21T00:00:00Z", "2026-07-21T08:00:00Z"}


def test_history_validation_rejects_missing_or_empty_inputs(tmp_path: Path) -> None:
    campaign = load_module()

    assert "does not exist" in campaign.validate_history_inputs(
        [str(tmp_path / "missing")]
    )[0]
    assert "contain no" in campaign.validate_history_inputs([str(tmp_path)])[0]


def test_plan_prioritizes_uncovered_then_least_sampled_scenario() -> None:
    campaign = load_module()
    scenarios = [
        "disk-pressure",
        "node-not-ready",
        "inode-exhaustion",
        "network-link-flap",
        "memory-pressure",
    ]

    plan = campaign.build_plan(
        scenarios,
        Counter(
            {
                "disk-pressure": 1,
                "node-not-ready": 1,
                "inode-exhaustion": 1,
                "network-link-flap": 1,
            }
        ),
        provider_call_budget=3,
        target_samples=20,
        target_scenarios=5,
    )

    assert plan == ["memory-pressure", "disk-pressure", "node-not-ready"]


def test_plan_stops_when_targets_are_already_met() -> None:
    campaign = load_module()
    scenarios = list(campaign.DEFAULT_SCENARIOS)

    plan = campaign.build_plan(
        scenarios,
        Counter({scenario: 4 for scenario in scenarios}),
        provider_call_budget=10,
        target_samples=20,
        target_scenarios=5,
    )

    assert plan == []


def test_plan_adds_only_one_sample_for_a_new_time_bucket() -> None:
    campaign = load_module()
    scenarios = list(campaign.DEFAULT_SCENARIOS)

    plan = campaign.build_plan(
        scenarios,
        Counter({scenario: 4 for scenario in scenarios}),
        provider_call_budget=10,
        target_samples=20,
        target_scenarios=5,
        temporal_sample_needed=True,
    )

    assert plan == ["disk-pressure"]


def test_plan_blocks_calls_when_current_time_bucket_is_already_sampled() -> None:
    campaign = load_module()
    scenarios = list(campaign.DEFAULT_SCENARIOS)

    plan = campaign.build_plan(
        scenarios,
        Counter({"disk-pressure": 1}),
        provider_call_budget=1,
        target_samples=20,
        target_scenarios=5,
        calls_allowed=False,
    )

    assert plan == []


def test_new_time_bucket_mode_caps_effective_budget_at_one() -> None:
    campaign = load_module()

    assert campaign.effective_provider_call_budget(10, True) == 1
    assert campaign.effective_provider_call_budget(10, False) == 10


def test_smoke_command_enforces_single_call_without_password() -> None:
    campaign = load_module()

    command = campaign.smoke_command(
        scenario="memory-pressure",
        base_url="https://rca.example.com",
        username="admin",
        output_dir=Path("results"),
        max_llm_latency_ms=60000,
        task_timeout_seconds=240,
    )

    assert "--skip-connectivity-test" in command
    assert command[command.index("--provider-call-budget") + 1] == "1"
    assert "--require-usage-metadata" in command
    assert "--password" not in command
    assert all("secret" not in item for item in command)


def test_aggregate_command_forwards_time_coverage_gate() -> None:
    campaign = load_module()

    command = campaign.aggregate_command(
        inputs=["history"],
        output_path=Path("report.json"),
        target_samples=20,
        target_scenarios=5,
        target_time_buckets=3,
        time_bucket_hours=8,
        current_p95_ms=60000,
    )

    assert command[command.index("--minimum-time-buckets") + 1] == "3"
    assert command[command.index("--time-bucket-hours") + 1] == "8"
