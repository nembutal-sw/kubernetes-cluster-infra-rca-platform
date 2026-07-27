#!/usr/bin/env python3
"""Fail CI when packaged enrollment migration tests are missing or skipped."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPORT = (
    ROOT
    / "web-console"
    / "target"
    / "failsafe-reports"
    / (
        "TEST-io.clusterinfra.rca.webconsole.maintenance."
        "AgentEnrollmentMigrationPackagedJarIT.xml"
    )
)
EXPECTED_TESTS = {
    "packagedCliMigratesPostgresqlAndPassesFinalAudit",
    "packagedCliMigratesMariadbAndPassesFinalAudit",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Verify the packaged migration CLI ran against PostgreSQL and MariaDB."
        )
    )
    parser.add_argument("report", nargs="?", type=Path, default=DEFAULT_REPORT)
    return parser.parse_args()


def evaluate_report(path: Path) -> dict[str, object]:
    result: dict[str, object] = {
        "status": "failed",
        "report": str(path),
        "expected_tests": sorted(EXPECTED_TESTS),
    }
    if not path.is_file():
        result["errors"] = ["Failsafe report was not found."]
        return result

    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        result["errors"] = [f"Failsafe report could not be parsed: {error}"]
        return result

    testcases = {case.get("name", ""): case for case in root.findall("testcase")}
    missing = sorted(EXPECTED_TESTS.difference(testcases))
    skipped = sorted(
        name
        for name, case in testcases.items()
        if name in EXPECTED_TESTS and case.find("skipped") is not None
    )
    failed = sorted(
        name
        for name, case in testcases.items()
        if name in EXPECTED_TESTS
        and (case.find("failure") is not None or case.find("error") is not None)
    )
    errors: list[str] = []
    if missing:
        errors.append(f"Expected tests are missing: {', '.join(missing)}")
    if skipped:
        errors.append(f"Packaged migration tests were skipped: {', '.join(skipped)}")
    if failed:
        errors.append(f"Packaged migration tests failed: {', '.join(failed)}")

    result.update({
        "tests_found": len(EXPECTED_TESTS.intersection(testcases)),
        "skipped": skipped,
        "failed": failed,
        "errors": errors,
        "status": "passed" if not errors else "failed",
    })
    return result


def main() -> int:
    result = evaluate_report(parse_args().report.resolve())
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
