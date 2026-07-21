#!/usr/bin/env python3
"""Rebuild an Agent soak summary from redacted checkpoints without collecting again."""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SOAK_SCRIPT = ROOT / "scripts" / "agent-soak-validation.py"
FORBIDDEN_CHECKPOINT_KEYS = {"agent_pod", "namespace", "pod_name", "node_name"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Revalidate a redacted Agent soak artifact offline.")
    parser.add_argument("--summary", type=Path, required=True, help="Original Agent soak summary JSON.")
    parser.add_argument("--checkpoints", type=Path, required=True, help="Redacted checkpoint JSONL.")
    parser.add_argument("--config", type=Path, default=ROOT / "config" / "agent-soak-thresholds.json")
    parser.add_argument("--output", type=Path, required=True, help="Revalidated summary JSON.")
    return parser.parse_args()


def soak_module():
    spec = importlib.util.spec_from_file_location("agent_soak_validation", SOAK_SCRIPT)
    if spec is None or spec.loader is None:
        raise ValueError("unable to load Agent soak validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_object(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"unable to load {label}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def contains_forbidden_key(value: Any) -> bool:
    if isinstance(value, dict):
        return any(key in FORBIDDEN_CHECKPOINT_KEYS or contains_forbidden_key(item) for key, item in value.items())
    if isinstance(value, list):
        return any(contains_forbidden_key(item) for item in value)
    return False


def load_checkpoints(path: Path, schema_version: str) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ValueError(f"unable to load Agent soak checkpoints: {exc}") from exc
    checkpoints = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            checkpoint = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"checkpoint line {line_number} is invalid JSON") from exc
        if not isinstance(checkpoint, dict) or checkpoint.get("schema_version") != schema_version:
            raise ValueError(f"checkpoint line {line_number} has an invalid schema")
        if contains_forbidden_key(checkpoint):
            raise ValueError(f"checkpoint line {line_number} contains a forbidden infrastructure identifier")
        checkpoints.append(checkpoint)
    if not checkpoints:
        raise ValueError("Agent soak checkpoints are empty")
    iterations = [item.get("iteration") for item in checkpoints]
    if iterations != list(range(1, len(checkpoints) + 1)):
        raise ValueError("checkpoint iterations must be contiguous and start at one")
    return checkpoints


def revalidate(
    original: dict[str, Any],
    checkpoints: list[dict[str, Any]],
    config: Path,
) -> dict[str, Any]:
    module = soak_module()
    if original.get("schema_version") != module.SCHEMA_VERSION:
        raise ValueError(f"Agent soak summary must use {module.SCHEMA_VERSION}")
    if original.get("read_only") is not True:
        raise ValueError("Agent soak summary must be read-only")
    profile_name = original.get("profile")
    if not isinstance(profile_name, str) or not profile_name:
        raise ValueError("Agent soak summary profile is missing")
    collectors, profile = module.load_configuration(config, profile_name)
    requested_collectors = original.get("requested_collectors")
    if not isinstance(requested_collectors, list) or not requested_collectors:
        requested_collectors = collectors
    observability = original.get("observability") if isinstance(original.get("observability"), dict) else {}
    started_at = original.get("started_at")
    if not isinstance(started_at, str) or not started_at:
        raise ValueError("Agent soak summary start timestamp is missing")
    interrupted = len(checkpoints) < int(profile["iterations"])
    if observability.get("runtime_observation_source") == "fleet":
        target_ids = sorted(
            {
                target["target_id"]
                for checkpoint in checkpoints
                for target in checkpoint.get("targets", [])
                if isinstance(target, dict) and isinstance(target.get("target_id"), str)
            }
        )
        if not target_ids:
            raise ValueError("fleet checkpoints do not contain target IDs")
        return module.build_fleet_summary(
            profile_name=profile_name,
            profile=profile,
            requested_collectors=requested_collectors,
            checkpoints=checkpoints,
            target_ids=target_ids,
            minimum_target_count=int(observability.get("minimum_fleet_target_count") or len(target_ids)),
            started_at=started_at,
            health_configured=bool(observability.get("health_probe_configured")),
            interrupted=interrupted,
        )
    return module.build_summary(
        profile_name=profile_name,
        profile=profile,
        requested_collectors=requested_collectors,
        checkpoints=checkpoints,
        started_at=started_at,
        health_configured=bool(observability.get("health_probe_configured")),
        process_configured=bool(observability.get("agent_process_configured")),
        spool_configured=bool(observability.get("state_dir_configured")),
        interrupted=interrupted,
        runtime_observation_required=bool(observability.get("runtime_observation_required")),
        runtime_observation_source=str(observability.get("runtime_observation_source") or "none"),
    )


def main() -> int:
    args = parse_args()
    try:
        original = load_object(args.summary, "Agent soak summary")
        module = soak_module()
        checkpoints = load_checkpoints(args.checkpoints, module.SCHEMA_VERSION)
        result = revalidate(original, checkpoints, args.config)
        module.atomic_write_json(args.output, result)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("status") == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
