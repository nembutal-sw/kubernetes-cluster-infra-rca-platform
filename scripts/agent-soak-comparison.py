#!/usr/bin/env python3
"""Apply compatibility, absolute, and regression gates to Agent soak summaries."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent-soak-comparison/v2"
AGENT_SCHEMA_VERSION = "agent-soak-validation/v1"
METADATA_SCHEMA_VERSION = "agent-soak-comparison-metadata/v1"
POLICY_SCHEMA_VERSION = "agent-soak-comparison-policy/v1"
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_POLICY = ROOT / "config" / "agent-soak-comparison-policy.json"
COMPATIBILITY_FIELDS = (
    "platform_family",
    "architecture",
    "agent_version",
    "collector_execution_source",
    "requested_collectors_sha256",
    "threshold_config_sha256",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Gate a candidate Agent soak summary against a baseline.")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to load {label}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def load_summary(path: Path, label: str) -> dict[str, Any]:
    payload = load_json_object(path, label)
    if payload.get("schema_version") != AGENT_SCHEMA_VERSION:
        raise ValueError(f"{label} must use {AGENT_SCHEMA_VERSION}")
    return payload


def load_policy(path: Path) -> dict[str, Any]:
    policy = load_json_object(path, "comparison policy")
    if policy.get("schema_version") != POLICY_SCHEMA_VERSION:
        raise ValueError(f"comparison policy must use {POLICY_SCHEMA_VERSION}")
    for key in ("compatible_profile_pairs", "absolute_limits", "regression_limits"):
        if not isinstance(policy.get(key), (list if key == "compatible_profile_pairs" else dict)):
            raise ValueError(f"comparison policy field is invalid: {key}")
    pairs = policy["compatible_profile_pairs"]
    if not pairs or not all(
        isinstance(item, dict)
        and isinstance(item.get("baseline"), str)
        and isinstance(item.get("candidate"), str)
        for item in pairs
    ):
        raise ValueError("comparison policy compatible profile pairs are invalid")
    return policy


def canonical_sha256(value: Any) -> str:
    rendered = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(rendered.encode("utf-8")).hexdigest()


def mib(value: Any) -> float | None:
    return value / 1024 / 1024 if isinstance(value, (int, float)) else None


def compact(payload: dict[str, Any]) -> dict[str, Any]:
    metrics = payload.get("metrics") if isinstance(payload.get("metrics"), dict) else {}
    collection = (
        metrics.get("collector_execution")
        if isinstance(metrics.get("collector_execution"), dict)
        else metrics
    )
    duration = (
        collection.get("collection_duration_seconds")
        if isinstance(collection.get("collection_duration_seconds"), dict)
        else {}
    )
    process = metrics.get("process") if isinstance(metrics.get("process"), dict) else {}
    rss = process.get("rss_bytes") if isinstance(process.get("rss_bytes"), dict) else {}
    cpu = process.get("cpu_percent") if isinstance(process.get("cpu_percent"), dict) else {}
    fleet = metrics.get("fleet") if isinstance(metrics.get("fleet"), dict) else {}
    variation = fleet.get("variation") if isinstance(fleet.get("variation"), dict) else {}
    peak_spread = variation.get("rss_peak_bytes") if isinstance(variation.get("rss_peak_bytes"), dict) else {}
    steady = fleet.get("worst_rss_steady_state") if isinstance(fleet.get("worst_rss_steady_state"), dict) else {}
    if not steady:
        rss_steady = rss.get("steady_state") if isinstance(rss.get("steady_state"), dict) else {}
        steady = {
            "maximum_slope_bytes_per_hour": rss_steady.get("slope_bytes_per_hour"),
            "maximum_range_bytes": rss_steady.get("range"),
            "maximum_consecutive_increases": rss_steady.get("maximum_consecutive_increases"),
            "minimum_sample_count": rss_steady.get("sample_count"),
        }
    metadata = payload.get("comparison_metadata") if isinstance(payload.get("comparison_metadata"), dict) else {}
    return {
        "status": payload.get("status", "unknown"),
        "profile": payload.get("profile"),
        "iterations": metrics.get("iterations_completed", 0),
        "target_count": fleet.get("target_count", 0),
        "passed_target_count": fleet.get("passed_target_count", 0),
        "collector_execution_source": metadata.get("collector_execution_source"),
        "collection_success_rate": collection.get("collection_success_rate"),
        "evidence_quality_rate": collection.get("evidence_quality_rate"),
        "degraded_collector_rate": collection.get("degraded_collector_rate"),
        "p95_collection_seconds": duration.get("p95"),
        "maximum_payload_bytes": collection.get("maximum_payload_bytes"),
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


def compact_metadata(payload: dict[str, Any]) -> dict[str, Any]:
    metadata = payload.get("comparison_metadata") if isinstance(payload.get("comparison_metadata"), dict) else {}
    return {key: metadata.get(key) for key in ("schema_version", *COMPATIBILITY_FIELDS)}


def delta(baseline: Any, candidate: Any) -> float | int | None:
    if isinstance(baseline, (int, float)) and isinstance(candidate, (int, float)):
        return candidate - baseline
    return None


def compatibility_gate(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    baseline_metrics: dict[str, Any],
    candidate_metrics: dict[str, Any],
    policy: dict[str, Any],
) -> dict[str, Any]:
    baseline_metadata = compact_metadata(baseline)
    candidate_metadata = compact_metadata(candidate)
    reasons: list[str] = []
    for label, metadata in (("baseline", baseline_metadata), ("candidate", candidate_metadata)):
        if metadata.get("schema_version") != METADATA_SCHEMA_VERSION:
            reasons.append(f"{label} comparison metadata is missing or unsupported")
        for field in COMPATIBILITY_FIELDS:
            if metadata.get(field) in (None, "", "unknown"):
                reasons.append(f"{label} comparison metadata is missing {field}")
    for field in COMPATIBILITY_FIELDS:
        if baseline_metadata.get(field) != candidate_metadata.get(field):
            reasons.append(f"comparison metadata differs: {field}")
    allowed_pairs = {
        (item["baseline"], item["candidate"])
        for item in policy["compatible_profile_pairs"]
    }
    profile_pair = (baseline_metrics.get("profile"), candidate_metrics.get("profile"))
    if profile_pair not in allowed_pairs:
        reasons.append("profile transition is not allowed by the comparison policy")
    if policy.get("require_equal_target_count", True) and (
        baseline_metrics.get("target_count") != candidate_metrics.get("target_count")
    ):
        reasons.append("fleet target count differs")
    reasons = list(dict.fromkeys(reasons))
    return {
        "status": "passed" if not reasons else "failed",
        "comparable": not reasons,
        "profile_pair": {"baseline": profile_pair[0], "candidate": profile_pair[1]},
        "baseline_metadata": baseline_metadata,
        "candidate_metadata": candidate_metadata,
        "reasons": reasons,
    }


def absolute_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], policy: dict[str, Any]
) -> dict[str, Any]:
    limits = policy["absolute_limits"]
    checks: list[dict[str, Any]] = []

    def add(name: str, passed: bool, actual: Any, expected: str) -> None:
        checks.append({"name": name, "status": "passed" if passed else "failed", "actual": actual, "expected": expected})

    add("baseline_artifact_status", baseline.get("status") == "passed", baseline.get("status"), "passed")
    add("candidate_artifact_status", candidate.get("status") == "passed", candidate.get("status"), "passed")
    add("candidate_failure_count", candidate.get("failure_count") == 0, candidate.get("failure_count"), "0")
    minimum_targets = int(limits.get("minimum_candidate_target_count", 0))
    add("candidate_target_count", int(candidate.get("target_count") or 0) >= minimum_targets, candidate.get("target_count"), f">= {minimum_targets}")
    add(
        "candidate_passed_target_count",
        candidate.get("passed_target_count") == candidate.get("target_count"),
        candidate.get("passed_target_count"),
        f"= {candidate.get('target_count')}",
    )
    required_source = limits.get("required_collector_execution_source")
    if required_source:
        add("collector_execution_source", candidate.get("collector_execution_source") == required_source, candidate.get("collector_execution_source"), str(required_source))
    for metric, minimum in (
        ("collection_success_rate", limits.get("minimum_collection_success_rate")),
        ("evidence_quality_rate", limits.get("minimum_evidence_quality_rate")),
    ):
        actual = candidate.get(metric)
        if isinstance(minimum, (int, float)):
            add(metric, isinstance(actual, (int, float)) and actual >= minimum, actual, f">= {minimum}")
    maximum_degraded = limits.get("maximum_degraded_collector_rate")
    if isinstance(maximum_degraded, (int, float)):
        actual = candidate.get("degraded_collector_rate")
        add("degraded_collector_rate", isinstance(actual, (int, float)) and actual <= maximum_degraded, actual, f"<= {maximum_degraded}")
    maximum_runtime_errors = int(limits.get("maximum_runtime_observation_errors", 0))
    add("runtime_observation_errors", int(candidate.get("runtime_observation_errors") or 0) <= maximum_runtime_errors, candidate.get("runtime_observation_errors"), f"<= {maximum_runtime_errors}")
    violations = [item["name"] for item in checks if item["status"] == "failed"]
    return {"status": "passed" if not violations else "failed", "checks": checks, "violations": violations}


def regression_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], policy: dict[str, Any], comparable: bool
) -> dict[str, Any]:
    if not comparable:
        return {"status": "not_evaluated", "checks": [], "violations": ["artifacts are not comparable"]}
    checks: list[dict[str, Any]] = []
    for metric, limit in policy["regression_limits"].get("increase", {}).items():
        before = baseline.get(metric)
        after = candidate.get(metric)
        if not isinstance(before, (int, float)) or not isinstance(after, (int, float)):
            checks.append({"metric": metric, "status": "failed", "reason": "numeric metric is unavailable"})
            continue
        allowed = float(limit.get("maximum_absolute_increase", 0))
        maximum_ratio = limit.get("maximum_ratio")
        if isinstance(maximum_ratio, (int, float)) and before > 0:
            allowed = max(allowed, before * (float(maximum_ratio) - 1.0))
        increase = after - before
        checks.append(
            {
                "metric": metric,
                "status": "passed" if increase <= allowed else "failed",
                "baseline": before,
                "candidate": after,
                "delta": increase,
                "allowed_increase": allowed,
                "ratio": after / before if before > 0 else None,
            }
        )
    for metric, limit in policy["regression_limits"].get("rate_drop", {}).items():
        before = baseline.get(metric)
        after = candidate.get(metric)
        maximum_drop = float(limit.get("maximum_absolute_drop", 0))
        if not isinstance(before, (int, float)) or not isinstance(after, (int, float)):
            checks.append({"metric": metric, "status": "failed", "reason": "numeric metric is unavailable"})
            continue
        drop = before - after
        checks.append(
            {
                "metric": metric,
                "status": "passed" if drop <= maximum_drop else "failed",
                "baseline": before,
                "candidate": after,
                "delta": after - before,
                "allowed_drop": maximum_drop,
            }
        )
    violations = [item["metric"] for item in checks if item["status"] == "failed"]
    return {"status": "passed" if not violations else "failed", "checks": checks, "violations": violations}


def compare(
    baseline: dict[str, Any], candidate: dict[str, Any], policy: dict[str, Any] | None = None
) -> dict[str, Any]:
    policy = policy or load_policy(DEFAULT_POLICY)
    baseline_metrics = compact(baseline)
    candidate_metrics = compact(candidate)
    compatibility = compatibility_gate(baseline, candidate, baseline_metrics, candidate_metrics, policy)
    absolute = absolute_gate(baseline_metrics, candidate_metrics, policy)
    regression = regression_gate(baseline_metrics, candidate_metrics, policy, compatibility["comparable"])
    passed = all(gate["status"] == "passed" for gate in (compatibility, absolute, regression))
    delta_keys = tuple(policy["regression_limits"].get("increase", {})) + tuple(
        policy["regression_limits"].get("rate_drop", {})
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "passed" if passed else "failed",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "redacted": True,
        "policy": {"schema_version": policy["schema_version"], "sha256": canonical_sha256(policy)},
        "compatibility": compatibility,
        "absolute_gate": absolute,
        "regression_gate": regression,
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
        result = compare(
            load_summary(args.baseline, "baseline"),
            load_summary(args.candidate, "candidate"),
            load_policy(args.policy),
        )
        write_json(args.output, result)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
