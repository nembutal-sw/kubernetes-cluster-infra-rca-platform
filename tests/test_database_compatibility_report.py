from pathlib import Path

from scripts.verify_database_compatibility_report import EXPECTED_TESTS, evaluate_report


def write_report(path: Path, *, skipped: set[str] | None = None) -> Path:
    skipped = skipped or set()
    testcases = []
    for name in sorted(EXPECTED_TESTS):
        child = "<skipped message=\"Docker is unavailable\"/>" if name in skipped else ""
        testcases.append(f'<testcase name="{name}" classname="DatabaseCompatibilityTests">{child}</testcase>')
    path.write_text(
        f'<testsuite tests="4" failures="0" errors="0" skipped="{len(skipped)}">'
        f'{"".join(testcases)}</testsuite>',
        encoding="utf-8",
    )
    return path


def test_accepts_complete_database_compatibility_run(tmp_path: Path) -> None:
    result = evaluate_report(write_report(tmp_path / "database-tests.xml"))

    assert result["status"] == "passed"
    assert result["tests_found"] == 4
    assert result["errors"] == []


def test_rejects_silently_skipped_database_compatibility_run(tmp_path: Path) -> None:
    skipped = {"mariadbSupportsFreshSchemaAndRepositoryWorkflow"}
    result = evaluate_report(write_report(tmp_path / "database-tests.xml", skipped=skipped))

    assert result["status"] == "failed"
    assert result["skipped"] == sorted(skipped)
    assert "were skipped" in result["errors"][0]


def test_rejects_missing_surefire_report(tmp_path: Path) -> None:
    result = evaluate_report(tmp_path / "missing.xml")

    assert result["status"] == "failed"
    assert result["errors"] == ["Surefire report was not found."]
