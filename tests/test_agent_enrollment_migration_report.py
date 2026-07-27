from pathlib import Path

from scripts.verify_agent_enrollment_migration_report import EXPECTED_TESTS, evaluate_report


def write_report(path: Path, *, skipped: set[str] | None = None) -> Path:
    skipped = skipped or set()
    testcases = []
    for name in sorted(EXPECTED_TESTS):
        child = "<skipped message=\"Docker is unavailable\"/>" if name in skipped else ""
        testcases.append(
            f'<testcase name="{name}" '
            f'classname="AgentEnrollmentMigrationPackagedJarIT">{child}</testcase>'
        )
    path.write_text(
        f'<testsuite tests="2" failures="0" errors="0" skipped="{len(skipped)}">'
        f'{"".join(testcases)}</testsuite>',
        encoding="utf-8",
    )
    return path


def test_accepts_complete_packaged_migration_run(tmp_path: Path) -> None:
    result = evaluate_report(write_report(tmp_path / "migration-tests.xml"))

    assert result["status"] == "passed"
    assert result["tests_found"] == 2
    assert result["errors"] == []


def test_rejects_skipped_packaged_migration_run(tmp_path: Path) -> None:
    skipped = {"packagedCliMigratesMariadbAndPassesFinalAudit"}
    result = evaluate_report(
        write_report(tmp_path / "migration-tests.xml", skipped=skipped)
    )

    assert result["status"] == "failed"
    assert result["skipped"] == sorted(skipped)
    assert "were skipped" in result["errors"][0]


def test_rejects_missing_failsafe_report(tmp_path: Path) -> None:
    result = evaluate_report(tmp_path / "missing.xml")

    assert result["status"] == "failed"
    assert result["errors"] == ["Failsafe report was not found."]
