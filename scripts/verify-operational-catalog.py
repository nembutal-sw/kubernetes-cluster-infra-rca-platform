#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CATALOG_PATH = ROOT / "web-console/src/main/resources/catalog/operational-catalog.json"
KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
SUPPORTED_SCHEMA = "rca-catalog/v1"
VALID_POLICIES = {
    "AUTO_SAFE",
    "MANUAL_INVESTIGATION",
    "APPROVAL_REQUIRED",
    "GITOPS_PR_ONLY",
    "NEVER_AUTO_EXECUTE",
}
REQUIRED_COLLECTORS = {
    "node",
    "kubernetes",
    "systemd",
    "runtime",
    "kubelet",
    "kernel",
    "network",
    "conntrack",
    "disk",
    "inode",
    "memory",
    "process",
    "cni",
    "dns",
}
REQUIRED_ALERT_SELECTIONS = {
    "NodeNotReady",
    "DiskPressure",
    "MemoryPressure",
    "PIDPressure",
    "NetworkUnavailable",
    "CoreDNSUnhealthy",
    "EtcdLatencyHigh",
    "APIServerLatencyHigh",
}
REQUIRED_ACTIONS = {
    "collect_more_evidence",
    "collect_linux_low_level_evidence",
    "manual_investigation",
}
REQUIRED_RULES = {
    "disk-pressure",
    "inode-pressure",
    "memory-pressure",
    "pid-pressure",
    "conntrack-pressure",
    "runtime-failure",
    "kubelet-failure",
    "kernel-log",
    "systemd-failure",
    "node-readiness",
    "node-pressure-conditions",
    "cni-failure",
    "dns-latency",
    "dns-configuration",
    "coredns-health",
    "api-server-latency",
    "etcd-latency",
    "ebpf-event",
}


def main() -> int:
    errors: list[str] = []
    if not CATALOG_PATH.exists():
        return fail([f"catalog file is missing: {CATALOG_PATH}"])

    try:
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return fail([f"catalog JSON is invalid: {exc}"])

    collectors = object_value(catalog.get("collectors"))
    selection = object_value(catalog.get("collector_selection"))
    actions = object_value(catalog.get("actions"))
    rules = object_value(catalog.get("rules"))

    require(catalog.get("schema_version") == SUPPORTED_SCHEMA, "schema_version must be rca-catalog/v1", errors)
    require(bool(collectors), "collectors must not be empty", errors)
    require(bool(selection), "collector_selection must not be empty", errors)
    require(bool(actions), "actions must not be empty", errors)
    require(bool(rules), "rules must not be empty", errors)

    check_required("collector", REQUIRED_COLLECTORS, collectors, errors)
    check_required("alert selection", REQUIRED_ALERT_SELECTIONS, object_value(selection.get("alerts")), errors)
    check_required("action", REQUIRED_ACTIONS, actions, errors)
    check_required("rule", REQUIRED_RULES, rules, errors)

    for kind, values in (("collector", collectors), ("action", actions), ("rule", rules)):
        for key in values:
            require(bool(KEY_PATTERN.match(key)), f"{kind} key is invalid: {key}", errors)

    known_collectors = set(collectors)
    check_collector_refs("collector_selection.default_collectors", selection.get("default_collectors"), known_collectors, errors)
    for alert_name, alert_collectors in object_value(selection.get("alerts")).items():
        check_collector_refs(f"collector_selection.alerts.{alert_name}", alert_collectors, known_collectors, errors)

    for key, action in actions.items():
        check_action(key, object_value(action), errors)
    for key, rule in rules.items():
        check_rule(key, object_value(rule), errors)

    return fail(errors) if errors else pass_ok(collectors, actions, rules)


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def check_required(kind: str, required: set[str], values: dict[str, Any], errors: list[str]) -> None:
    missing = sorted(required - set(values))
    for key in missing:
        errors.append(f"required {kind} is missing: {key}")


def check_collector_refs(location: str, values: Any, known_collectors: set[str], errors: list[str]) -> None:
    collectors = list_value(values)
    if not collectors:
        errors.append(f"{location} must not be empty")
        return
    for collector in collectors:
        if collector not in known_collectors:
            errors.append(f"{location} references unknown collector: {collector}")


def check_action(key: str, action: dict[str, Any], errors: list[str]) -> None:
    policy = action.get("policy")
    automation_mode = str(action.get("automation_mode") or "").strip()
    require(policy in VALID_POLICIES, f"actions.{key}.policy is invalid or missing", errors)
    require(bool(automation_mode), f"actions.{key}.automation_mode is required", errors)
    plan = object_value(action.get("plan"))
    if plan:
        require(
            plan.get("executable") is not True,
            f"actions.{key}.plan.executable must be false; direct agent mutation is disabled",
            errors,
        )
        timeout = plan.get("timeout_seconds")
        if timeout is not None:
            require(isinstance(timeout, int) and timeout >= 0, f"actions.{key}.plan.timeout_seconds must be >= 0", errors)
        preview = plan.get("command_preview")
        if preview is not None:
            require(isinstance(preview, list), f"actions.{key}.plan.command_preview must be a list", errors)
    triggers = object_value(action.get("triggers"))
    for trigger_key in ("components_any", "signal_names_any", "alert_names_any"):
        value = triggers.get(trigger_key)
        if value is not None:
            require(isinstance(value, list), f"actions.{key}.triggers.{trigger_key} must be a list", errors)


def check_rule(key: str, rule: dict[str, Any], errors: list[str]) -> None:
    require(bool(str(rule.get("detector") or "").strip()), f"rules.{key}.detector is required", errors)
    require(bool(str(rule.get("component") or "").strip()), f"rules.{key}.component is required", errors)
    signals = rule.get("signals")
    require(isinstance(signals, list) and bool(signals), f"rules.{key}.signals must not be empty", errors)


def pass_ok(collectors: dict[str, Any], actions: dict[str, Any], rules: dict[str, Any]) -> int:
    print(json.dumps(
        {
            "status": "passed",
            "catalog": str(CATALOG_PATH.relative_to(ROOT)).replace("\\", "/"),
            "collectors": len(collectors),
            "actions": len(actions),
            "rules": len(rules),
        },
        indent=2,
    ))
    return 0


def fail(errors: list[str]) -> int:
    print("operational catalog verification failed:", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
