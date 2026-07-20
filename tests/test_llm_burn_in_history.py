import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-history.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_history", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def write_sample(
    root: Path,
    sample: str,
    *,
    scenario: str = "disk-pressure",
    status: str = "passed",
    report_id: str = "report-1",
    started_at: str = "2026-07-21T00:00:00Z",
    include_report: bool = True,
) -> Path:
    directory = root / sample
    directory.mkdir(parents=True)
    result_path = directory / "llm-staging-smoke-result.json"
    result_path.write_text(
        json.dumps(
            {
                "status": status,
                "started_at": started_at,
                "scenario": scenario,
                "report_id": report_id,
                "llm": {"provider": "gemini", "model": "gemini-test"},
                "errors": [] if status == "passed" else ["provider unavailable"],
            }
        ),
        encoding="utf-8",
    )
    if include_report:
        (directory / f"report-{report_id}.json").write_text(
            json.dumps({"report_id": report_id, "recommended_actions": []}),
            encoding="utf-8",
        )
    return result_path


def test_build_history_deduplicates_and_uses_relative_paths(tmp_path: Path) -> None:
    history = load_module()
    first = write_sample(tmp_path / "first", "sample")
    duplicate = tmp_path / "second" / "sample"
    duplicate.mkdir(parents=True)
    (duplicate / first.name).write_bytes(first.read_bytes())
    source_report = first.parent / "report-report-1.json"
    (duplicate / source_report.name).write_bytes(source_report.read_bytes())
    output = tmp_path / "output"
    history.ensure_empty_output(output)

    manifest = history.build_history([first, duplicate / first.name], output)

    assert manifest["status"] == "passed"
    assert manifest["sample_count"] == 1
    assert manifest["passed_sample_count"] == 1
    entry = manifest["samples"][0]
    assert entry["result"].startswith("samples/")
    assert not Path(entry["result"]).is_absolute()
    assert str(tmp_path) not in json.dumps(manifest)
    assert (output / entry["result"]).is_file()
    assert (output / entry["report"]).is_file()


def test_failed_sample_is_preserved_without_requiring_report(tmp_path: Path) -> None:
    history = load_module()
    failed = write_sample(
        tmp_path,
        "failed",
        status="failed",
        include_report=False,
    )
    output = tmp_path / "output"
    history.ensure_empty_output(output)

    manifest = history.build_history([failed], output)

    assert manifest["status"] == "passed"
    assert manifest["sample_count"] == 1
    assert manifest["passed_sample_count"] == 0
    assert manifest["failed_sample_count"] == 1
    assert manifest["samples"][0]["report"] is None


def test_passed_sample_without_report_fails_bundle_validation(tmp_path: Path) -> None:
    history = load_module()
    result = write_sample(tmp_path, "missing-report", include_report=False)
    output = tmp_path / "output"
    history.ensure_empty_output(output)

    manifest = history.build_history([result], output)

    assert manifest["status"] == "failed"
    assert manifest["passed_sample_count"] == 0
    assert "missing its sibling RCA report" in manifest["validation_errors"][0]


def test_passed_sample_requires_valid_timestamp(tmp_path: Path) -> None:
    history = load_module()
    result = write_sample(tmp_path, "invalid-time", started_at="invalid")
    output = tmp_path / "output"
    history.ensure_empty_output(output)

    manifest = history.build_history([result], output)

    assert manifest["status"] == "failed"
    assert "valid started_at" in manifest["validation_errors"][0]


def test_ensure_empty_output_rejects_existing_content(tmp_path: Path) -> None:
    history = load_module()
    output = tmp_path / "output"
    output.mkdir()
    (output / "existing.json").write_text("{}", encoding="utf-8")

    try:
        history.ensure_empty_output(output)
    except ValueError as exc:
        assert "must be empty" in str(exc)
    else:
        raise AssertionError("non-empty output directory was accepted")
