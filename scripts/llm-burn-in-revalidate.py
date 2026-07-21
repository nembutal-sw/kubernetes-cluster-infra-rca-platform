#!/usr/bin/env python3
"""Revalidate recoverable LLM smoke artifacts without calling a provider."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_NAME = "llm-staging-smoke-result.json"
RECOVERABLE_ERRORS = {
    "llm_analysis appears to contain an unredacted secret-like value",
}


def load_smoke_module():
    path = Path(__file__).with_name("llm-staging-smoke.py")
    spec = importlib.util.spec_from_file_location("llm_staging_smoke", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load LLM staging smoke validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Revalidate recoverable LLM burn-in samples from an immutable artifact."
    )
    parser.add_argument("input", help="Downloaded LLM burn-in artifact directory.")
    parser.add_argument("--output-dir", required=True)
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return payload


def report_path(result_path: Path, result: dict[str, Any]) -> Path | None:
    report_id = str(result.get("report_id") or "").strip()
    if not report_id or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for character in report_id):
        return None
    candidate = result_path.parent / f"report-{report_id}.json"
    return candidate if candidate.is_file() else None


def discover_samples(root: Path) -> list[tuple[Path, dict[str, Any], Path | None]]:
    by_hash: dict[str, tuple[Path, dict[str, Any], Path | None]] = {}
    for result_path in sorted(root.rglob(RESULT_NAME)):
        result = load_json(result_path)
        digest = hashlib.sha256(result_path.read_bytes()).hexdigest()
        candidate = (result_path, result, report_path(result_path, result))
        existing = by_hash.get(digest)
        if existing is None or (existing[2] is None and candidate[2] is not None):
            by_hash[digest] = candidate
    return [by_hash[key] for key in sorted(by_hash)]


def positive_integer(value: Any, default: int = 0) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def non_negative_float(value: Any) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return 0.0
    return parsed if parsed >= 0 else 0.0


def revalidation_errors(
    smoke: Any,
    result: dict[str, Any],
    report: dict[str, Any],
) -> list[str]:
    errors = smoke.validate_llm_configuration({"llm": result.get("llm")}, allow_disabled=False)
    limits = result.get("limits") if isinstance(result.get("limits"), dict) else {}
    connectivity = result.get("connectivity_test")
    skip_connectivity = isinstance(connectivity, dict) and connectivity.get("outcome") == "skipped"
    errors.extend(
        smoke.validate_provider_call_budget(
            {"llm": result.get("llm")},
            skip_connectivity_test=skip_connectivity,
            provider_call_budget=positive_integer(limits.get("provider_call_budget")),
        )
    )
    task = result.get("task") if isinstance(result.get("task"), dict) else {}
    if task.get("status") != "completed":
        errors.append("analysis task was not completed")
    analysis = smoke.llm_analysis_section(report)
    if result.get("llm_analysis") != analysis:
        errors.append("stored llm_analysis does not match the sibling report")
    errors.extend(
        smoke.validate_llm_report(
            report,
            expected_statuses={"completed"},
            allow_disabled=False,
            require_usage_metadata=limits.get("require_usage_metadata") is True,
            max_latency_ms=positive_integer(limits.get("max_llm_latency_ms")),
            max_estimated_cost_usd=non_negative_float(limits.get("max_estimated_cost_usd")),
        )
    )
    return errors


def revalidate_sample(
    smoke: Any,
    result: dict[str, Any],
    report: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    status = str(result.get("status") or "")
    original_errors = result.get("errors")
    if not isinstance(original_errors, list) or not all(isinstance(item, str) for item in original_errors):
        raise ValueError("smoke result errors must be a string list")
    unknown_errors = set(original_errors) - RECOVERABLE_ERRORS
    if status == "failed" and (not original_errors or unknown_errors):
        raise ValueError("failed smoke result contains non-recoverable validation errors")
    if status not in {"passed", "failed"}:
        raise ValueError(f"unsupported smoke result status: {status or 'missing'}")
    errors = revalidation_errors(smoke, result, report)
    if errors:
        raise ValueError("current validation failed: " + "; ".join(errors))
    if status == "passed":
        if original_errors:
            raise ValueError("passed smoke result contains validation errors")
        return result, False

    corrected = dict(result)
    corrected["status"] = "passed"
    corrected["errors"] = []
    corrected["revalidation"] = {
        "schema_version": "llm-burn-in-revalidation/v1",
        "revalidated_at": datetime.now(timezone.utc).isoformat(),
        "original_status": status,
        "recovered_errors": sorted(original_errors),
        "provider_reinvoked": False,
    }
    return corrected, True


def write_sample(
    output_dir: Path,
    result: dict[str, Any],
    report_source: Path,
    report: dict[str, Any],
) -> None:
    rendered = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    sample_id = hashlib.sha256(rendered.encode("utf-8")).hexdigest()[:24]
    sample_dir = output_dir / "samples" / sample_id
    sample_dir.mkdir(parents=True, exist_ok=False)
    (sample_dir / RESULT_NAME).write_text(rendered, encoding="utf-8")
    (sample_dir / report_source.name).write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    root = Path(args.input)
    output_dir = Path(args.output_dir)
    if not root.is_dir():
        print("artifact input must be a directory", file=sys.stderr)
        return 2
    if output_dir.exists() and any(output_dir.iterdir()):
        print("output directory must be empty", file=sys.stderr)
        return 2
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        samples = discover_samples(root)
        if not samples:
            raise ValueError(f"artifact contains no {RESULT_NAME} files")
        smoke = load_smoke_module()
        recovered = 0
        for result_path, result, sibling in samples:
            if sibling is None:
                raise ValueError(f"sample {result_path.parent.name} has no sibling RCA report")
            report = load_json(sibling)
            validated, changed = revalidate_sample(smoke, result, report)
            write_sample(output_dir, validated, sibling, report)
            recovered += int(changed)
    except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        return 1

    summary = {
        "schema_version": "llm-burn-in-revalidation/v1",
        "status": "passed",
        "sample_count": len(samples),
        "recovered_sample_count": recovered,
        "provider_reinvoked": False,
    }
    (output_dir / "revalidation-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
