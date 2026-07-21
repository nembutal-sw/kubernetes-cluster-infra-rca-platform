#!/usr/bin/env python3
"""Repeat read-only Agent collection and evaluate long-running stability signals."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import hmac
import json
import math
import os
import platform
import secrets
import re
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
COMPARISON_METADATA_SCHEMA_VERSION = "agent-soak-comparison-metadata/v1"
THRESHOLD_SCHEMA_VERSION = "agent-soak-thresholds/v1"
EVIDENCE_SCHEMA_VERSION = "collector-evidence/v1"
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent_soak.platform_evidence import PlatformEvidenceClient, PlatformEvidenceError
from agent_soak.statistics import cpu_usage_metrics, percentile, resource_trend, rss_steady_state_metrics


DEFAULT_CONFIG = ROOT / "config" / "agent-soak-thresholds.json"
DEGRADED_STATUSES = {"disabled", "error", "failed", "unsupported"}
AGENT_POD_SELECTOR = "app.kubernetes.io/part-of=cluster-infra-rca"
KUBERNETES_LABEL_RE = re.compile(r"^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$")
KUBERNETES_SUBDOMAIN_RE = re.compile(r"^[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?$")
POD_RUNTIME_SCRIPT = r"""
import json
import os
import time
from pathlib import Path


def agent_pid():
    matches = []
    current_cgroup = Path("/proc/self/cgroup").read_text(encoding="utf-8", errors="replace")
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            arguments = [part for part in (entry / "cmdline").read_bytes().split(b"\0") if part]
        except (FileNotFoundError, PermissionError, OSError):
            continue
        try:
            process_cgroup = (entry / "cgroup").read_text(encoding="utf-8", errors="replace")
        except (FileNotFoundError, PermissionError, OSError):
            continue
        if process_cgroup != current_cgroup:
            continue
        if any(
            arguments[index] == b"-m" and arguments[index + 1] == b"node_agent.main"
            for index in range(len(arguments) - 1)
        ):
            matches.append(int(entry.name))
    if len(matches) != 1:
        raise SystemExit("expected exactly one Node Agent process")
    return matches[0]


pid = agent_pid()
proc = Path("/proc") / str(pid)
status = {}
for line in (proc / "status").read_text(encoding="utf-8", errors="replace").splitlines():
    if ":" in line:
        key, value = line.split(":", 1)
        status[key] = value.strip()
stat_text = (proc / "stat").read_text(encoding="utf-8", errors="replace")
stat_fields = stat_text[stat_text.rfind(")") + 2 :].split()
rss_kb = int((status.get("VmRSS") or "0 kB").split()[0])
clock_ticks = os.sysconf("SC_CLK_TCK")

state_dir = Path(os.environ.get("AGENT_STATE_DIR", "/var/lib/cluster-infra-rca-agent"))
if not state_dir.is_dir():
    raise SystemExit("Agent state directory is unavailable")
spool_dir = state_dir / "spool"
pending = list(spool_dir.glob("*.json")) if spool_dir.is_dir() else []
quarantine = list(spool_dir.glob("*.invalid")) if spool_dir.is_dir() else []

