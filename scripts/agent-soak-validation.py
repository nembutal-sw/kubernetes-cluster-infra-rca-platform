#!/usr/bin/env python3
"""Repeat read-only Agent collection and evaluate long-running stability signals."""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent-soak-validation/v1"
THRESHOLD_SCHEMA_VERSION = "agent-soak-thresholds/v1"
EVIDENCE_SCHEMA_VERSION = "collector-evidence/v1"
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / "config" / "agent-soak-thresholds.json"
DEGRADED_STATUSES = {"disabled", "error", "failed", "unsupported"}
REQUIRED_PROFILE_KEYS = {
    "iterations",
    "interval_seconds",
    "command_timeout_seconds",
    "minimum_success_rate",
    "minimum_evidence_quality_rate",
    "minimum_health_probe_success_rate",
    "maximum_degraded_collector_rate",
    "maximum_p95_collection_seconds",
    "maximum_payload_bytes",
    "maximum_rss_growth_mb",
    "maximum_p95_cpu_percent",
    "maximum_fd_growth",
    "maximum_thread_growth",
    "maximum_spool_files",
    "maximum_spool_bytes",
    "maximum_quarantine_files",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run repeated read-only Node Agent local collection and enforce stability, "
            "evidence quality, process, health, and spool thresholds."
        )
    )
    parser.add_argument("--profile", default="smoke", help="Threshold profile name.")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="Threshold configuration JSON.")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory for checkpoints and summary.")
    parser.add_argument("--collectors", default="", help="Comma-separated collector override.")
    parser.add_argument("--iterations", type=int, help="Iteration override for bounded validation runs.")
    parser.add_argument("--interval-seconds", type=float, help="Interval override between iteration start times.")
    parser.add_argument("--agent-pid", type=int, help="Optional long-running Agent PID observed through /proc.")
    parser.add_argument("--state-dir", type=Path, help="Optional Agent state directory used for spool checks.")
    parser.add_argument("--health-url", default="", help="Optional unauthenticated HTTP(S) health endpoint.")
    parser.add_argument(
        "--retain-evidence",
        action="store_true",
        help="Retain raw per-iteration evidence. It can contain sensitive host data.",
    )
    return parser.parse_args()


