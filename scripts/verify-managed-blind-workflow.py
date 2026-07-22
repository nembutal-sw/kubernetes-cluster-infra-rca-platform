#!/usr/bin/env python3
"""Verify that managed blind intake remains opt-in, sanitized, and manually promoted."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    workflow = (ROOT / ".github/workflows/managed-cluster-canary.yml").read_text(encoding="utf-8")
    intake = (ROOT / "scripts/managed-blind-intake.py").read_text(encoding="utf-8")
    finalize = (ROOT / "scripts/managed-blind-finalize.py").read_text(encoding="utf-8")
    required_workflow = (
        "capture_blind_candidate:",
        "evaluation_reference:",
        "CANARY_CAPTURE_BLIND_CANDIDATE",
        "managed-blind-intake.py",
        "evaluation_reference_sha256",
        "validation-results/managed-blind-intake",
        "Upload sanitized blind evaluation candidate",
        "Remove private canary material",
    )
    failures = [f"workflow is missing {marker}" for marker in required_workflow if marker not in workflow]
    required_intake = (
        "rca-managed-blind-evidence/v1",
        "contains_raw_customer_data",
        "analyzer_output_included",
        "automatic_corpus_update",
        "source_bundle_sha256",
        "evaluation_reference_sha256",
        "requires exactly one node evidence document",
        "unsafe ZIP entry path",
    )
    failures.extend(f"intake is missing {marker}" for marker in required_intake if marker not in intake)
    required_finalize = (
        "exactly two independent reviewers",
        "rca-managed-blind-sample/v1",
        "promotion_requires_pull_request",
        "automatic_corpus_update",
    )
    failures.extend(f"finalizer is missing {marker}" for marker in required_finalize if marker not in finalize)
    forbidden_uploads = (
        "path: ${{ runner.temp }}",
        "path: ${CANARY_PRIVATE_DIR}",
        "path: $CANARY_PRIVATE_DIR",
        "path: **/evidence-bundle.zip",
    )
    failures.extend(
        f"workflow uploads private material through {marker}"
        for marker in forbidden_uploads
        if marker in workflow
    )
    result = {
        "schema_version": "managed-blind-workflow-contract/v1",
        "status": "failed" if failures else "passed",
        "failures": failures,
    }
    print(json.dumps(result, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
