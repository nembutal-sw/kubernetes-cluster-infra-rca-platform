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


def write_result(root: Path, scenario: str, *, passed: bool = True) -> Path:
    directory = root / scenario
    directory.mkdir(parents=True)
    path = directory / "llm-staging-smoke-result.json"
    path.write_text(
        json.dumps(
            {
                "status": "passed" if passed else "failed",
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