def load_configuration(path: Path, profile_name: str) -> tuple[list[str], dict[str, Any]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to load threshold config: {exc}") from exc
    if payload.get("schema_version") != THRESHOLD_SCHEMA_VERSION:
        raise ValueError(f"threshold config must use {THRESHOLD_SCHEMA_VERSION}")
    collectors = payload.get("collectors")
    profiles = payload.get("profiles")
    if not isinstance(collectors, list) or not collectors or not all(isinstance(item, str) and item for item in collectors):
        raise ValueError("threshold config collectors must be a non-empty string list")
    if not isinstance(profiles, dict) or profile_name not in profiles:
        raise ValueError(f"unknown threshold profile: {profile_name}")
    profile = profiles[profile_name]
    if not isinstance(profile, dict):
        raise ValueError(f"threshold profile is invalid: {profile_name}")
    missing = sorted(REQUIRED_PROFILE_KEYS - set(profile))
    if missing:
        raise ValueError("threshold profile is missing keys: " + ", ".join(missing))
    validate_profile(profile)
    return list(dict.fromkeys(collectors)), dict(profile)


def validate_profile(profile: dict[str, Any]) -> None:
    positive = (
        "iterations",
        "command_timeout_seconds",
        "maximum_p95_collection_seconds",
        "maximum_payload_bytes",
        "maximum_p95_cpu_percent",
    )
    non_negative = (
        "interval_seconds",
        "maximum_rss_growth_mb",
        "maximum_fd_growth",
        "maximum_thread_growth",
        "maximum_spool_files",
        "maximum_spool_bytes",
        "maximum_quarantine_files",
    )
    rates = (
        "minimum_success_rate",
        "minimum_evidence_quality_rate",
        "minimum_health_probe_success_rate",
        "maximum_degraded_collector_rate",
    )
    if any(not isinstance(profile[key], (int, float)) or profile[key] <= 0 for key in positive):
        raise ValueError("positive threshold values must be greater than zero")
    if any(not isinstance(profile[key], (int, float)) or profile[key] < 0 for key in non_negative):
        raise ValueError("non-negative threshold values cannot be below zero")
    if any(not isinstance(profile[key], (int, float)) or not 0 <= profile[key] <= 1 for key in rates):
        raise ValueError("rate thresholds must be between zero and one")


def parse_collectors(value: str, defaults: list[str]) -> list[str]:
    if not value.strip():
        return defaults
    collectors = list(dict.fromkeys(item.strip() for item in value.split(",") if item.strip()))
    if not collectors or any(not item.replace("-", "").replace("_", "").isalnum() for item in collectors):
        raise ValueError("collectors must be a comma-separated list of safe collector names")
    return collectors


def validate_health_url(value: str) -> str:
    if not value:
        return ""
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("health URL must use http or https")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("health URL must not include credentials, query parameters, or fragments")
    return value.rstrip("/")


def process_snapshot(pid: int | None) -> dict[str, int | float] | None:
    if pid is None or os.name != "posix":
        return None
    proc = Path("/proc") / str(pid)
    try:
        status = {}
        for line in (proc / "status").read_text(encoding="utf-8", errors="replace").splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                status[key] = value.strip()
        rss_kb = int((status.get("VmRSS") or "0 kB").split()[0])
        stat_text = (proc / "stat").read_text(encoding="utf-8", errors="replace")
        stat_fields = stat_text[stat_text.rfind(")") + 2 :].split()
        clock_ticks = os.sysconf("SC_CLK_TCK")
        return {
            "rss_bytes": rss_kb * 1024,
            "fd_count": sum(1 for _ in (proc / "fd").iterdir()),
            "thread_count": sum(1 for _ in (proc / "task").iterdir()),
            "cpu_seconds": (int(stat_fields[11]) + int(stat_fields[12])) / clock_ticks,
            "sampled_at_monotonic": time.monotonic(),
        }
    except (FileNotFoundError, PermissionError, OSError, ValueError, IndexError):
        return None


def spool_snapshot(state_dir: Path | None) -> dict[str, int] | None:
    if state_dir is None:
        return None
    if not state_dir.exists():
        return None
    spool_dir = state_dir / "spool"
    if not spool_dir.exists():
        return {"pending_files": 0, "pending_bytes": 0, "quarantine_files": 0}
    try:
        pending = list(spool_dir.glob("*.json"))
        quarantine = list(spool_dir.glob("*.invalid"))
        return {
            "pending_files": len(pending),
            "pending_bytes": sum(path.stat().st_size for path in pending),
            "quarantine_files": len(quarantine),
        }
    except (PermissionError, OSError):
        return None


def health_probe(url: str, timeout_seconds: float = 5) -> bool | None:
    if not url:
        return None
    request = urllib.request.Request(url, headers={"User-Agent": "cluster-rca-agent-soak/1"})
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return 200 <= response.status < 300
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def evaluate_evidence(payload: Any, requested: list[str]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {"quality": False, "missing": requested, "degraded": requested, "collector_count": 0}
    collectors = payload.get("collectors")
    if not isinstance(collectors, dict):
        return {"quality": False, "missing": requested, "degraded": requested, "collector_count": 0}
    missing = sorted(set(requested) - set(collectors))
    invalid = []
    degraded = []
    for name in requested:
        value = collectors.get(name)
        if not isinstance(value, dict) or value.get("_schema_version") != EVIDENCE_SCHEMA_VERSION:
            invalid.append(name)
        if not isinstance(value, dict) or str(value.get("status") or "").lower() in DEGRADED_STATUSES:
            degraded.append(name)
    return {
        "quality": not missing and not invalid,
        "missing": missing,
        "invalid_schema": sorted(invalid),
        "degraded": sorted(degraded),
        "collector_count": len(collectors),
    }


def collect_once(
    *,
    output_path: Path,
    collectors: list[str],
    timeout_seconds: float,
) -> tuple[bool, dict[str, Any] | None, str | None, float, int]:
    env = os.environ.copy()
    env.setdefault("AGENT_MODE", "node-diagnostics")
    if os.name == "posix":
        env.setdefault("HOST_ROOT", "/")
        env.setdefault("HOST_PROC", "/proc")
        env.setdefault("HOST_SYS", "/sys")
        env.setdefault("HOST_ETC", "/etc")
        env.setdefault("HOST_VAR_LOG", "/var/log")
        env.setdefault("HOST_RUN", "/run")
    existing_pythonpath = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = f"{ROOT}{os.pathsep}{existing_pythonpath}" if existing_pythonpath else str(ROOT)
    command = [
        sys.executable,
        "-m",
        "node_agent.main",
        "--collect-local",
        "--collectors",
        ",".join(collectors),
        "--output",
        str(output_path),
    ]
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return False, None, "agent local collection timed out", time.monotonic() - started, 0
    duration = time.monotonic() - started
    if completed.returncode != 0:
        return False, None, f"agent local collection exited with status {completed.returncode}", duration, 0
    try:
        raw = output_path.read_bytes()
        return True, json.loads(raw.decode("utf-8")), None, duration, len(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return False, None, "agent local collection produced invalid evidence JSON", duration, 0


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * percentile_value
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def resource_trend(checkpoints: list[dict[str, Any]], key: str) -> dict[str, Any] | None:
    values = [item["process"][key] for item in checkpoints if isinstance(item.get("process"), dict) and key in item["process"]]
    if not values:
        return None
    return {
        "sample_count": len(values),
        "initial": values[0],
        "final": values[-1],
        "minimum": min(values),
        "maximum": max(values),
        "growth": max(values) - values[0],
        "final_delta": values[-1] - values[0],
    }


def cpu_usage_metrics(checkpoints: list[dict[str, Any]]) -> dict[str, Any] | None:
    samples = []
    for item in checkpoints:
        process = item.get("process")
        if isinstance(process, dict) and "cpu_seconds" in process and "sampled_at_monotonic" in process:
            samples.append((float(process["sampled_at_monotonic"]), float(process["cpu_seconds"])))
    if len(samples) < 2:
        return None
    percentages = []
    for previous, current in zip(samples, samples[1:]):
        wall_delta = current[0] - previous[0]
        cpu_delta = current[1] - previous[1]
        if wall_delta <= 0 or cpu_delta < 0:
            continue
        percentages.append(cpu_delta / wall_delta * 100)
    if not percentages:
        return None
    return {
        "sample_count": len(percentages),
        "p50": percentile(percentages, 0.5),
        "p95": percentile(percentages, 0.95),
        "maximum": max(percentages),
    }


def build_summary(
    *,
    profile_name: str,
    profile: dict[str, Any],
    requested_collectors: list[str],
    checkpoints: list[dict[str, Any]],
    started_at: str,
    health_configured: bool,
    process_configured: bool,
    spool_configured: bool,
    interrupted: bool,
) -> dict[str, Any]:
    total = len(checkpoints)
    successes = sum(bool(item.get("success")) for item in checkpoints)
    quality = sum(bool(item.get("evidence_quality")) for item in checkpoints)
    durations = [float(item["duration_seconds"]) for item in checkpoints if item.get("success")]
    payload_sizes = [int(item.get("payload_bytes") or 0) for item in checkpoints]
    collector_observations = total * len(requested_collectors)
    degraded_observations = sum(len(item.get("degraded_collectors") or []) for item in checkpoints)
    health_values = [item["health_probe_ok"] for item in checkpoints if item.get("health_probe_ok") is not None]
    spool_values = [item["spool"] for item in checkpoints if isinstance(item.get("spool"), dict)]
    rss = resource_trend(checkpoints, "rss_bytes")
    fds = resource_trend(checkpoints, "fd_count")
    threads = resource_trend(checkpoints, "thread_count")
    cpu = cpu_usage_metrics(checkpoints)
    metrics = {
        "iterations_completed": total,
        "iterations_target": int(profile["iterations"]),
        "successful_iterations": successes,
        "collection_success_rate": successes / total if total else 0.0,
        "evidence_quality_rate": quality / total if total else 0.0,
        "degraded_collector_rate": degraded_observations / collector_observations if collector_observations else 0.0,
        "collection_duration_seconds": {
            "p50": percentile(durations, 0.5),
            "p95": percentile(durations, 0.95),
            "maximum": max(durations) if durations else None,
        },
        "maximum_payload_bytes": max(payload_sizes) if payload_sizes else 0,
        "health_probe_success_rate": (
            sum(bool(value) for value in health_values) / len(health_values) if health_values else None
        ),
        "process": {"rss_bytes": rss, "fd_count": fds, "thread_count": threads, "cpu_percent": cpu},
        "spool": {
            "maximum_pending_files": max((item["pending_files"] for item in spool_values), default=None),
            "maximum_pending_bytes": max((item["pending_bytes"] for item in spool_values), default=None),
            "maximum_quarantine_files": max((item["quarantine_files"] for item in spool_values), default=None),
        },
    }
    failures = []
    warnings = []
    if interrupted:
        failures.append("validation was interrupted before all iterations completed")
    if total < int(profile["iterations"]):
        failures.append(f"completed {total} of {int(profile['iterations'])} required iterations")
    if metrics["collection_success_rate"] < float(profile["minimum_success_rate"]):
        failures.append("collection success rate is below threshold")
    if metrics["evidence_quality_rate"] < float(profile["minimum_evidence_quality_rate"]):
        failures.append("evidence quality rate is below threshold")
    if metrics["degraded_collector_rate"] > float(profile["maximum_degraded_collector_rate"]):
        failures.append("degraded collector rate exceeds threshold")
    p95 = metrics["collection_duration_seconds"]["p95"]
    if p95 is not None and p95 > float(profile["maximum_p95_collection_seconds"]):
        failures.append("p95 collection duration exceeds threshold")
    if metrics["maximum_payload_bytes"] > int(profile["maximum_payload_bytes"]):
        failures.append("evidence payload exceeds threshold")
    if health_configured:
        health_rate = metrics["health_probe_success_rate"]
        if health_rate is None or health_rate < float(profile["minimum_health_probe_success_rate"]):
            failures.append("health probe success rate is below threshold")
    if process_configured:
        if (
            rss is None
            or fds is None
            or threads is None
            or rss["sample_count"] != total
            or fds["sample_count"] != total
            or threads["sample_count"] != total
            or cpu is None
            or cpu["sample_count"] != total - 1
            or total < 2
        ):
            failures.append("configured Agent PID could not be observed for every iteration")
        else:
            if rss["growth"] > float(profile["maximum_rss_growth_mb"]) * 1024 * 1024:
                failures.append("Agent RSS growth exceeds threshold")
            if cpu["p95"] > float(profile["maximum_p95_cpu_percent"]):
                failures.append("Agent p95 CPU usage exceeds threshold")
            if fds["growth"] > int(profile["maximum_fd_growth"]):
                failures.append("Agent file descriptor growth exceeds threshold")
            if threads["growth"] > int(profile["maximum_thread_growth"]):
                failures.append("Agent thread growth exceeds threshold")
    else:
        warnings.append("Agent PID was not provided; long-running process RSS, CPU, FD, and thread trends were not measured")
    if spool_configured:
        spool = metrics["spool"]
        if spool["maximum_pending_files"] is None:
            failures.append("configured Agent state directory could not be observed")
        else:
            if spool["maximum_pending_files"] > int(profile["maximum_spool_files"]):
                failures.append("Agent spool file count exceeds threshold")
            if spool["maximum_pending_bytes"] > int(profile["maximum_spool_bytes"]):
                failures.append("Agent spool byte count exceeds threshold")
            if spool["maximum_quarantine_files"] > int(profile["maximum_quarantine_files"]):
                failures.append("Agent quarantine file count exceeds threshold")
    else:
        warnings.append("Agent state directory was not provided; spool growth was not measured")
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "failed" if failures else "passed",
        "profile": profile_name,
        "started_at": started_at,
        "completed_at": utc_now(),
        "read_only": True,
        "requested_collectors": requested_collectors,
        "observability": {
            "health_probe_configured": health_configured,
            "agent_process_configured": process_configured,
            "state_dir_configured": spool_configured,
        },
        "thresholds": profile,
        "metrics": metrics,
        "failures": failures,
        "warnings": warnings,
    }


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        temporary.chmod(0o600)
    except OSError:
        pass
    temporary.replace(path)


def append_checkpoint(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    try:
        path.chmod(0o600)
    except OSError:
        pass


def main() -> int:
    args = parse_args()
    try:
        collectors, profile = load_configuration(args.config, args.profile)
        collectors = parse_collectors(args.collectors, collectors)
        health_url = validate_health_url(args.health_url)
        if args.iterations is not None:
            if args.iterations < 1:
                raise ValueError("iterations must be at least one")
            profile["iterations"] = args.iterations
        if args.interval_seconds is not None:
            if args.interval_seconds < 0:
                raise ValueError("interval seconds cannot be negative")
            profile["interval_seconds"] = args.interval_seconds
        if args.agent_pid is not None and args.agent_pid < 1:
            raise ValueError("agent PID must be a positive integer")
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = output_dir / "agent-soak-checkpoints.jsonl"
    summary_path = output_dir / "agent-soak-summary.json"
    temporary_directory = None
    if args.retain_evidence:
        evidence_dir = output_dir / "evidence"
    else:
        temporary_directory = tempfile.TemporaryDirectory(prefix="rca-agent-soak-")
        evidence_dir = Path(temporary_directory.name)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    if checkpoint_path.exists():
        checkpoint_path.unlink()

    started_at = utc_now()
    schedule_started = time.monotonic()
    checkpoints: list[dict[str, Any]] = []
    interrupted = False
    try:
        for index in range(int(profile["iterations"])):
            if index:
                scheduled = schedule_started + index * float(profile["interval_seconds"])
                delay = scheduled - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
            iteration_started = utc_now()
            evidence_path = evidence_dir / f"evidence-{index + 1:05d}.json"
            success, evidence, error, duration, payload_bytes = collect_once(
                output_path=evidence_path,
                collectors=collectors,
                timeout_seconds=float(profile["command_timeout_seconds"]),
            )
            evaluated = evaluate_evidence(evidence, collectors)
            checkpoint = {
                "schema_version": SCHEMA_VERSION,
                "iteration": index + 1,
                "started_at": iteration_started,
                "completed_at": utc_now(),
                "duration_seconds": round(duration, 6),
                "success": success,
                "evidence_quality": bool(success and evaluated["quality"]),
                "collector_count": evaluated["collector_count"],
                "missing_collectors": evaluated["missing"],
                "invalid_schema_collectors": evaluated.get("invalid_schema", []),
                "degraded_collectors": evaluated["degraded"],
                "payload_bytes": payload_bytes,
                "health_probe_ok": health_probe(health_url),
                "process": process_snapshot(args.agent_pid),
                "spool": spool_snapshot(args.state_dir),
                "error": error,
            }
            checkpoints.append(checkpoint)
            append_checkpoint(checkpoint_path, checkpoint)
            if not args.retain_evidence:
                try:
                    evidence_path.unlink()
                except FileNotFoundError:
                    pass
    except KeyboardInterrupt:
        interrupted = True
    finally:
        if temporary_directory is not None:
            temporary_directory.cleanup()

    summary = build_summary(
        profile_name=args.profile,
        profile=profile,
        requested_collectors=collectors,
        checkpoints=checkpoints,
        started_at=started_at,
        health_configured=bool(health_url),
        process_configured=args.agent_pid is not None,
        spool_configured=args.state_dir is not None,
        interrupted=interrupted,
    )
    atomic_write_json(summary_path, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 130 if interrupted else 1 if summary["status"] == "failed" else 0


if __name__ == "__main__":
    raise SystemExit(main())