print(json.dumps({
    "process": {
        "rss_bytes": rss_kb * 1024,
        "fd_count": sum(1 for _ in (proc / "fd").iterdir()),
        "thread_count": sum(1 for _ in (proc / "task").iterdir()),
        "cpu_seconds": (int(stat_fields[11]) + int(stat_fields[12])) / clock_ticks,
        "sampled_at_monotonic": time.monotonic(),
        "process_start_ticks": int(stat_fields[19]),
    },
    "spool": {
        "pending_files": len(pending),
        "pending_bytes": sum(path.stat().st_size for path in pending),
        "quarantine_files": len(quarantine),
    },
}, separators=(",", ":")))
""".strip()
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
FLEET_PROFILE_DEFAULTS = {
    "maximum_fleet_rss_peak_spread_mb": 128,
    "maximum_fleet_p95_cpu_spread_percent": 100,
    "maximum_fleet_fd_spread": 64,
    "maximum_fleet_thread_spread": 64,
}
STEADY_STATE_PROFILE_DEFAULTS = {
    "enforce_rss_steady_state": False,
    "rss_steady_state_warmup_fraction": 0.5,
    "minimum_rss_steady_state_samples": 3,
    "maximum_rss_steady_state_slope_mb_per_hour": 1024,
    "maximum_rss_steady_state_range_mb": 1024,
    "maximum_rss_consecutive_growth_samples": 1000000,
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def canonical_sha256(value: Any) -> str:
    rendered = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(rendered.encode("utf-8")).hexdigest()


def threshold_config_sha256(path: Path) -> str:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to fingerprint threshold config: {exc}") from exc
    return canonical_sha256(payload)


def build_comparison_metadata(
    *,
    config_path: Path,
    requested_collectors: list[str],
    collector_execution_source: str,
    inherited: dict[str, Any] | None = None,
) -> dict[str, Any]:
    inherited = inherited if isinstance(inherited, dict) else {}
    return {
        "schema_version": COMPARISON_METADATA_SCHEMA_VERSION,
        "platform_family": inherited.get("platform_family")
        or os.environ.get("RCA_AGENT_SOAK_PLATFORM_FAMILY")
        or "unknown",
        "architecture": inherited.get("architecture")
        or os.environ.get("RCA_AGENT_SOAK_ARCHITECTURE")
        or platform.machine().lower()
        or "unknown",
        "agent_version": inherited.get("agent_version")
        or os.environ.get("RCA_AGENT_SOAK_AGENT_VERSION")
        or "unknown",
        "collector_execution_source": collector_execution_source,
        "requested_collectors_sha256": canonical_sha256(sorted(requested_collectors)),
        "threshold_config_sha256": threshold_config_sha256(config_path),
    }


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
    parser.add_argument(
        "--agent-pod",
        default="",
        help="Optional Agent Pod target in namespace/name form for read-only process and spool observation.",
    )
    parser.add_argument(
        "--discover-agent-pod",
        action="store_true",
        help="Discover exactly one Ready Agent Pod using the chart's stable application label.",
    )
    parser.add_argument(
        "--discover-agent-pods",
        action="store_true",
        help="Discover and observe every Ready Agent Pod as a redacted fleet.",
    )
    parser.add_argument(
        "--platform-evidence-fleet",
        action="store_true",
        help=(
            "Collect fleet evidence through Platform requests handled by the long-running Agents. "
            "Credentials are read only from RCA_AGENT_SOAK_PLATFORM_* environment variables."
        ),
    )
    parser.add_argument(
        "--minimum-agent-pods",
        type=int,
        default=2,
        help="Minimum Ready Agent Pod count required by fleet discovery.",
    )
    parser.add_argument("--agent-container", default="agent", help="Agent container name used by kubectl exec.")
    parser.add_argument("--kubectl-context", default="", help="Optional kubectl context for Agent Pod observation.")
    parser.add_argument(
        "--require-runtime-observation",
        action="store_true",
        help="Fail when process or spool observations are unavailable.",
    )
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
    normalized_profile = dict(profile)
    for key, value in {**FLEET_PROFILE_DEFAULTS, **STEADY_STATE_PROFILE_DEFAULTS}.items():
        normalized_profile.setdefault(key, value)
    validate_profile(normalized_profile)
    return list(dict.fromkeys(collectors)), normalized_profile


def validate_profile(profile: dict[str, Any]) -> None:
    if not isinstance(profile["enforce_rss_steady_state"], bool):
        raise ValueError("RSS steady-state enforcement flag must be boolean")
    if not isinstance(profile["minimum_rss_steady_state_samples"], int):
        raise ValueError("minimum RSS steady-state samples must be an integer")
    positive = (
        "iterations",
        "command_timeout_seconds",
        "maximum_p95_collection_seconds",
        "maximum_payload_bytes",
        "maximum_p95_cpu_percent",
        "minimum_rss_steady_state_samples",
    )
    non_negative = (
        "interval_seconds",
        "maximum_rss_growth_mb",
        "maximum_fd_growth",
        "maximum_thread_growth",
        "maximum_spool_files",
        "maximum_spool_bytes",
        "maximum_quarantine_files",
        "maximum_fleet_rss_peak_spread_mb",
        "maximum_fleet_p95_cpu_spread_percent",
        "maximum_fleet_fd_spread",
        "maximum_fleet_thread_spread",
        "maximum_rss_steady_state_slope_mb_per_hour",
        "maximum_rss_steady_state_range_mb",
        "maximum_rss_consecutive_growth_samples",
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
    warmup_fraction = profile["rss_steady_state_warmup_fraction"]
    if not isinstance(warmup_fraction, (int, float)) or not 0 <= warmup_fraction < 1:
        raise ValueError("RSS steady-state warm-up fraction must be between zero and one")


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


def validate_kubernetes_label(value: str, field: str) -> str:
    if not value or len(value) > 63 or not KUBERNETES_LABEL_RE.fullmatch(value):
        raise ValueError(f"{field} must be a valid Kubernetes DNS label")
    return value


def parse_agent_pod(value: str) -> tuple[str, str]:
    parts = value.split("/")
    if len(parts) != 2:
        raise ValueError("agent Pod must use namespace/name form")
    namespace, name = parts
    validate_kubernetes_label(namespace, "agent Pod namespace")
    labels = name.split(".")
    if (
        not name
        or len(name) > 253
        or not KUBERNETES_SUBDOMAIN_RE.fullmatch(name)
        or any(len(label) > 63 or not KUBERNETES_LABEL_RE.fullmatch(label) for label in labels)
    ):
        raise ValueError("agent Pod name must be a valid Kubernetes DNS subdomain")
    return namespace, name


def validate_kubectl_context(value: str) -> str:
    if len(value) > 253 or any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError("kubectl context contains unsupported characters")
    return value


def kubectl_prefix(context: str) -> list[str]:
    command = ["kubectl", "--request-timeout=10s"]
    if context:
        command.extend(["--context", context])
    return command


def redacted_target_id(target: str, redaction_salt: bytes) -> str:
    return hmac.new(redaction_salt, target.encode("utf-8"), hashlib.sha256).hexdigest()[:16]


def ready_agent_pod_targets(payload: Any, container: str, redaction_salt: bytes) -> list[dict[str, str]]:
    candidates = []
    for item in payload.get("items", []) if isinstance(payload, dict) else []:
        if not isinstance(item, dict):
            continue
        metadata = item.get("metadata") or {}
        spec = item.get("spec") or {}
        status = item.get("status") or {}
        if not isinstance(metadata, dict) or not isinstance(spec, dict) or not isinstance(status, dict):
            continue
        namespace = metadata.get("namespace")
        name = metadata.get("name")
        node_name = spec.get("nodeName")
        conditions = status.get("conditions") or []
        container_statuses = status.get("containerStatuses") or []
        if not isinstance(conditions, list) or not isinstance(container_statuses, list):
            continue
        pod_ready = any(
            condition.get("type") == "Ready" and condition.get("status") == "True"
            for condition in conditions
            if isinstance(condition, dict)
        )
        container_ready = any(
            entry.get("name") == container and entry.get("ready") is True
            for entry in container_statuses
            if isinstance(entry, dict)
        )
        agent_pod = f"{namespace}/{name}"
        if status.get("phase") != "Running" or not pod_ready or not container_ready:
            continue
        try:
            parse_agent_pod(agent_pod)
            if (
                not isinstance(node_name, str)
                or len(node_name) > 253
                or not KUBERNETES_SUBDOMAIN_RE.fullmatch(node_name)
            ):
                continue
        except ValueError:
            continue
        candidates.append(
            {
                "agent_pod": agent_pod,
                "node_name": node_name,
                "target_id": redacted_target_id(node_name, redaction_salt),
            }
        )
    candidates = sorted(candidates, key=lambda item: item["agent_pod"])
    if len({item["node_name"] for item in candidates}) != len(candidates):
        return []
    return candidates


def discover_agent_pods(
    context: str,
    container: str,
    timeout_seconds: float,
) -> tuple[list[dict[str, str]], str | None]:
    command = kubectl_prefix(context) + [
        "get",
        "pods",
        "--all-namespaces",
        "--selector",
        AGENT_POD_SELECTOR,
        "--output",
        "json",
    ]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=min(max(timeout_seconds, 1.0), 15.0),
            check=False,
        )
    except subprocess.TimeoutExpired:
        return [], "Agent Pod discovery timed out"
    except (FileNotFoundError, OSError):
        return [], "kubectl is unavailable for Agent Pod discovery"
    if completed.returncode != 0:
        return [], f"Agent Pod discovery exited with status {completed.returncode}"
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return [], "Agent Pod discovery returned invalid JSON"
    candidates = ready_agent_pod_targets(payload, container, secrets.token_bytes(32))
    if not candidates:
        return [], "no Ready Agent Pod was discovered"
    return candidates, None


def discover_agent_pod(context: str, container: str, timeout_seconds: float) -> tuple[str | None, str | None]:
    candidates, error = discover_agent_pods(context, container, timeout_seconds)
    if error:
        return None, error
    if len(candidates) > 1:
        return None, "multiple Ready Agent Pods were discovered; specify --agent-pod"
    return candidates[0]["agent_pod"], None


def _non_negative_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and value >= 0
    )


def validate_runtime_snapshot(payload: Any) -> tuple[dict[str, int | float], dict[str, int]] | None:
    if not isinstance(payload, dict):
        return None
    process = payload.get("process")
    spool = payload.get("spool")
    process_keys = {
        "rss_bytes",
        "fd_count",
        "thread_count",
        "cpu_seconds",
        "sampled_at_monotonic",
        "process_start_ticks",
    }
    spool_keys = {"pending_files", "pending_bytes", "quarantine_files"}
    if not isinstance(process, dict) or not isinstance(spool, dict):
        return None
    if any(not _non_negative_number(process.get(key)) for key in process_keys):
        return None
    if any(
        not isinstance(spool.get(key), int) or isinstance(spool.get(key), bool) or spool[key] < 0
        for key in spool_keys
    ):
        return None
    normalized_process = {key: process[key] for key in process_keys}
    normalized_spool = {key: spool[key] for key in spool_keys}
    return normalized_process, normalized_spool


def pod_runtime_snapshot(
    agent_pod: str,
    container: str,
    context: str,
    timeout_seconds: float,
) -> tuple[dict[str, int | float] | None, dict[str, int] | None, str | None]:
    namespace, pod_name = parse_agent_pod(agent_pod)
    command = kubectl_prefix(context) + [
        "exec",
        "--namespace",
        namespace,
        pod_name,
        "--container",
        container,
        "--",
        "python",
        "-c",
        POD_RUNTIME_SCRIPT,
    ]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=min(max(timeout_seconds, 1.0), 15.0),
            check=False,
        )
    except subprocess.TimeoutExpired:
        return None, None, "Agent Pod runtime snapshot timed out"
    except (FileNotFoundError, OSError):
        return None, None, "kubectl is unavailable for Agent Pod runtime observation"
    if completed.returncode != 0:
        return None, None, f"Agent Pod runtime snapshot exited with status {completed.returncode}"
    try:
        validated = validate_runtime_snapshot(json.loads(completed.stdout))
    except json.JSONDecodeError:
        validated = None
    if validated is None:
        return None, None, "Agent Pod runtime snapshot returned invalid JSON"
    return validated[0], validated[1], None


def fleet_runtime_snapshots(
    targets: list[dict[str, str]],
    container: str,
    context: str,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    def observe(target: dict[str, str]) -> dict[str, Any]:
        process, spool, error = pod_runtime_snapshot(
            target["agent_pod"],
            container,
            context,
            timeout_seconds,
        )
        return {
            "target_id": target["target_id"],
            "process": process,
            "spool": spool,
            "runtime_observation_error": error,
        }

    if not targets:
        return []
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(targets), 8)) as executor:
        observations = list(executor.map(observe, targets))
    return sorted(observations, key=lambda item: item["target_id"])


def fleet_evidence_observations(
    client: PlatformEvidenceClient,
    targets: list[dict[str, str]],
    collectors: list[str],
    *,
    iteration: int,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    raw_results = client.collect_fleet(
        targets,
        collectors,
        iteration=iteration,
        completion_timeout_seconds=timeout_seconds,
    )
    observations = []
    for result in raw_results:
        evaluated = evaluate_evidence({"collectors": result.get("collectors")}, collectors)
        success = bool(result.get("success"))
        observations.append(
            {
                "target_id": result["target_id"],
                "success": success,
                "evidence_quality": bool(success and evaluated["quality"]),
                "collector_count": evaluated["collector_count"],
                "missing_collectors": evaluated["missing"],
                "invalid_schema_collectors": evaluated.get("invalid_schema", []),
                "degraded_collectors": evaluated["degraded"],
                "duration_seconds": result.get("duration_seconds"),
                "payload_bytes": int(result.get("payload_bytes") or 0),
                "error": result.get("error"),
            }
        )
    return sorted(observations, key=lambda item: item["target_id"])


def failed_fleet_evidence_observations(
    targets: list[dict[str, str]],
    collectors: list[str],
    error: str,
) -> list[dict[str, Any]]:
    return [
        {
            "target_id": target["target_id"],
            "success": False,
            "evidence_quality": False,
            "collector_count": 0,
            "missing_collectors": list(collectors),
            "invalid_schema_collectors": list(collectors),
            "degraded_collectors": list(collectors),
            "duration_seconds": None,
            "payload_bytes": 0,
            "error": error,
        }
        for target in sorted(targets, key=lambda item: item["target_id"])
    ]


def combine_fleet_observations(
    runtime: list[dict[str, Any]],
    collection: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    runtime_by_target = {item.get("target_id"): item for item in runtime}
    collection_by_target = {item.get("target_id"): item for item in collection}
    target_ids = sorted(set(runtime_by_target) | set(collection_by_target))
    return [
        {
            "target_id": target_id,
            "process": (runtime_by_target.get(target_id) or {}).get("process"),
            "spool": (runtime_by_target.get(target_id) or {}).get("spool"),
            "runtime_observation_error": (runtime_by_target.get(target_id) or {}).get(
                "runtime_observation_error"
            ),
            "collection": collection_by_target.get(target_id),
        }
        for target_id in target_ids
    ]


def aggregate_fleet_collection(collection: list[dict[str, Any]]) -> dict[str, Any]:
    durations = [
        float(item["duration_seconds"])
        for item in collection
        if isinstance(item.get("duration_seconds"), (int, float))
    ]
    failed_count = sum(not bool(item.get("success")) for item in collection)
    quality = all(bool(item.get("evidence_quality")) for item in collection) if collection else False
    return {
        "success": bool(collection) and failed_count == 0,
        "evidence_quality": quality,
        "collector_count": min((int(item.get("collector_count") or 0) for item in collection), default=0),
        "missing_collectors": sorted(
            {name for item in collection for name in item.get("missing_collectors", [])}
        ),
        "invalid_schema_collectors": sorted(
            {name for item in collection for name in item.get("invalid_schema_collectors", [])}
        ),
        "degraded_collectors": sorted(
            {name for item in collection for name in item.get("degraded_collectors", [])}
        ),
        "duration_seconds": max(durations, default=0.0),
        "payload_bytes": max((int(item.get("payload_bytes") or 0) for item in collection), default=0),
        "error": f"{failed_count} Agent fleet evidence requests failed" if failed_count else None,
    }


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
            "process_start_ticks": int(stat_fields[19]),
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
    runtime_observation_required: bool = False,
    runtime_observation_source: str = "none",
    comparison_metadata: dict[str, Any] | None = None,
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
    rss_steady_state = rss_steady_state_metrics(
        checkpoints,
        warmup_fraction=float(profile["rss_steady_state_warmup_fraction"]),
        minimum_samples=int(profile["minimum_rss_steady_state_samples"]),
        interval_seconds=float(profile["interval_seconds"]),
    )
    if rss is not None:
        rss["steady_state"] = rss_steady_state
    fds = resource_trend(checkpoints, "fd_count")
    threads = resource_trend(checkpoints, "thread_count")
    cpu = cpu_usage_metrics(checkpoints)
    process_start_ticks = [
        int(item["process"]["process_start_ticks"])
        for item in checkpoints
        if isinstance(item.get("process"), dict) and "process_start_ticks" in item["process"]
    ]
    runtime_observation_errors = sum(bool(item.get("runtime_observation_error")) for item in checkpoints)
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
        "process": {
            "rss_bytes": rss,
            "fd_count": fds,
            "thread_count": threads,
            "cpu_percent": cpu,
            "identity": {
                "sample_count": len(process_start_ticks),
                "stable": len(process_start_ticks) == total and len(set(process_start_ticks)) == 1,
            },
        },
        "spool": {
            "maximum_pending_files": max((item["pending_files"] for item in spool_values), default=None),
            "maximum_pending_bytes": max((item["pending_bytes"] for item in spool_values), default=None),
            "maximum_quarantine_files": max((item["quarantine_files"] for item in spool_values), default=None),
        },
        "runtime_observation_errors": runtime_observation_errors,
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
            or len(process_start_ticks) != total
            or total < 2
        ):
            failures.append("configured Agent process could not be observed for every iteration")
        else:
            if len(set(process_start_ticks)) != 1:
                failures.append("Agent process restarted during validation")
            if rss["growth"] > float(profile["maximum_rss_growth_mb"]) * 1024 * 1024:
                failures.append("Agent RSS growth exceeds threshold")
            if profile["enforce_rss_steady_state"]:
                if rss_steady_state is None or not rss_steady_state["sufficient_samples"]:
                    failures.append("Agent RSS steady-state sample count is below threshold")
                else:
                    steady_slope = rss_steady_state["slope_bytes_per_hour"]
                    if steady_slope is None:
                        failures.append("Agent RSS steady-state slope could not be calculated")
                    elif steady_slope > float(profile["maximum_rss_steady_state_slope_mb_per_hour"]) * 1024 * 1024:
                        failures.append("Agent RSS steady-state slope exceeds threshold")
                    if rss_steady_state["range"] > float(profile["maximum_rss_steady_state_range_mb"]) * 1024 * 1024:
                        failures.append("Agent RSS steady-state range exceeds threshold")
                    if rss_steady_state["maximum_consecutive_increases"] > int(
                        profile["maximum_rss_consecutive_growth_samples"]
                    ):
                        failures.append("Agent RSS consecutive growth exceeds threshold")
            if cpu["p95"] > float(profile["maximum_p95_cpu_percent"]):
                failures.append("Agent p95 CPU usage exceeds threshold")
            if fds["growth"] > int(profile["maximum_fd_growth"]):
                failures.append("Agent file descriptor growth exceeds threshold")
            if threads["growth"] > int(profile["maximum_thread_growth"]):
                failures.append("Agent thread growth exceeds threshold")
    else:
        message = "Agent process observation was not configured; RSS, CPU, FD, and thread trends were not measured"
        (failures if runtime_observation_required else warnings).append(message)
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
        message = "Agent spool observation was not configured; spool growth was not measured"
        (failures if runtime_observation_required else warnings).append(message)
    if runtime_observation_required and runtime_observation_errors:
        failures.append("Agent runtime observation failed during one or more iterations")
    summary = {
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
            "runtime_observation_required": runtime_observation_required,
            "runtime_observation_source": runtime_observation_source,
        },
        "thresholds": profile,
        "metrics": metrics,
        "failures": failures,
        "warnings": warnings,
    }
    if comparison_metadata is not None:
        summary["comparison_metadata"] = comparison_metadata
    return summary


def numeric_spread(values: list[float | int]) -> dict[str, float | int | None]:
    if not values:
        return {"minimum": None, "maximum": None, "spread": None}
    return {
        "minimum": min(values),
        "maximum": max(values),
        "spread": max(values) - min(values),
    }


def build_fleet_summary(
    *,
    profile_name: str,
    profile: dict[str, Any],
    requested_collectors: list[str],
    checkpoints: list[dict[str, Any]],
    target_ids: list[str],
    minimum_target_count: int,
    started_at: str,
    health_configured: bool,
    interrupted: bool,
    collector_execution_source: str = "runner_local",
    comparison_metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    summary = build_summary(
        profile_name=profile_name,
        profile=profile,
        requested_collectors=requested_collectors,
        checkpoints=checkpoints,
        started_at=started_at,
        health_configured=health_configured,
        process_configured=False,
        spool_configured=False,
        interrupted=interrupted,
        runtime_observation_required=False,
        runtime_observation_source="fleet",
        comparison_metadata=comparison_metadata,
    )
    summary["warnings"] = [
        item
        for item in summary["warnings"]
        if not item.startswith("Agent process observation") and not item.startswith("Agent spool observation")
    ]
    fleet_targets = []
    process_metrics = []
    spool_metrics = []
    target_failures = []
    collection_metrics = []
    for target_id in sorted(target_ids):
        target_points = []
        for checkpoint in checkpoints:
            observation = next(
                (
                    item
                    for item in checkpoint.get("targets", [])
                    if isinstance(item, dict) and item.get("target_id") == target_id
                ),
                {},
            )
            target_point = dict(checkpoint)
            target_point["process"] = observation.get("process")
            target_point["spool"] = observation.get("spool")
            target_point["runtime_observation_error"] = observation.get("runtime_observation_error")
            collection = observation.get("collection")
            if isinstance(collection, dict):
                target_point.update(
                    {
                        "success": bool(collection.get("success")),
                        "evidence_quality": bool(collection.get("evidence_quality")),
                        "collector_count": int(collection.get("collector_count") or 0),
                        "missing_collectors": collection.get("missing_collectors") or [],
                        "invalid_schema_collectors": collection.get("invalid_schema_collectors") or [],
                        "degraded_collectors": collection.get("degraded_collectors") or [],
                        "duration_seconds": float(collection.get("duration_seconds") or 0),
                        "payload_bytes": int(collection.get("payload_bytes") or 0),
                        "error": collection.get("error"),
                    }
                )
            target_points.append(target_point)
        target_summary = build_summary(
            profile_name=profile_name,
            profile=profile,
            requested_collectors=requested_collectors,
            checkpoints=target_points,
            started_at=started_at,
            health_configured=False,
            process_configured=True,
            spool_configured=True,
            interrupted=interrupted,
            runtime_observation_required=True,
            runtime_observation_source="pod",
        )
        target_gate_failures = (
            list(target_summary["failures"])
            if collector_execution_source == "platform_evidence_request"
            else [
                item
                for item in target_summary["failures"]
                if item.startswith("Agent") or item.startswith("configured Agent")
            ]
        )
        process = target_summary["metrics"]["process"]
        spool = target_summary["metrics"]["spool"]
        collection_metric = {
            "iterations_completed": target_summary["metrics"]["iterations_completed"],
            "successful_iterations": target_summary["metrics"]["successful_iterations"],
            "collection_success_rate": target_summary["metrics"]["collection_success_rate"],
            "evidence_quality_rate": target_summary["metrics"]["evidence_quality_rate"],
            "degraded_collector_rate": target_summary["metrics"]["degraded_collector_rate"],
            "collection_duration_seconds": target_summary["metrics"]["collection_duration_seconds"],
            "maximum_payload_bytes": target_summary["metrics"]["maximum_payload_bytes"],
        }
        process_metrics.append(process)
        spool_metrics.append(spool)
        collection_metrics.append(collection_metric)
        target_status = "failed" if target_gate_failures else "passed"
        fleet_targets.append(
            {
                "target_id": target_id,
                "status": target_status,
                "rss_peak_bytes": (process.get("rss_bytes") or {}).get("maximum"),
                "rss_growth_bytes": (process.get("rss_bytes") or {}).get("growth"),
                "rss_steady_state_slope_bytes_per_hour": (
                    ((process.get("rss_bytes") or {}).get("steady_state") or {}).get("slope_bytes_per_hour")
                ),
                "rss_steady_state_range_bytes": (
                    ((process.get("rss_bytes") or {}).get("steady_state") or {}).get("range")
                ),
                "rss_maximum_consecutive_increases": (
                    ((process.get("rss_bytes") or {}).get("steady_state") or {}).get(
                        "maximum_consecutive_increases"
                    )
                ),
                "p95_cpu_percent": (process.get("cpu_percent") or {}).get("p95"),
                "fd_peak": (process.get("fd_count") or {}).get("maximum"),
                "fd_growth": (process.get("fd_count") or {}).get("growth"),
                "thread_peak": (process.get("thread_count") or {}).get("maximum"),
                "thread_growth": (process.get("thread_count") or {}).get("growth"),
                "process_identity_stable": (process.get("identity") or {}).get("stable", False),
                "maximum_spool_files": spool.get("maximum_pending_files"),
                "maximum_spool_bytes": spool.get("maximum_pending_bytes"),
                "maximum_quarantine_files": spool.get("maximum_quarantine_files"),
                "runtime_observation_errors": target_summary["metrics"].get("runtime_observation_errors", 0),
                "collection_success_rate": collection_metric["collection_success_rate"],
                "evidence_quality_rate": collection_metric["evidence_quality_rate"],
                "degraded_collector_rate": collection_metric["degraded_collector_rate"],
                "p95_collection_seconds": collection_metric["collection_duration_seconds"]["p95"],
                "maximum_payload_bytes": collection_metric["maximum_payload_bytes"],
                "failures": target_gate_failures,
            }
        )
        if target_gate_failures:
            target_failures.append(f"fleet target {target_id} failed one or more thresholds")

    rss_trends = [item.get("rss_bytes") for item in process_metrics if isinstance(item.get("rss_bytes"), dict)]
    fd_trends = [item.get("fd_count") for item in process_metrics if isinstance(item.get("fd_count"), dict)]
    thread_trends = [item.get("thread_count") for item in process_metrics if isinstance(item.get("thread_count"), dict)]
    cpu_metrics = [item.get("cpu_percent") for item in process_metrics if isinstance(item.get("cpu_percent"), dict)]
    steady_rss_metrics = [
        item["steady_state"]
        for item in rss_trends
        if isinstance(item.get("steady_state"), dict)
    ]
    worst_rss = max(rss_trends, key=lambda item: item.get("growth", -1), default=None)
    worst_fd = max(fd_trends, key=lambda item: item.get("growth", -1), default=None)
    worst_thread = max(thread_trends, key=lambda item: item.get("growth", -1), default=None)
    worst_cpu = max(cpu_metrics, key=lambda item: item.get("p95", -1), default=None)
    identity_stable = bool(process_metrics) and all(
        bool((item.get("identity") or {}).get("stable")) for item in process_metrics
    )
    rss_peak_values = [item["rss_peak_bytes"] for item in fleet_targets if item["rss_peak_bytes"] is not None]
    rss_steady_slope_values = [
        item["rss_steady_state_slope_bytes_per_hour"]
        for item in fleet_targets
        if item["rss_steady_state_slope_bytes_per_hour"] is not None
    ]
    rss_steady_range_values = [
        item["rss_steady_state_range_bytes"]
        for item in fleet_targets
        if item["rss_steady_state_range_bytes"] is not None
    ]
    cpu_p95_values = [item["p95_cpu_percent"] for item in fleet_targets if item["p95_cpu_percent"] is not None]
    fd_peak_values = [item["fd_peak"] for item in fleet_targets if item["fd_peak"] is not None]
    thread_peak_values = [item["thread_peak"] for item in fleet_targets if item["thread_peak"] is not None]
    variation = {
        "rss_peak_bytes": numeric_spread(rss_peak_values),
        "rss_steady_state_slope_bytes_per_hour": numeric_spread(rss_steady_slope_values),
        "rss_steady_state_range_bytes": numeric_spread(rss_steady_range_values),
        "p95_cpu_percent": numeric_spread(cpu_p95_values),
        "fd_peak": numeric_spread(fd_peak_values),
        "thread_peak": numeric_spread(thread_peak_values),
    }
    failures = list(summary["failures"])
    if len(target_ids) < minimum_target_count:
        failures.append(f"discovered {len(target_ids)} of {minimum_target_count} required Agent Pods")
    failures.extend(target_failures)
    if any(checkpoint.get("runtime_observation_error") for checkpoint in checkpoints):
        failures.append("Agent fleet discovery or runtime observation failed")
    rss_spread = variation["rss_peak_bytes"]["spread"]
    if rss_spread is not None and rss_spread > float(profile["maximum_fleet_rss_peak_spread_mb"]) * 1024 * 1024:
        failures.append("Agent fleet RSS peak spread exceeds threshold")
    cpu_spread = variation["p95_cpu_percent"]["spread"]
    if cpu_spread is not None and cpu_spread > float(profile["maximum_fleet_p95_cpu_spread_percent"]):
        failures.append("Agent fleet p95 CPU spread exceeds threshold")
    fd_spread = variation["fd_peak"]["spread"]
    if fd_spread is not None and fd_spread > int(profile["maximum_fleet_fd_spread"]):
        failures.append("Agent fleet file descriptor spread exceeds threshold")
    thread_spread = variation["thread_peak"]["spread"]
    if thread_spread is not None and thread_spread > int(profile["maximum_fleet_thread_spread"]):
        failures.append("Agent fleet thread spread exceeds threshold")

    summary["status"] = "failed" if failures else "passed"
    summary["failures"] = list(dict.fromkeys(failures))
    summary["observability"].update(
        {
            "agent_process_configured": True,
            "state_dir_configured": True,
            "runtime_observation_required": True,
            "runtime_observation_source": "fleet",
            "collector_execution_source": collector_execution_source,
            "fleet_target_count": len(target_ids),
            "minimum_fleet_target_count": minimum_target_count,
        }
    )
    summary["metrics"]["process"] = {
        "rss_bytes": worst_rss,
        "fd_count": worst_fd,
        "thread_count": worst_thread,
        "cpu_percent": worst_cpu,
        "identity": {
            "sample_count": sum((item.get("identity") or {}).get("sample_count", 0) for item in process_metrics),
            "stable": identity_stable,
        },
    }
    summary["metrics"]["spool"] = {
        "maximum_pending_files": max(
            (item.get("maximum_pending_files") for item in spool_metrics if item.get("maximum_pending_files") is not None),
            default=None,
        ),
        "maximum_pending_bytes": max(
            (item.get("maximum_pending_bytes") for item in spool_metrics if item.get("maximum_pending_bytes") is not None),
            default=None,
        ),
        "maximum_quarantine_files": max(
            (
                item.get("maximum_quarantine_files")
                for item in spool_metrics
                if item.get("maximum_quarantine_files") is not None
            ),
            default=None,
        ),
    }
    summary["metrics"]["runtime_observation_errors"] = sum(
        item["runtime_observation_errors"] for item in fleet_targets
    )
    if collector_execution_source == "platform_evidence_request":
        total_collection_observations = sum(item["iterations_completed"] for item in collection_metrics)
        successful_collection_observations = sum(item["successful_iterations"] for item in collection_metrics)
        quality_observations = sum(
            round(item["evidence_quality_rate"] * item["iterations_completed"])
            for item in collection_metrics
        )
        degraded_observations = sum(
            item["degraded_collector_rate"] * item["iterations_completed"] * len(requested_collectors)
            for item in collection_metrics
        )
        collection_durations = [
            float((target.get("collection") or {}).get("duration_seconds"))
            for checkpoint in checkpoints
            for target in checkpoint.get("targets", [])
            if isinstance(target, dict)
            and isinstance(target.get("collection"), dict)
            and isinstance(target["collection"].get("duration_seconds"), (int, float))
            and target["collection"].get("success")
        ]
        collector_execution = {
            "source": collector_execution_source,
            "target_count": len(target_ids),
            "observation_count": total_collection_observations,
            "successful_observations": successful_collection_observations,
            "collection_success_rate": (
                successful_collection_observations / total_collection_observations
                if total_collection_observations
                else 0.0
            ),
            "evidence_quality_rate": (
                quality_observations / total_collection_observations if total_collection_observations else 0.0
            ),
            "degraded_collector_rate": (
                degraded_observations / (total_collection_observations * len(requested_collectors))
                if total_collection_observations and requested_collectors
                else 0.0
            ),
            "collection_duration_seconds": {
                "p50": percentile(collection_durations, 0.5),
                "p95": percentile(collection_durations, 0.95),
                "maximum": max(collection_durations) if collection_durations else None,
            },
            "maximum_payload_bytes": max(
                (item["maximum_payload_bytes"] for item in collection_metrics),
                default=0,
            ),
        }
    else:
        collector_execution = {
            "source": collector_execution_source,
            "target_count": 0,
            "observation_count": summary["metrics"]["iterations_completed"],
            "successful_observations": summary["metrics"]["successful_iterations"],
            "collection_success_rate": summary["metrics"]["collection_success_rate"],
            "evidence_quality_rate": summary["metrics"]["evidence_quality_rate"],
            "degraded_collector_rate": summary["metrics"]["degraded_collector_rate"],
            "collection_duration_seconds": summary["metrics"]["collection_duration_seconds"],
            "maximum_payload_bytes": summary["metrics"]["maximum_payload_bytes"],
        }
    summary["metrics"]["collector_execution"] = collector_execution
    summary["metrics"]["fleet_runtime"] = {
        "source": "kubernetes_pod",
        "target_count": len(target_ids),
        "process_identity_stable": identity_stable,
        "runtime_observation_errors": summary["metrics"]["runtime_observation_errors"],
        "maximum_pending_files": summary["metrics"]["spool"]["maximum_pending_files"],
        "maximum_pending_bytes": summary["metrics"]["spool"]["maximum_pending_bytes"],
        "maximum_quarantine_files": summary["metrics"]["spool"]["maximum_quarantine_files"],
    }
    summary["metrics"]["fleet"] = {
        "target_count": len(target_ids),
        "minimum_target_count": minimum_target_count,
        "passed_target_count": sum(item["status"] == "passed" for item in fleet_targets),
        "targets": fleet_targets,
        "variation": variation,
        "worst_rss_steady_state": {
            "maximum_slope_bytes_per_hour": max(
                (
                    item["slope_bytes_per_hour"]
                    for item in steady_rss_metrics
                    if isinstance(item.get("slope_bytes_per_hour"), (int, float))
                ),
                default=None,
            ),
            "maximum_range_bytes": max(
                (item["range"] for item in steady_rss_metrics if isinstance(item.get("range"), (int, float))),
                default=None,
            ),
            "maximum_consecutive_increases": max(
                (
                    item["maximum_consecutive_increases"]
                    for item in steady_rss_metrics
                    if isinstance(item.get("maximum_consecutive_increases"), int)
                ),
                default=None,
            ),
            "minimum_sample_count": min(
                (item["sample_count"] for item in steady_rss_metrics if isinstance(item.get("sample_count"), int)),
                default=None,
            ),
        },
    }
    return summary


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
    agent_pod = ""
    agent_targets: list[dict[str, str]] = []
    discovery_error = None
    platform_client = None
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
        if args.minimum_agent_pods < 1:
            raise ValueError("minimum Agent Pod count must be at least one")
        validate_kubernetes_label(args.agent_container, "agent container")
        validate_kubectl_context(args.kubectl_context)
        pod_modes = sum(bool(value) for value in (args.agent_pod, args.discover_agent_pod, args.discover_agent_pods))
        if pod_modes > 1:
            raise ValueError("Agent Pod target, single discovery, and fleet discovery are mutually exclusive")
        if pod_modes and (args.agent_pid is not None or args.state_dir is not None):
            raise ValueError("Agent Pod discovery cannot be combined with local PID or state directory observation")
        if args.kubectl_context and not pod_modes:
            raise ValueError("kubectl context requires Agent Pod observation")
        if args.require_runtime_observation and not (
            pod_modes or args.agent_pid is not None or args.state_dir is not None
        ):
            raise ValueError("required runtime observation needs an Agent Pod or local process/state target")
        if args.discover_agent_pods and args.minimum_agent_pods < 2:
            raise ValueError("fleet discovery requires at least two Agent Pods")
        if args.platform_evidence_fleet and not args.discover_agent_pods:
            raise ValueError("Platform fleet evidence requires --discover-agent-pods")
        if args.platform_evidence_fleet:
            platform_client = PlatformEvidenceClient(
                os.environ.get("RCA_AGENT_SOAK_PLATFORM_URL", ""),
                os.environ.get("RCA_AGENT_SOAK_PLATFORM_CLUSTER_ID", ""),
                os.environ.get("RCA_AGENT_SOAK_PLATFORM_ACCESS_TOKEN", ""),
                request_timeout_seconds=min(float(profile["command_timeout_seconds"]), 15.0),
                ca_bundle=os.environ.get("RCA_AGENT_SOAK_PLATFORM_CA_BUNDLE") or None,
            )
        if args.agent_pod:
            parse_agent_pod(args.agent_pod)
            agent_pod = args.agent_pod
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

    if args.discover_agent_pod:
        agent_pod, discovery_error = discover_agent_pod(
            args.kubectl_context,
            args.agent_container,
            float(profile["command_timeout_seconds"]),
        )
    elif args.discover_agent_pods:
        agent_targets, discovery_error = discover_agent_pods(
            args.kubectl_context,
            args.agent_container,
            float(profile["command_timeout_seconds"]),
        )

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
            target_observations = []
            if args.discover_agent_pods:
                if platform_client is not None:
                    try:
                        fleet_collection = fleet_evidence_observations(
                            platform_client,
                            agent_targets,
                            collectors,
                            iteration=index + 1,
                            timeout_seconds=float(profile["command_timeout_seconds"]),
                        )
                    except PlatformEvidenceError as exc:
                        fleet_collection = failed_fleet_evidence_observations(
                            agent_targets,
                            collectors,
                            str(exc),
                        )
                    aggregate = aggregate_fleet_collection(fleet_collection)
                    success = aggregate["success"]
                    error = aggregate["error"]
                    duration = aggregate["duration_seconds"]
                    payload_bytes = aggregate["payload_bytes"]
                    evaluated = {
                        "quality": aggregate["evidence_quality"],
                        "collector_count": aggregate["collector_count"],
                        "missing": aggregate["missing_collectors"],
                        "invalid_schema": aggregate["invalid_schema_collectors"],
                        "degraded": aggregate["degraded_collectors"],
                    }
                else:
                    success, evidence, error, duration, payload_bytes = collect_once(
                        output_path=evidence_path,
                        collectors=collectors,
                        timeout_seconds=float(profile["command_timeout_seconds"]),
                    )
                    evaluated = evaluate_evidence(evidence, collectors)
                    fleet_collection = []
                runtime_observations = fleet_runtime_snapshots(
                    agent_targets,
                    args.agent_container,
                    args.kubectl_context,
                    float(profile["command_timeout_seconds"]),
                )
                target_observations = (
                    combine_fleet_observations(runtime_observations, fleet_collection)
                    if platform_client is not None
                    else runtime_observations
                )
                failed_observations = sum(bool(item.get("runtime_observation_error")) for item in runtime_observations)
                process = None
                spool = None
                runtime_error = discovery_error or (
                    f"{failed_observations} Agent Pod runtime snapshots failed" if failed_observations else None
                )
            else:
                success, evidence, error, duration, payload_bytes = collect_once(
                    output_path=evidence_path,
                    collectors=collectors,
                    timeout_seconds=float(profile["command_timeout_seconds"]),
                )
                evaluated = evaluate_evidence(evidence, collectors)
                if agent_pod:
                    process, spool, runtime_error = pod_runtime_snapshot(
                        agent_pod,
                        args.agent_container,
                        args.kubectl_context,
                        float(profile["command_timeout_seconds"]),
                    )
                else:
                    process = process_snapshot(args.agent_pid)
                    spool = spool_snapshot(args.state_dir)
                    runtime_error = discovery_error
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
                "process": process,
                "spool": spool,
                "targets": target_observations,
                "runtime_observation_error": runtime_error,
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

    if args.discover_agent_pods:
        collector_execution_source = "platform_evidence_request" if platform_client is not None else "runner_local"
        summary = build_fleet_summary(
            profile_name=args.profile,
            profile=profile,
            requested_collectors=collectors,
            checkpoints=checkpoints,
            target_ids=[item["target_id"] for item in agent_targets],
            minimum_target_count=args.minimum_agent_pods,
            started_at=started_at,
            health_configured=bool(health_url),
            interrupted=interrupted,
            collector_execution_source=collector_execution_source,
            comparison_metadata=build_comparison_metadata(
                config_path=args.config,
                requested_collectors=collectors,
                collector_execution_source=collector_execution_source,
            ),
        )
    else:
        runtime_observation_source = (
            "pod"
            if args.agent_pod or args.discover_agent_pod
            else "local"
            if args.agent_pid is not None or args.state_dir is not None
            else "none"
        )
        summary = build_summary(
            profile_name=args.profile,
            profile=profile,
            requested_collectors=collectors,
            checkpoints=checkpoints,
            started_at=started_at,
            health_configured=bool(health_url),
            process_configured=bool(agent_pod or args.discover_agent_pod or args.agent_pid is not None),
            spool_configured=bool(agent_pod or args.discover_agent_pod or args.state_dir is not None),
            interrupted=interrupted,
            runtime_observation_required=args.require_runtime_observation,
            runtime_observation_source=runtime_observation_source,
            comparison_metadata=build_comparison_metadata(
                config_path=args.config,
                requested_collectors=collectors,
                collector_execution_source="runner_local",
            ),
        )
    atomic_write_json(summary_path, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 130 if interrupted else 1 if summary["status"] == "failed" else 0


if __name__ == "__main__":
    raise SystemExit(main())
