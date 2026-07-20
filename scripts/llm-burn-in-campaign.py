#!/usr/bin/env python3
"""Run a quota-aware sequence of live LLM staging smoke scenarios."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_NAME = "llm-staging-smoke-result.json"
MAX_PROVIDER_CALL_BUDGET = 20
DEFAULT_SCENARIOS = (
    "disk-pressure",
    "node-not-ready",
    "inode-exhaustion",
    "network-link-flap",
    "memory-pressure",
)
SCENARIO_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run bounded LLM smoke calls and refresh the aggregate burn-in report."
    )
    parser.add_argument("--base-url", default=os.getenv("RCA_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("RCA_ADMIN_USERNAME", "admin"))
    parser.add_argument(
        "--scenarios",
        default=",".join(DEFAULT_SCENARIOS),
        help="Comma-separated demo scenario keys used by the least-sampled-first planner.",
    )
    parser.add_argument(
        "--history",
        action="append",
        default=[],
        help=f"Existing {RESULT_NAME} file or directory. Repeat for multiple inputs.",
    )
    parser.add_argument("--output-dir", required=True)
    parser.add_argument(
        "--provider-call-budget",
        type=int,
        default=int(os.getenv("RCA_LLM_BURN_IN_CALL_BUDGET", "0")),
        help="Maximum provider calls for this campaign. Default 0 performs no live calls.",
    )
    parser.add_argument("--target-samples", type=int, default=20)
    parser.add_argument("--target-scenarios", type=int, default=5)
    parser.add_argument("--current-p95-ms", type=int, default=60000)
    parser.add_argument("--max-llm-latency-ms", type=int, default=60000)
    parser.add_argument("--task-timeout-seconds", type=int, default=240)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def parse_scenarios(raw: str) -> list[str]:
    scenarios: list[str] = []
    seen: set[str] = set()
    for item in raw.split(","):
        scenario = item.strip().lower()
        if not scenario:
            continue
        if not SCENARIO_PATTERN.fullmatch(scenario):
            raise ValueError(f"invalid scenario key: {scenario}")
        if scenario not in seen:
            scenarios.append(scenario)
            seen.add(scenario)
    if not scenarios:
        raise ValueError("at least one scenario is required")
    return scenarios


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


def validate_history_inputs(inputs: list[str]) -> list[str]:
    missing = [raw for raw in inputs if not Path(raw).exists()]
    if missing:
        return ["history input does not exist: " + ", ".join(missing)]
    if inputs and not discover_results(inputs):
        return [f"history inputs contain no {RESULT_NAME} files"]
    return []


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return payload


def successful_scenario_counts(result_paths: list[Path]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for path in result_paths:
        result = load_json(path)
        scenario = str(result.get("scenario") or "").strip()
        if result.get("status") == "passed" and scenario and not result.get("errors"):
            counts[scenario] += 1
    return counts


def build_plan(
    scenarios: list[str],
    existing_counts: Counter[str],
    *,
    provider_call_budget: int,
    target_samples: int,
    target_scenarios: int,
) -> list[str]:
    simulated = Counter(existing_counts)
    total_samples = sum(simulated.values())
    covered = {scenario for scenario, count in simulated.items() if count > 0}
    order = {scenario: index for index, scenario in enumerate(scenarios)}
    plan: list[str] = []

    while (
        len(plan) < provider_call_budget
        and (total_samples < target_samples or len(covered) < target_scenarios)
    ):
        scenario = min(scenarios, key=lambda item: (simulated[item], order[item]))
        plan.append(scenario)
        simulated[scenario] += 1
        total_samples += 1
        covered.add(scenario)
    return plan


def smoke_command(
    *,
    scenario: str,
    base_url: str,
    username: str,
    output_dir: Path,
    max_llm_latency_ms: int,
    task_timeout_seconds: int,
) -> list[str]:
    smoke_script = Path(__file__).resolve().with_name("llm-staging-smoke.py")
    return [
        sys.executable,
        str(smoke_script),
        "--base-url",
        base_url,
        "--username",
        username,
        "--scenario",
        scenario,
        "--node-name",
        f"burn-in-{scenario}-node",
        "--expected-llm-status",
        "completed",
        "--skip-connectivity-test",
        "--provider-call-budget",
        "1",
        "--require-usage-metadata",
        "--max-llm-latency-ms",
        str(max_llm_latency_ms),
        "--task-timeout-seconds",
        str(task_timeout_seconds),
        "--output-dir",
        str(output_dir),
    ]


def aggregate_command(
    *,
    inputs: list[str],
    output_path: Path,
    target_samples: int,
    target_scenarios: int,
    current_p95_ms: int,
) -> list[str]:
    aggregate_script = Path(__file__).resolve().with_name("llm-burn-in-report.py")
    return [
        sys.executable,
        str(aggregate_script),
        *inputs,
        "--output",
        str(output_path),
        "--minimum-samples",
        str(target_samples),
        "--minimum-scenarios",
        str(target_scenarios),
        "--current-p95-ms",
        str(current_p95_ms),
    ]


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def validate_args(args: argparse.Namespace, scenarios: list[str]) -> list[str]:
    errors: list[str] = []
    if args.provider_call_budget < 0 or args.provider_call_budget > MAX_PROVIDER_CALL_BUDGET:
        errors.append(
            f"--provider-call-budget must be between 0 and {MAX_PROVIDER_CALL_BUDGET}"
        )
    if args.target_samples < 1 or args.target_scenarios < 1:
        errors.append("target sample and scenario counts must be positive")
    if args.target_scenarios > len(scenarios):
        errors.append("--target-scenarios cannot exceed the number of configured scenarios")
    if args.current_p95_ms < 1 or args.max_llm_latency_ms < 1:
        errors.append("latency thresholds must be positive")
    return errors


def main() -> int:
    args = parse_args()
    try:
        scenarios = parse_scenarios(args.scenarios)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    errors = validate_args(args, scenarios)
    errors.extend(validate_history_inputs(args.history))
    if errors:
        print("; ".join(errors), file=sys.stderr)
        return 2

    history_results = discover_results(args.history)
    counts = successful_scenario_counts(history_results)
    plan = build_plan(
        scenarios,
        counts,
        provider_call_budget=args.provider_call_budget,
        target_samples=args.target_samples,
        target_scenarios=args.target_scenarios,
    )
    if plan and not args.dry_run and not (
        os.getenv("RCA_ADMIN_PASSWORD") or os.getenv("RCA_PASSWORD")
    ):
        print("RCA_ADMIN_PASSWORD or RCA_PASSWORD is required for live calls", file=sys.stderr)
        return 2

    output_dir = Path(args.output_dir)
    runs_dir = output_dir / "runs"
    started_at = datetime.now(timezone.utc).isoformat()
    attempted: list[str] = []
    succeeded: list[str] = []
    failed: list[str] = []

    if not args.dry_run:
        for index, scenario in enumerate(plan, start=1):
            attempted.append(scenario)
            command = smoke_command(
                scenario=scenario,
                base_url=args.base_url,
                username=args.username,
                output_dir=runs_dir / f"{index:02d}-{scenario}",
                max_llm_latency_ms=args.max_llm_latency_ms,
                task_timeout_seconds=args.task_timeout_seconds,
            )
            completed = subprocess.run(command, check=False)
            if completed.returncode != 0:
                failed.append(scenario)
                break
            succeeded.append(scenario)

    aggregate_report = output_dir / "burn-in-report.json"
    aggregate_inputs = [*args.history]
    if runs_dir.is_dir():
        aggregate_inputs.append(str(runs_dir))
    aggregate_status: int | None = None
    if aggregate_inputs and discover_results(aggregate_inputs):
        aggregate_status = subprocess.run(
            aggregate_command(
                inputs=aggregate_inputs,
                output_path=aggregate_report,
                target_samples=args.target_samples,
                target_scenarios=args.target_scenarios,
                current_p95_ms=args.current_p95_ms,
            ),
            check=False,
        ).returncode

    status = (
        "dry_run"
        if args.dry_run
        else "failed"
        if failed or aggregate_status not in {None, 0}
        else "no_calls_needed"
        if not plan
        else "passed"
    )
    summary = {
        "schema_version": "llm-burn-in-campaign/v1",
        "started_at": started_at,
        "completed_at": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "dry_run": args.dry_run,
        "provider_call_budget": args.provider_call_budget,
        "provider_call_upper_bound_used": len(attempted),
        "target_samples": args.target_samples,
        "target_scenarios": args.target_scenarios,
        "existing_successful_samples": sum(counts.values()),
        "existing_scenario_counts": dict(sorted(counts.items())),
        "planned_scenarios": plan,
        "attempted_scenarios": attempted,
        "succeeded_scenarios": succeeded,
        "failed_scenarios": failed,
        "aggregate_report": str(aggregate_report) if aggregate_status is not None else None,
        "aggregate_exit_code": aggregate_status,
    }
    write_json(output_dir / "campaign-summary.json", summary)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 1 if status == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
