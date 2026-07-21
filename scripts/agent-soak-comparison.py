#!/usr/bin/env python3
"""Compare two redacted Agent soak summaries without exposing target identifiers."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent-soak-comparison/v1"
AGENT_SCHEMA_VERSION = "agent-soak-validation/v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare baseline and candidate Agent soak summaries.")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_summary(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to load {label}: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("schema_version") != AGENT_SCHEMA_VERSION:
        raise ValueError(f"{label} must use {AGENT_SCHEMA_VERSION}")
    return payload


def mib(value: Any) -> float | None:
    return value / 1024 / 1024 if isinstance(value, (int, float)) else None


def compact(payload: dict[str, Any]) -> dict[str, Any]:
    metrics = payload.get("metrics") if isinstance(payload.get("metrics"), dict) else {}
    duration = metrics.get("collection_duration_seconds") if isinstance(metrics.get("collection_duration_seconds"), dict) else {}
    process = metrics.get("process") if isinstance(metrics.get("process"), dict) else {}
    rss = process.get("rss_bytes") if isinstance(process.get("rss_bytes"), dict) else {}
    cpu = process.get("cpu_percent") if isinstance(process.get("cpu_percent"), dict) else {}
    fleet = metrics.get("fleet") if isinstance(metrics.get("fleet"), dict) else {}
    variation = fleet.get("variation") if isinstance(fleet.get("variation"), dict) else {}
    peak_spread = variation.get("rss_peak_bytes") if isinstance(variation.get("rss_peak_bytes"), dict) else {}
    steady = fleet.get("worst_rss_steady_state") if isinstance(fleet.get("worst_rss_steady_state"), dict) else {}
    if not steady:
        steady = rss.get("steady_state") if isinstance(rss.get("steady_state"), dict) else {}
        steady = {
            "maximum_slope_bytes_per_hour": steady.get("slope_bytes_per_hour"),
            "maximum_range_bytes": steady.get("range"),
            "maximum_consecutive_increases": steady.get("maximum_consecutive_increases"),
            "minimum_sample_count": steady.get("sample_count"),
        }
    return {
        "status": payload.get("status", "unknown"),
        "profile": payload.get("profile"),
        "iterations": metrics.get("iterations_completed", 0),
        "target_count": fleet.get("target_count", 0),
        "passed_target_count": fleet.get("passed_target_count", 0),
        "collection_success_rate": metrics.get("collection_success_rate"),
        "evidence_quality_rate": metrics.get("evidence_quality_rate"),
        "p95_collection_seconds": duration.get("p95"),
        "maximum_payload_bytes": metrics.get("maximum_payload_bytes"),
        "maximum_rss_growth_mib": mib(rss.get("growth")),
        "worst_steady_rss_slope_mib_per_hour": mib(steady.get("maximum_slope_bytes_per_hour")),
        "worst_steady_rss_range_mib": mib(steady.get("maximum_range_bytes")),
        "maximum_consecutive_rss_increases": steady.get("maximum_consecutive_increases"),
        "minimum_steady_rss_samples": steady.get("minimum_sample_count"),
        "fleet_rss_peak_spread_mib": mib(peak_spread.get("spread")),
        "p95_cpu_percent": cpu.get("p95"),
        "runtime_observation_errors": metrics.get("runtime_observation_errors", 0),
        "failure_count": len(payload.get("failures") or []),
    }


def delta(baseline: Any, candidate: Any) -> float | int | None:
    if isinstance(baseline, (int, float)) and isinstance(candidate, (int, float)):
        return candidate - baseline
    return None


def compare(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    baseline_metrics = compact(baseline)
    candidate_metrics = compact(candidate)
    delta_keys = (
        "p95_collection_seconds",
        "maximum_payload_bytes",
        "maximum_rss_growth_mib",
        "worst_steady_rss_slope_mib_per_hour",
        "worst_steady_rss_range_mib",
        "fleet_rss_peak_spread_mib",
        "p95_cpu_percent",
        "runtime_observation_errors",
    )
    passed = baseline_metrics["status"] == "passed" and candidate_metrics["status"] == "passed"
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "passed" if passed else "failed",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "redacted": True,
        "baseline": baseline_metrics,
        "candidate": candidate_metrics,
        "candidate_minus_baseline": {
            key: delta(baseline_metrics.get(key), candidate_metrics.get(key)) for key in delta_keys
        },
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    try:
        result = compare(load_summary(args.baseline, "baseline"), load_summary(args.candidate, "candidate"))
        write_json(args.output, result)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
