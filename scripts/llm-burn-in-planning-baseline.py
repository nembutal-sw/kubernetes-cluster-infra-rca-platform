#!/usr/bin/env python3
"""Create a non-sensitive planning baseline from approved LLM smoke evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_NAME = "llm-staging-smoke-result.json"
SCHEMA_VERSION = "llm-burn-in-planning-baseline/v1"
REPORT_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
SCENARIO_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a planning-only baseline without copying raw RCA evidence."
    )
    parser.add_argument("inputs", nargs="+", help=f"Existing {RESULT_NAME} file or directory.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--time-bucket-hours", type=int, default=8)
    return parser.parse_args()


def discover_results(inputs: list[str]) -> list[Path]:
    discovered: dict[str, Path] = {}
    for raw in inputs:
        path = Path(raw)
        candidates = [path] if path.is_file() else path.rglob(RESULT_NAME) if path.is_dir() else []
        for candidate in candidates:
            if candidate.name == RESULT_NAME:
                resolved = candidate.resolve()
                discovered[str(resolved)] = resolved
    return [discovered[key] for key in sorted(discovered)]


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("JSON root must be an object")
    return payload


def parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def normalized_timestamp(value: Any) -> str:
    parsed = parse_timestamp(value)
    if parsed is None:
        raise ValueError("passed smoke result has no valid started_at timestamp")
    return parsed.isoformat().replace("+00:00", "Z")


def time_bucket(timestamp: str, bucket_hours: int) -> str:
    parsed = parse_timestamp(timestamp)
    if parsed is None:
        raise ValueError("sample timestamp is invalid")
    bucket_seconds = bucket_hours * 60 * 60
    bucket_epoch = int(parsed.timestamp()) // bucket_seconds * bucket_seconds
    return datetime.fromtimestamp(bucket_epoch, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def action_safety(report: dict[str, Any]) -> tuple[int, int]:
    action_count = 0
    unsafe_count = 0
    for action in report.get("recommended_actions") or []:
        if not isinstance(action, dict) or action.get("source") != "llm":
            continue
        action_count += 1
        plan = action.get("execution_plan")
        executable = isinstance(plan, dict) and bool(plan.get("executable"))
        if bool(action.get("automation_allowed")) or executable:
            unsafe_count += 1
    return action_count, unsafe_count


def summarize_sample(result_path: Path) -> dict[str, Any]:
    result = load_json(result_path)
    if result.get("status") != "passed" or result.get("errors"):
        raise ValueError("planning baseline accepts passed smoke results only")

    scenario = str(result.get("scenario") or "").strip()
    if not SCENARIO_PATTERN.fullmatch(scenario):
        raise ValueError("smoke result has an invalid scenario")
    started_at = normalized_timestamp(result.get("started_at"))

    report_id = str(result.get("report_id") or "").strip()
    if not REPORT_ID_PATTERN.fullmatch(report_id):
        raise ValueError("smoke result has an invalid report_id")
    report_path = result_path.parent / f"report-{report_id}.json"
    if not report_path.is_file():
        raise ValueError("passed smoke result is missing its sibling RCA report")
    report = load_json(report_path)
    action_count, unsafe_count = action_safety(report)
    if unsafe_count:
        raise ValueError("smoke report contains an unsafe LLM action")

    return {
        "sample_sha256": sha256_file(result_path),
        "report_sha256": sha256_file(report_path),
        "scenario": scenario,
        "started_at": started_at,
        "llm_action_count": action_count,
        "unsafe_llm_action_count": unsafe_count,
    }


def source_bundle_digest(samples: list[dict[str, Any]]) -> str:
    digest_samples = sorted(
        (
            {
                "sample_sha256": sample["sample_sha256"],
                "report_sha256": sample["report_sha256"],
                "scenario": sample["scenario"],
                "started_at": sample["started_at"],
                "llm_action_count": sample["llm_action_count"],
                "unsafe_llm_action_count": sample["unsafe_llm_action_count"],
            }
            for sample in samples
        ),
        key=lambda sample: sample["sample_sha256"],
    )
    canonical = json.dumps(
        digest_samples,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    )
    return hashlib.sha256((canonical + "\n").encode("ascii")).hexdigest()


def build_baseline(result_paths: list[Path], bucket_hours: int) -> dict[str, Any]:
    if bucket_hours < 1:
        raise ValueError("time bucket hours must be positive")

    samples_by_hash: dict[str, dict[str, Any]] = {}
    for result_path in result_paths:
        sample = summarize_sample(result_path)
        sample_hash = sample["sample_sha256"]
        existing = samples_by_hash.get(sample_hash)
        if existing is not None and existing != sample:
            raise ValueError(f"sample content conflict: {sample_hash}")
        samples_by_hash[sample_hash] = sample

    samples = sorted(
        samples_by_hash.values(),
        key=lambda sample: (sample["started_at"], sample["sample_sha256"]),
    )
    if not samples:
        raise ValueError(f"no {RESULT_NAME} files found")
    scenarios = sorted({sample["scenario"] for sample in samples})
    buckets = sorted({time_bucket(sample["started_at"], bucket_hours) for sample in samples})
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "purpose": "provider-call-planning-only",
        "readiness_eligible": False,
        "time_bucket_hours": bucket_hours,
        "sample_count": len(samples),
        "scenario_count": len(scenarios),
        "scenarios": scenarios,
        "time_buckets": buckets,
        "source_bundle_sha256": source_bundle_digest(samples),
        "safety": {
            "llm_action_count": sum(sample["llm_action_count"] for sample in samples),
            "unsafe_llm_action_count": 0,
        },
        "samples": samples,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    missing = [raw for raw in args.inputs if not Path(raw).exists()]
    if missing:
        print("baseline input does not exist: " + ", ".join(missing), file=sys.stderr)
        return 2
    result_paths = discover_results(args.inputs)
    try:
        baseline = build_baseline(result_paths, args.time_bucket_hours)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    write_json(Path(args.output), baseline)
    print(json.dumps(baseline, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
