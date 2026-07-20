#!/usr/bin/env python3
"""Aggregate quota-limited LLM smoke results into an SLO burn-in report."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_NAME = "llm-staging-smoke-result.json"
SCHEMA_VERSION = "llm-burn-in/v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Aggregate LLM staging smoke results without making provider calls."
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        help=f"Result files or directories recursively containing {RESULT_NAME}.",
    )
    parser.add_argument("--output", required=True, help="Burn-in JSON report path.")
    parser.add_argument("--minimum-samples", type=int, default=20)
    parser.add_argument("--minimum-scenarios", type=int, default=5)
    parser.add_argument("--current-p95-ms", type=int, default=60000)
    return parser.parse_args()


def discover_results(inputs: list[str]) -> list[Path]:
    discovered: dict[str, Path] = {}
    for raw in inputs:
        path = Path(raw)
        candidates = [path] if path.is_file() else path.rglob(RESULT_NAME) if path.is_dir() else []
        for candidate in candidates:
            if candidate.name != RESULT_NAME:
                continue
            resolved = candidate.resolve()
            discovered[str(resolved)] = resolved
    return [discovered[key] for key in sorted(discovered)]


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return payload


def non_negative_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if number >= 0 else None


def non_negative_number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number >= 0 else None


def nearest_rank(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def sibling_report(result_path: Path, result: dict[str, Any]) -> Path | None:
    report_id = str(result.get("report_id") or "").strip()
    if not report_id:
        return None
    candidate = result_path.parent / f"report-{report_id}.json"
    return candidate if candidate.is_file() else None


def action_safety(report: dict[str, Any]) -> tuple[int, int]:
    action_count = 0
    violation_count = 0
    for action in report.get("recommended_actions") or []:
        if not isinstance(action, dict) or action.get("source") != "llm":
            continue
        action_count += 1
        plan = action.get("execution_plan")
        executable = isinstance(plan, dict) and bool(plan.get("executable"))
        if bool(action.get("automation_allowed")) or executable:
            violation_count += 1
    return action_count, violation_count


def summarize_sample(result_path: Path) -> dict[str, Any]:
    result = load_json(result_path)
    analysis = result.get("llm_analysis") if isinstance(result.get("llm_analysis"), dict) else {}
    usage = analysis.get("usage") if isinstance(analysis.get("usage"), dict) else {}
    llm = result.get("llm") if isinstance(result.get("llm"), dict) else {}
    errors = [str(error)[:300] for error in result.get("errors") or []]
    report_path = sibling_report(result_path, result)
    action_count = 0
    safety_violations = 0
    if report_path:
        action_count, safety_violations = action_safety(load_json(report_path))
    elif result.get("status") == "passed":
        errors.append("passed smoke result is missing its sibling RCA report")

    return {
        "source": str(result_path),
        "status": str(result.get("status") or "unknown"),
        "started_at": result.get("started_at"),
        "scenario": str(result.get("scenario") or "unknown"),
        "provider": str(llm.get("provider") or "unknown"),
        "model": str(llm.get("model") or "unknown"),
        "analysis_status": str(analysis.get("status") or "unknown"),
        "latency_ms": non_negative_number(analysis.get("latency_ms")),
        "usage_available": usage.get("usage_available") is True,
        "input_tokens": non_negative_int(usage.get("input_tokens")),
        "output_tokens": non_negative_int(usage.get("output_tokens")),
        "total_tokens": non_negative_int(usage.get("total_tokens")),
        "estimated_cost_usd": non_negative_number(usage.get("estimated_cost_usd")),
        "llm_action_count": action_count,
        "unsafe_llm_action_count": safety_violations,
        "errors": errors,
    }


def aggregate(
    result_paths: list[Path],
    *,
    minimum_samples: int,
    minimum_scenarios: int,
    current_p95_ms: int,
) -> dict[str, Any]:
    samples = [summarize_sample(path) for path in result_paths]
    latencies = [sample["latency_ms"] for sample in samples if sample["latency_ms"] is not None]
    passed = [sample for sample in samples if sample["status"] == "passed" and not sample["errors"]]
    scenarios = sorted({sample["scenario"] for sample in samples})
    providers = sorted({sample["provider"] for sample in samples})
    models = sorted({sample["model"] for sample in samples})
    safety_violations = sum(sample["unsafe_llm_action_count"] for sample in samples)
    usage_available = sum(1 for sample in samples if sample["usage_available"])
    token_keys = ("input_tokens", "output_tokens", "total_tokens")
    token_totals = {
        key: sum(sample[key] or 0 for sample in samples)
        for key in token_keys
    }
    estimated_costs = [
        sample["estimated_cost_usd"]
        for sample in samples
        if sample["estimated_cost_usd"] is not None
    ]

    has_failures = len(passed) != len(samples) or safety_violations > 0
    enough_samples = len(samples) >= minimum_samples and len(scenarios) >= minimum_scenarios
    readiness = "failed" if has_failures else "ready" if enough_samples else "insufficient_samples"
    observed_p95 = nearest_rank(latencies, 0.95)
    if has_failures:
        decision = "investigate_failures"
    elif not enough_samples:
        decision = "retain_current_threshold"
    elif observed_p95 is not None and observed_p95 > current_p95_ms:
        decision = "review_provider_or_threshold"
    else:
        decision = "current_threshold_supported"

    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": "failed" if has_failures else "passed",
        "readiness": readiness,
        "sample_count": len(samples),
        "minimum_samples": minimum_samples,
        "scenario_count": len(scenarios),
        "minimum_scenarios": minimum_scenarios,
        "passed_sample_count": len(passed),
        "scenarios": scenarios,
        "providers": providers,
        "models": models,
        "latency_ms": {
            "count": len(latencies),
            "minimum": min(latencies) if latencies else None,
            "mean": statistics.fmean(latencies) if latencies else None,
            "p50": nearest_rank(latencies, 0.50),
            "p95": observed_p95,
            "maximum": max(latencies) if latencies else None,
            "current_slo_p95": current_p95_ms,
            "within_current_slo": observed_p95 is not None and observed_p95 <= current_p95_ms,
        },
        "usage": {
            "metadata_available_count": usage_available,
            "metadata_available_ratio": usage_available / len(samples) if samples else 0,
            **token_totals,
            "estimated_cost_usd": sum(estimated_costs),
        },
        "safety": {
            "llm_action_count": sum(sample["llm_action_count"] for sample in samples),
            "unsafe_llm_action_count": safety_violations,
        },
        "recommendation": {
            "decision": decision,
            "configured_p95_ms": current_p95_ms,
            "reason": (
                "Do not lower the production threshold until both minimum sample and scenario counts are met."
                if readiness == "insufficient_samples"
                else "Investigate failed samples or unsafe LLM actions before changing the SLO."
                if readiness == "failed"
                else "The observed p95 is within the configured threshold."
                if decision == "current_threshold_supported"
                else "Observed p95 exceeds the configured threshold; review provider health and workload mix."
            ),
        },
        "samples": samples,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if args.minimum_samples < 1 or args.minimum_scenarios < 1 or args.current_p95_ms < 1:
        print("minimum counts and --current-p95-ms must be positive", file=sys.stderr)
        return 2
    result_paths = discover_results(args.inputs)
    if not result_paths:
        print(f"no {RESULT_NAME} files found", file=sys.stderr)
        return 2
    report = aggregate(
        result_paths,
        minimum_samples=args.minimum_samples,
        minimum_scenarios=args.minimum_scenarios,
        current_p95_ms=args.current_p95_ms,
    )
    write_json(Path(args.output), report)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
