#!/usr/bin/env python3
"""Merge Agent soak, cluster readiness, platform coverage, and LLM readiness."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "operational-burn-in/v1"
AGENT_SCHEMA_VERSION = "agent-soak-validation/v1"
PLATFORM_SCHEMA_VERSION = "cluster-platform-compatibility/v1"


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Build a redacted operational burn-in summary.")
    parser.add_argument("--agent-soak", type=Path, required=True, help="Agent soak summary JSON.")
    parser.add_argument("--real-cluster", type=Path, help="Optional real-cluster readiness JSON.")
    parser.add_argument("--llm-campaign", type=Path, help="Optional LLM campaign summary JSON.")
    parser.add_argument(
        "--platform-matrix",
        type=Path,
        default=root / "config" / "platform-compatibility-matrix.json",
        help="Platform compatibility matrix JSON.",
    )
    parser.add_argument("--require-real-cluster", action="store_true", help="Fail when a cluster report is absent.")
    parser.add_argument("--output", type=Path, required=True, help="Combined summary JSON path.")
    parser.add_argument("--markdown-output", type=Path, help="Optional concise Markdown summary path.")
    return parser.parse_args()


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to load {label}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def load_optional(path: Path | None, label: str) -> tuple[dict[str, Any] | None, str | None]:
    if path is None:
        return None, None
    if not path.is_file():
        return None, f"{label} was requested but no result file was produced"
    try:
        return load_json(path, label), None
    except ValueError as exc:
        return None, str(exc)


def agent_component(payload: dict[str, Any]) -> dict[str, Any]:
    metrics = payload.get("metrics") if isinstance(payload.get("metrics"), dict) else {}
    duration = metrics.get("collection_duration_seconds") if isinstance(metrics.get("collection_duration_seconds"), dict) else {}
    process = metrics.get("process") if isinstance(metrics.get("process"), dict) else {}
    rss = process.get("rss_bytes") if isinstance(process.get("rss_bytes"), dict) else {}
    fds = process.get("fd_count") if isinstance(process.get("fd_count"), dict) else {}
    threads = process.get("thread_count") if isinstance(process.get("thread_count"), dict) else {}
    cpu = process.get("cpu_percent") if isinstance(process.get("cpu_percent"), dict) else {}
    fleet = metrics.get("fleet") if isinstance(metrics.get("fleet"), dict) else {}
    variation = fleet.get("variation") if isinstance(fleet.get("variation"), dict) else {}
    rss_peak_variation = (
        variation.get("rss_peak_bytes") if isinstance(variation.get("rss_peak_bytes"), dict) else {}
    )
    cpu_p95_variation = (
        variation.get("p95_cpu_percent") if isinstance(variation.get("p95_cpu_percent"), dict) else {}
    )
    return {
        "status": payload.get("status", "unknown"),
        "profile": payload.get("profile"),
        "iterations_completed": metrics.get("iterations_completed", 0),
        "iterations_target": metrics.get("iterations_target", 0),
        "collection_success_rate": metrics.get("collection_success_rate"),
        "evidence_quality_rate": metrics.get("evidence_quality_rate"),
        "degraded_collector_rate": metrics.get("degraded_collector_rate"),
        "p95_collection_seconds": duration.get("p95"),
        "maximum_payload_bytes": metrics.get("maximum_payload_bytes"),
        "rss_growth_mb": (rss.get("growth") / 1024 / 1024) if isinstance(rss.get("growth"), (int, float)) else None,
        "p95_cpu_percent": cpu.get("p95"),
        "fd_growth": fds.get("growth"),
        "thread_growth": threads.get("growth"),
        "process_observed": bool((payload.get("observability") or {}).get("agent_process_configured")),
        "spool_observed": bool((payload.get("observability") or {}).get("state_dir_configured")),
        "fleet_target_count": fleet.get("target_count", 0),
        "fleet_minimum_target_count": fleet.get("minimum_target_count", 0),
        "fleet_passed_target_count": fleet.get("passed_target_count", 0),
        "fleet_rss_peak_spread_mb": (
            (rss_peak_variation.get("spread") / 1024 / 1024)
            if isinstance(rss_peak_variation.get("spread"), (int, float))
            else None
        ),
        "fleet_p95_cpu_spread_percent": cpu_p95_variation.get("spread"),
        "failure_count": len(payload.get("failures") or []),
        "warning_count": len(payload.get("warnings") or []),
    }


def cluster_component(payload: dict[str, Any] | None) -> dict[str, Any]:
    if payload is None:
        return {"status": "skipped"}
    failures = payload.get("failures") if isinstance(payload.get("failures"), list) else []
    warnings = payload.get("warnings") if isinstance(payload.get("warnings"), list) else []
    signals = payload.get("signals") if isinstance(payload.get("signals"), dict) else {}
    compatibility = signals.get("cluster_compatibility") if isinstance(signals.get("cluster_compatibility"), dict) else {}
    fingerprint = compatibility.get("fingerprint") if isinstance(compatibility.get("fingerprint"), dict) else {}
    assessment = compatibility.get("assessment") if isinstance(compatibility.get("assessment"), dict) else {}
    platform = fingerprint.get("platform") if isinstance(fingerprint.get("platform"), dict) else {}
    pods = signals.get("pods") if isinstance(signals.get("pods"), dict) else {}
    nodes = signals.get("nodes") if isinstance(signals.get("nodes"), list) else []
    return {
        "status": "failed" if failures else "warning" if warnings else payload.get("status", "unknown"),
        "platform": platform.get("family"),
        "compatibility_status": assessment.get("status"),
        "unverified_dimensions": assessment.get("unverified_dimensions") or [],
        "architectures": fingerprint.get("architectures") or [],
        "runtime_families": fingerprint.get("runtime_families") or [],
        "cni_families": ((fingerprint.get("cni") or {}).get("families") or []),
        "node_count": len(nodes),
        "pod_count": pods.get("total"),
        "unhealthy_pod_count": len(pods.get("unhealthy") or []),
        "failure_count": len(failures),
        "warning_count": len(warnings),
    }


def llm_component(payload: dict[str, Any] | None) -> dict[str, Any]:
    if payload is None:
        return {"status": "skipped", "readiness": "unknown", "provider_calls_used": 0}
    readiness = payload.get("readiness") if isinstance(payload.get("readiness"), dict) else {}
    return {
        "status": payload.get("status", "unknown"),
        "readiness": "ready" if readiness.get("ready") else "pending",
        "samples": {"current": readiness.get("sample_count", 0), "target": readiness.get("sample_target", 0)},
        "scenarios": {"current": readiness.get("scenario_count", 0), "target": readiness.get("scenario_target", 0)},
        "time_buckets": {
            "current": readiness.get("time_bucket_count", 0),
            "target": readiness.get("time_bucket_target", 0),
        },
        "current_time_bucket": payload.get("current_time_bucket"),
        "next_provider_call_at": payload.get("next_provider_call_at"),
        "provider_calls_allowed": bool(payload.get("provider_calls_allowed")),
        "provider_calls_used": payload.get("provider_call_upper_bound_used", 0),
        "dry_run": bool(payload.get("dry_run", True)),
    }


def platform_component(payload: dict[str, Any]) -> dict[str, Any]:
    platforms = payload.get("platforms") if isinstance(payload.get("platforms"), dict) else {}
    real = []
    fixture = []
    planned = []
    for name, value in sorted(platforms.items()):
        level = value.get("validation_level") if isinstance(value, dict) else None
        if level == "real_e2e":
            real.append(name)
        elif level == "contract_fixture":
            fixture.append(name)
        else:
            planned.append(name)
    return {
        "status": "passed" if real else "warning",
        "real_e2e": real,
        "contract_fixture_only": fixture,
        "planned": planned,
        "managed_canary_pending": [name for name in ("eks", "aks", "gke", "openshift") if name not in real],
    }


def build_summary(
    agent: dict[str, Any],
    cluster: dict[str, Any] | None,
    llm: dict[str, Any] | None,
    platform: dict[str, Any],
    *,
    require_real_cluster: bool,
    input_errors: list[str],
) -> dict[str, Any]:
    failures = list(input_errors)
    warnings = []
    agent_result = agent_component(agent)
    cluster_result = cluster_component(cluster)
    llm_result = llm_component(llm)
    platform_result = platform_component(platform)

    if agent.get("schema_version") != AGENT_SCHEMA_VERSION:
        failures.append(f"Agent soak summary must use {AGENT_SCHEMA_VERSION}")
    if agent_result["status"] != "passed":
        failures.append("Agent soak validation did not pass")
    if require_real_cluster and cluster is None:
        failures.append("real-cluster readiness is required but unavailable")
    if cluster_result["status"] == "failed":
        failures.append("real-cluster readiness failed")
    elif cluster_result["status"] == "warning":
        warnings.append("real-cluster readiness completed with warnings")
    elif cluster_result["status"] == "skipped":
        warnings.append("real-cluster readiness was not included")
    if llm_result["status"] == "failed":
        failures.append("LLM readiness status generation failed")
    elif llm_result["status"] == "skipped":
        warnings.append("LLM readiness status was not included")
    elif llm_result["readiness"] != "ready":
        warnings.append("LLM SLO readiness still needs more eligible samples")
    if platform.get("schema_version") != PLATFORM_SCHEMA_VERSION:
        failures.append(f"platform matrix must use {PLATFORM_SCHEMA_VERSION}")
    pending = platform_result["managed_canary_pending"]
    if pending:
        warnings.append("managed-platform real canaries remain pending: " + ", ".join(pending))

    next_actions = []
    if not agent_result["process_observed"]:
        next_actions.append("Repeat the standard profile with required Agent Pod runtime observation.")
    if not agent_result["spool_observed"]:
        next_actions.append("Repeat the standard profile with required Agent Pod spool observation.")
    if llm_result["readiness"] == "pending":
        next_actions.append("Continue the approval-gated LLM burn-in only in a new eligible time bucket.")
    if pending:
        next_actions.append("Run one read-only Agent canary on each pending managed platform before claiming support.")
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "failed" if failures else "warning" if warnings else "passed",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "read_only": True,
        "components": {
            "agent_soak": agent_result,
            "real_cluster": cluster_result,
            "llm_burn_in": llm_result,
            "platform_coverage": platform_result,
        },
        "failures": failures,
        "warnings": warnings,
        "next_actions": next_actions,
    }


def markdown(summary: dict[str, Any]) -> str:
    components = summary["components"]
    agent = components["agent_soak"]
    cluster = components["real_cluster"]
    llm = components["llm_burn_in"]
    coverage = components["platform_coverage"]
    lines = [
        "## Operational Burn-in",
        "",
        f"- Overall: `{summary['status']}`",
        f"- Agent soak: `{agent['status']}` ({agent['iterations_completed']}/{agent['iterations_target']} iterations)",
        f"- Evidence quality: `{agent.get('evidence_quality_rate')}`",
        f"- Agent p95 CPU: `{agent.get('p95_cpu_percent') if agent.get('p95_cpu_percent') is not None else 'not measured'}`",
        f"- Real cluster: `{cluster['status']}` ({cluster.get('platform') or 'not included'})",
        f"- LLM readiness: `{llm['readiness']}`; provider calls used: `{llm['provider_calls_used']}`",
        f"- Real E2E platforms: `{', '.join(coverage['real_e2e']) or 'none'}`",
        f"- Managed canaries pending: `{', '.join(coverage['managed_canary_pending']) or 'none'}`",
    ]
    if agent.get("fleet_target_count"):
        lines.insert(
            5,
            f"- Agent fleet: `{agent['fleet_passed_target_count']}/{agent['fleet_target_count']}` targets passed",
        )
    if summary["failures"]:
        lines.extend(["", "### Failures", *[f"- {item}" for item in summary["failures"]]])
    if summary["warnings"]:
        lines.extend(["", "### Warnings", *[f"- {item}" for item in summary["warnings"]]])
    return "\n".join(lines) + "\n"


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    errors = []
    try:
        agent = load_json(args.agent_soak, "Agent soak summary")
        platform = load_json(args.platform_matrix, "platform matrix")
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    cluster, cluster_error = load_optional(args.real_cluster, "real-cluster readiness")
    llm, llm_error = load_optional(args.llm_campaign, "LLM campaign summary")
    errors.extend(item for item in (cluster_error, llm_error) if item)
    summary = build_summary(
        agent,
        cluster,
        llm,
        platform,
        require_real_cluster=args.require_real_cluster,
        input_errors=errors,
    )
    write_text(args.output, json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if args.markdown_output:
        write_text(args.markdown_output, markdown(summary))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if summary["status"] == "failed" else 0


if __name__ == "__main__":
    raise SystemExit(main())
