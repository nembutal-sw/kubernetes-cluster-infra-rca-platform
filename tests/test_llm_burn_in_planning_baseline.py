import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-planning-baseline.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_planning_baseline", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def write_sample(
    root: Path,
    name: str,
    *,
    scenario: str = "disk-pressure",
    automation_allowed: bool = False,
    executable: bool = False,
) -> Path:
    directory = root / name
    directory.mkdir(parents=True)
    report_id = f"report-{name}"
    result = {
        "status": "passed",
        "started_at": "2026-07-20T16:30:00Z",
        "scenario": scenario,
        "report_id": report_id,
        "base_url": "https://private.example.invalid",
        "cluster_id": "sensitive-cluster-id",
        "errors": [],
    }
    result_path = directory / "llm-staging-smoke-result.json"
    result_path.write_text(json.dumps(result), encoding="utf-8")
    report = {
        "report_id": report_id,
        "evidence": [{"message": "sensitive node evidence"}],
        "recommended_actions": [
            {
                "source": "llm",
                "automation_allowed": automation_allowed,
                "execution_plan": {"executable": executable},
            }
        ],
    }
    (directory / f"report-{report_id}.json").write_text(json.dumps(report), encoding="utf-8")
    return result_path


def test_baseline_contains_only_planning_metadata(tmp_path: Path) -> None:
    baseline = load_module()
    result = write_sample(tmp_path, "one")

    payload = baseline.build_baseline([result], 8)
    serialized = json.dumps(payload)

    assert payload["schema_version"] == "llm-burn-in-planning-baseline/v1"
    assert payload["readiness_eligible"] is False
    assert payload["sample_count"] == 1
    assert payload["time_buckets"] == ["2026-07-20T16:00:00Z"]
    assert "private.example.invalid" not in serialized
    assert "sensitive-cluster-id" not in serialized
    assert "sensitive node evidence" not in serialized
    assert str(tmp_path) not in serialized


def test_baseline_deduplicates_identical_results(tmp_path: Path) -> None:
    baseline = load_module()
    first = write_sample(tmp_path / "first", "one")
    duplicate_dir = tmp_path / "second" / "one"
    duplicate_dir.mkdir(parents=True)
    duplicate = duplicate_dir / first.name
    duplicate.write_bytes(first.read_bytes())
    source_report = next(first.parent.glob("report-*.json"))
    (duplicate_dir / source_report.name).write_bytes(source_report.read_bytes())

    payload = baseline.build_baseline([first, duplicate], 8)

    assert payload["sample_count"] == 1


def test_baseline_rejects_unsafe_llm_action(tmp_path: Path) -> None:
    baseline = load_module()
    result = write_sample(tmp_path, "unsafe", automation_allowed=True)

    try:
        baseline.build_baseline([result], 8)
    except ValueError as exc:
        assert "unsafe LLM action" in str(exc)
    else:
        raise AssertionError("unsafe baseline sample was accepted")
