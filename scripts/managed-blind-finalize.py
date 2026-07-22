#!/usr/bin/env python3
"""Seal independently adjudicated managed evidence into an immutable sample."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

from managed_blind_common import (
    CLASSIFICATIONS,
    REVIEWER_ID_PATTERN,
    atomic_write_json,
    canonical_json_bytes,
    load_json,
    require_empty_output_dir,
    sha256_bytes,
    sha256_file,
    sanitize_string,
    utc_now,
    validate_evidence_candidate,
    validate_rfc3339,
)


SIGNAL_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{2,100}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--adjudication", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def string_array(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ValueError(f"{label} must be an array of non-empty strings")
    if len(value) != len(set(value)):
        raise ValueError(f"{label} must not contain duplicates")
    if any(not SIGNAL_PATTERN.fullmatch(item) for item in value):
        raise ValueError(f"{label} values must use uppercase signal identifiers")
    return value


def validate_adjudication(value: dict[str, Any], case_id: str) -> dict[str, Any]:
    if value.get("schema_version") != "rca-managed-blind-adjudication/v1":
        raise ValueError("unexpected managed blind adjudication schema")
    if value.get("case_id") != case_id:
        raise ValueError("evidence and adjudication case_id values do not match")
    if value.get("review_status") != "approved" or value.get("consensus") is not True:
        raise ValueError("adjudication requires approved status and reviewer consensus")
    classification = str(value.get("classification", ""))
    if classification not in CLASSIFICATIONS:
        raise ValueError("adjudication classification is invalid")
    expected = string_array(value.get("expected_signals"), "expected_signals")
    allowed = string_array(value.get("allowed_signals"), "allowed_signals")
    forbidden = string_array(value.get("forbidden_signals"), "forbidden_signals")
    if classification not in {"negative", "degraded_evidence"} and not expected:
        raise ValueError("positive adjudication classes require at least one expected signal")
    if set(expected) & set(forbidden):
        raise ValueError("expected_signals and forbidden_signals must not overlap")
    root_cause = value.get("root_cause_summary")
    if not isinstance(root_cause, str) or not root_cause.strip() or len(root_cause) > 1000:
        raise ValueError("root_cause_summary must contain 1 to 1000 characters")
    if sanitize_string(root_cause, set()) != root_cause:
        raise ValueError("root_cause_summary contains a sensitive identifier pattern")
    reviewers = value.get("reviewers")
    if not isinstance(reviewers, list) or len(reviewers) != 2:
        raise ValueError("adjudication requires exactly two independent reviewers")
    reviewer_ids: set[str] = set()
    roles: set[str] = set()
    normalized_reviewers: list[dict[str, str]] = []
    for index, reviewer in enumerate(reviewers):
        if not isinstance(reviewer, dict):
            raise ValueError(f"reviewers[{index}] must be an object")
        reviewer_id = str(reviewer.get("reviewer_id", ""))
        role = str(reviewer.get("role", ""))
        decision = str(reviewer.get("decision", ""))
        reviewed_at = str(reviewer.get("reviewed_at", ""))
        if not REVIEWER_ID_PATTERN.fullmatch(reviewer_id):
            raise ValueError(
                f"reviewers[{index}].reviewer_id must match reviewer_<8-32 lowercase hex>"
            )
        if role not in {"primary", "secondary"}:
            raise ValueError(f"reviewers[{index}].role must be primary or secondary")
        if decision != "approve":
            raise ValueError(f"reviewers[{index}].decision must be approve")
        validate_rfc3339(reviewed_at, f"reviewers[{index}].reviewed_at")
        reviewer_ids.add(reviewer_id)
        roles.add(role)
        normalized_reviewers.append(
            {
                "reviewer_id": reviewer_id,
                "role": role,
                "decision": decision,
                "reviewed_at": reviewed_at,
            }
        )
    if len(reviewer_ids) != 2 or roles != {"primary", "secondary"}:
        raise ValueError("reviewers must have distinct IDs and distinct primary/secondary roles")
    return {
        "schema_version": "rca-managed-blind-label/v1",
        "case_id": case_id,
        "classification": classification,
        "expected_signals": expected,
        "allowed_signals": allowed,
        "forbidden_signals": forbidden,
        "root_cause_summary": root_cause.strip(),
        "adjudication": {
            "review_status": "approved",
            "consensus": True,
            "reviewers": normalized_reviewers,
        },
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    evidence = load_json(args.evidence, "managed blind evidence")
    validate_evidence_candidate(evidence)
    adjudication = load_json(args.adjudication, "managed blind adjudication")
    label = validate_adjudication(adjudication, str(evidence["case_id"]))
    require_empty_output_dir(args.output_dir)
    atomic_write_json(args.output_dir / "evidence.json", evidence)
    atomic_write_json(args.output_dir / "labels.json", label)
    manifest = {
        "schema_version": "rca-managed-blind-sample/v1",
        "generated_at": utc_now(),
        "case_id": evidence["case_id"],
        "evidence_sha256": sha256_bytes(canonical_json_bytes(evidence)),
        "labels_sha256": sha256_bytes(canonical_json_bytes(label)),
        "source_evidence_file_sha256": sha256_file(args.evidence),
        "source_adjudication_file_sha256": sha256_file(args.adjudication),
        "reviewer_count": 2,
        "consensus": True,
        "automatic_corpus_update": False,
        "promotion_requires_pull_request": True,
    }
    atomic_write_json(args.output_dir / "manifest.json", manifest)
    return manifest


def main() -> int:
    args = parse_args()
    try:
        result = run(args)
    except (OSError, ValueError) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps({"status": "passed", **result}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
