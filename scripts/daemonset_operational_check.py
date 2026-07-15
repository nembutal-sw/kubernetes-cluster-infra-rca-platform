#!/usr/bin/env python3
"""Read-only operational checks for the node-agent DaemonSet."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_NAMESPACE = "rca-system"
DEFAULT_LABEL = "app.kubernetes.io/name=cluster-infra-rca-agent"
REQUIRED_ENV_KEYS = {
    "BACKEND_URL",
    "AGENT_MODE",
    "CLUSTER_ID",
    "AGENT_TOKEN",
    "NODE_NAME",
    "POLL_INTERVAL_SECONDS",
    "HTTP_TIMEOUT_SECONDS",
    "COMMAND_TIMEOUT_SECONDS",
    "KUBERNETES_API_TIMEOUT_SECONDS",
    "KUBERNETES_API_MAX_ATTEMPTS",
    "KUBERNETES_API_MAX_RESPONSE_BYTES",
    "SYSTEMD_COLLECTOR_MODE",
}
NODE_DIAGNOSTIC_HOSTPATHS = {
    "host-root": "/",
    "host-var-log": "/var/log",
    "host-run": "/run",
    "host-etc": "/etc",
    "host-proc": "/proc",
    "host-sys": "/sys",
}
EBPF_HOSTPATHS = {
    "host-debug": "/sys/kernel/debug",
    "host-bpf": "/sys/fs/bpf",
    "host-modules": "/lib/modules",
}
ERROR_PATTERNS = (
    "traceback",
    "permission denied",
    "401",
    "403",
    "authentication failed",
    "node_token is missing",
    "evidence submit failed",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate an RCA node-agent DaemonSet without changing the cluster.")
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE)
    parser.add_argument("--daemonset", default="")
    parser.add_argument("--selector", default=DEFAULT_LABEL)
    parser.add_argument("--context", default="")
    parser.add_argument("--output", default="")
    parser.add_argument("--tail", type=int, default=120)
    parser.add_argument("--skip-logs", action="store_true")
    return parser.parse_args()


def kubectl(args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    command = ["kubectl", *args]
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    if check and completed.returncode != 0:
        stderr = completed.stderr.strip()
        stdout = completed.stdout.strip()
        raise RuntimeError(f"{' '.join(command)} failed: {stderr or stdout}")
    return completed


def kubectl_json(args: list[str]) -> dict[str, Any]:
    completed = kubectl([*args, "-o", "json"])
    return json.loads(completed.stdout)


def context_args(context: str) -> list[str]:
    return ["--context", context] if context else []


def find_daemonset(namespace: str, selector: str, context: str, explicit: str) -> dict[str, Any]:
    base = [*context_args(context), "-n", namespace]
    if explicit:
        return kubectl_json([*base, "get", "daemonset", explicit])
    list_result = kubectl_json([*base, "get", "daemonset", "-l", selector])
    items = list_result.get("items", [])
    if len(items) != 1:
        names = [item.get("metadata", {}).get("name") for item in items]
        raise RuntimeError(
            f"expected exactly one DaemonSet for selector '{selector}' in namespace '{namespace}', found {names}"
        )
    return items[0]


def selector_from_daemonset(daemonset: dict[str, Any]) -> str:
    labels = daemonset.get("spec", {}).get("selector", {}).get("matchLabels", {})
    if not labels:
        return DEFAULT_LABEL
    return ",".join(f"{key}={value}" for key, value in labels.items())


def configmap_data(namespace: str, context: str, name: str) -> dict[str, str]:
    if not name:
        return {}
    result = kubectl_json([*context_args(context), "-n", namespace, "get", "configmap", name])
    return result.get("data", {})


def env_refs(container: dict[str, Any]) -> tuple[set[str], dict[str, str], str]:
    names: set[str] = set()
    direct_values: dict[str, str] = {}
    configmap_name = ""
    for item in container.get("env", []):
        name = item.get("name")
        if not name:
            continue
        names.add(name)
        if "value" in item:
            direct_values[name] = str(item.get("value"))
        ref = item.get("valueFrom", {}).get("configMapKeyRef", {})
        if ref.get("name") and not configmap_name:
            configmap_name = ref["name"]
    return names, direct_values, configmap_name


def hostpath_map(daemonset: dict[str, Any]) -> dict[str, str]:
    volumes = daemonset.get("spec", {}).get("template", {}).get("spec", {}).get("volumes", [])
    result: dict[str, str] = {}
    for volume in volumes:
        host_path = volume.get("hostPath")
        if host_path:
            result[volume.get("name", "")] = host_path.get("path", "")
    return result


def mount_readonly_map(container: dict[str, Any]) -> dict[str, bool]:
    mounts = container.get("volumeMounts", [])
    return {mount.get("name", ""): bool(mount.get("readOnly")) for mount in mounts}


def service_account_subject(namespace: str, daemonset: dict[str, Any]) -> str:
    template = daemonset.get("spec", {}).get("template", {}).get("spec", {})
    name = template.get("serviceAccountName") or "default"
    return f"system:serviceaccount:{namespace}:{name}"


def can_i(namespace: str, context: str, subject: str, args: list[str]) -> dict[str, Any]:
    completed = kubectl([*context_args(context), "auth", "can-i", *args, f"--as={subject}"], check=False)
    allowed = completed.returncode == 0 and completed.stdout.strip().lower() == "yes"
    return {
        "args": args,
        "allowed": allowed,
        "stdout": completed.stdout.strip(),
        "stderr": completed.stderr.strip(),
    }


def raw_access(context: str, subject: str, path: str) -> dict[str, Any]:
    completed = kubectl([*context_args(context), f"--as={subject}", "get", "--raw", path], check=False)
    return {
        "args": ["get", "--raw", path],
        "allowed": completed.returncode == 0,
        "stdout": completed.stdout.strip()[:500],
        "stderr": completed.stderr.strip(),
    }


def collect_logs(namespace: str, context: str, selector: str, tail: int) -> dict[str, Any]:
    completed = kubectl(
        [
            *context_args(context),
            "-n",
            namespace,
            "logs",
            "-l",
            selector,
            "--all-containers=true",
            f"--tail={tail}",
        ],
        check=False,
    )
    text = (completed.stdout or "") + "\n" + (completed.stderr or "")
    lowered = text.lower()
    matches = sorted(pattern for pattern in ERROR_PATTERNS if pattern in lowered)
    return {
        "available": completed.returncode == 0,
        "matched_error_patterns": matches,
        "sample": text[-4000:],
    }


def main() -> int:
    args = parse_args()
    failures: list[str] = []
    warnings: list[str] = []
    started_at = datetime.now(timezone.utc)

    daemonset = find_daemonset(args.namespace, args.selector, args.context, args.daemonset)
    ds_name = daemonset.get("metadata", {}).get("name", "")
    selector = selector_from_daemonset(daemonset)
    template_spec = daemonset.get("spec", {}).get("template", {}).get("spec", {})
    containers = template_spec.get("containers", [])
    if not containers:
        raise RuntimeError(f"DaemonSet {ds_name} has no containers")
    container = containers[0]
    status = daemonset.get("status", {})
    desired = int(status.get("desiredNumberScheduled") or 0)
    ready = int(status.get("numberReady") or 0)
    available = int(status.get("numberAvailable") or 0)
    env_names, direct_env, cm_name = env_refs(container)
    cm_data = configmap_data(args.namespace, args.context, cm_name)
    mode = (cm_data.get("AGENT_MODE") or direct_env.get("AGENT_MODE") or "").strip().lower()
    systemd_mode = (cm_data.get("SYSTEMD_COLLECTOR_MODE") or direct_env.get("SYSTEMD_COLLECTOR_MODE") or "").strip().lower()
    ebpf_enabled = (direct_env.get("EBPF_ENABLED") or cm_data.get("EBPF_ENABLED") or "false").strip().lower()

    missing_env = sorted(REQUIRED_ENV_KEYS - env_names)
    if missing_env:
        failures.append("missing required env keys: " + ", ".join(missing_env))
    if desired <= 0:
        failures.append("DaemonSet desiredNumberScheduled is 0")
    if ready < desired or available < desired:
        failures.append(f"DaemonSet is not fully ready: desired={desired}, ready={ready}, available={available}")
    if mode not in {"safe", "node-diagnostics", "ebpf"}:
        failures.append(f"AGENT_MODE must be safe, node-diagnostics, or ebpf; actual={mode or '<empty>'}")
    if systemd_mode != "file":
        warnings.append(f"SYSTEMD_COLLECTOR_MODE is {systemd_mode or '<empty>'}; file mode is recommended for DaemonSet")

    if direct_env.get("APPROVED_ACTIONS_ENABLED", "false").lower() == "true":
        failures.append("APPROVED_ACTIONS_ENABLED=true is not allowed for operational validation")

    hostpaths = hostpath_map(daemonset)
    readonly = mount_readonly_map(container)
    if mode == "safe":
        unexpected = sorted(name for name in NODE_DIAGNOSTIC_HOSTPATHS if name in hostpaths)
        if unexpected:
            failures.append("safe mode should not mount host diagnostic paths: " + ", ".join(unexpected))
    else:
        for name, path in NODE_DIAGNOSTIC_HOSTPATHS.items():
            actual = hostpaths.get(name)
            if actual != path:
                failures.append(f"hostPath {name} must be {path}, actual={actual or '<missing>'}")
            if readonly.get(name) is not True:
                failures.append(f"hostPath mount {name} must be readOnly")
        capabilities = container.get("securityContext", {}).get("capabilities", {}).get("add", [])
        if "SYSLOG" not in capabilities:
            warnings.append("SYSLOG capability is absent; dmesg may be unavailable when kernel.dmesg_restrict=1")
    if mode == "ebpf":
        for name, path in EBPF_HOSTPATHS.items():
            actual = hostpaths.get(name)
            if actual != path:
                failures.append(f"eBPF hostPath {name} must be {path}, actual={actual or '<missing>'}")
        capabilities = container.get("securityContext", {}).get("capabilities", {}).get("add", [])
        for capability in ["BPF", "PERFMON", "NET_ADMIN", "SYS_RESOURCE"]:
            if capability not in capabilities:
                failures.append(f"eBPF mode is missing capability {capability}")
    elif ebpf_enabled == "true":
        failures.append("EBPF_ENABLED=true while AGENT_MODE is not ebpf")

    subject = service_account_subject(args.namespace, daemonset)
    rbac_checks = [
        can_i(args.namespace, args.context, subject, ["get", "nodes"]),
        can_i(args.namespace, args.context, subject, ["list", "pods", "--all-namespaces"]),
        can_i(args.namespace, args.context, subject, ["list", "events", "--all-namespaces"]),
        raw_access(args.context, subject, "/readyz"),
    ]
    for check in rbac_checks:
        if not check["allowed"]:
            failures.append("RBAC/API denied: kubectl " + " ".join(check["args"]))

    pod_list = kubectl_json([*context_args(args.context), "-n", args.namespace, "get", "pods", "-l", selector])
    pods = pod_list.get("items", [])
    pod_summary = []
    for pod in pods:
        conditions = pod.get("status", {}).get("conditions", [])
        ready_condition = next((item for item in conditions if item.get("type") == "Ready"), {})
        pod_summary.append({
            "name": pod.get("metadata", {}).get("name"),
            "node": pod.get("spec", {}).get("nodeName"),
            "phase": pod.get("status", {}).get("phase"),
            "ready": ready_condition.get("status") == "True",
            "restart_count": sum(
                int(status.get("restartCount") or 0)
                for status in pod.get("status", {}).get("containerStatuses", [])
            ),
        })
    if len(pods) != desired:
        warnings.append(f"pod count {len(pods)} does not match desiredNumberScheduled {desired}")
    if any(not item["ready"] for item in pod_summary):
        failures.append("one or more agent pods are not Ready")
    if any(int(item["restart_count"]) > 0 for item in pod_summary):
        warnings.append("one or more agent pods have restarted")

    logs = None
    if not args.skip_logs:
        logs = collect_logs(args.namespace, args.context, selector, args.tail)
        if not logs["available"]:
            warnings.append("could not read DaemonSet logs")
        elif logs["matched_error_patterns"]:
            warnings.append("agent logs contain error-like patterns: " + ", ".join(logs["matched_error_patterns"]))

    summary = {
        "schema_version": "1.0",
        "checked_at": started_at.isoformat(),
        "namespace": args.namespace,
        "daemonset": ds_name,
        "selector": selector,
        "service_account": subject,
        "mode": mode,
        "systemd_collector_mode": systemd_mode,
        "ebpf_enabled": ebpf_enabled,
        "status": {
            "desired": desired,
            "ready": ready,
            "available": available,
        },
        "image": container.get("image"),
        "configmap": cm_name,
        "hostpaths": hostpaths,
        "pods": pod_summary,
        "rbac_checks": rbac_checks,
        "logs": logs,
        "warnings": warnings,
        "failures": failures,
        "passed": not failures,
    }
    text = json.dumps(summary, indent=2, ensure_ascii=False)
    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")
        print(f"DaemonSet validation summary written to {path}")
    else:
        print(text)
    return 0 if not failures else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        raise SystemExit(130)
    except Exception as exc:
        print(f"DaemonSet validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
