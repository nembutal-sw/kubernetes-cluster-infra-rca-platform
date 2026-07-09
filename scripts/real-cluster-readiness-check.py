#!/usr/bin/env python3
"""Read-only readiness checks for validating the platform against a real cluster."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_COLLECTORS = (
    "node,kubernetes,systemd,runtime,kubelet,kernel,network,conntrack,"
    "disk,inode,memory,process,cni,dns"
)
EVENT_KEYWORDS = (
    "NodeNotReady",
    "DiskPressure",
    "MemoryPressure",
    "PIDPressure",
    "NetworkUnavailable",
    "FailedScheduling",
    "FailedMount",
    "BackOff",
    "OOM",
    "Evicted",
    "CNI",
    "DNS",
    "CoreDNS",
    "Unhealthy",
)
NODE_CONDITIONS = (
    "Ready",
    "DiskPressure",
    "MemoryPressure",
    "PIDPressure",
    "NetworkUnavailable",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run read-only real-cluster checks for Cluster RCA Console. "
            "The script does not create, update, delete, or restart cluster resources."
        )
    )
    parser.add_argument("--context", default="", help="Optional kubectl context.")
    parser.add_argument("--agent-namespace", default="default", help="Namespace used for agent server dry-run.")
    parser.add_argument("--backend-url", default="https://rca.example.com", help="Backend URL used for Helm rendering.")
    parser.add_argument("--cluster-id", default="cluster-test", help="Dry-run cluster id value.")
    parser.add_argument("--agent-token", default="agent-token", help="Dry-run agent token value.")
    parser.add_argument("--output", default="-", help="JSON output path, or '-' for stdout.")
    parser.add_argument("--repo-root", default="", help="Repository root. Defaults to the script parent directory.")
    parser.add_argument("--command-timeout", type=int, default=45, help="Per-command timeout seconds.")
    parser.add_argument("--event-limit", type=int, default=30, help="Maximum warning events to include.")
    parser.add_argument("--skip-helm", action="store_true", help="Skip Helm lint/template checks.")
    parser.add_argument("--skip-server-dry-run", action="store_true", help="Skip kubectl server-side dry-run.")
    parser.add_argument("--agent-local", action="store_true", help="Run node_agent local collection from this host.")
    parser.add_argument(
        "--agent-output",
        default="/tmp/rca-agent-evidence-host.json",
        help="Output path for --agent-local evidence JSON.",
    )
    parser.add_argument("--collectors", default=DEFAULT_COLLECTORS, help="Comma-separated local collector list.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve() if args.repo_root else Path(__file__).resolve().parents[1]
    report: dict[str, Any] = {
        "status": "passed",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "repo_root": str(repo_root),
        "kubectl_context": args.context or None,
        "checks": [],
        "signals": {},
        "warnings": [],
        "failures": [],
        "recommendations": [],
    }

    require_command(report, "kubectl")
    if not args.skip_helm:
        require_command(report, "helm")
    if report["failures"]:
        finalize(report, args.output)
        return 1

    check_kubernetes_access(report, args)
    check_cluster_state(report, args)
    check_events(report, args)

    if args.skip_helm:
        add_check(report, "helm", "skipped", "Helm checks skipped by --skip-helm.")
    else:
        check_helm(report, args, repo_root)

    if args.agent_local:
        check_agent_local_collect(report, args, repo_root)
    else:
        add_check(report, "agent-local-collect", "skipped", "Use --agent-local on a Linux node to run host collection.")

    add_recommendations(report)
    if report["failures"]:
        report["status"] = "failed"
    elif report["warnings"]:
        report["status"] = "warning"

    finalize(report, args.output)
    return 1 if report["failures"] else 0


def require_command(report: dict[str, Any], command: str) -> None:
    path = shutil.which(command)
    if path:
        add_check(report, f"command-{command}", "passed", f"{command} found.", {"path": path})
    else:
        add_check(report, f"command-{command}", "failed", f"{command} is not installed or not in PATH.")
        report["failures"].append(f"{command} command is required")


def check_kubernetes_access(report: dict[str, Any], args: argparse.Namespace) -> None:
    probes = [
        ("current-context", ["config", "current-context"], False),
        ("can-get-nodes", ["auth", "can-i", "get", "nodes"], True),
        ("can-list-pods", ["auth", "can-i", "list", "pods", "--all-namespaces"], True),
        ("can-list-events", ["auth", "can-i", "list", "events", "--all-namespaces"], True),
        ("readyz", ["get", "--raw", "/readyz"], False),
    ]
    results = []
    for name, command_args, expects_yes in probes:
        completed = run_kubectl(args, command_args, check=False)
        value = (completed.stdout or completed.stderr).strip()
        ok = completed.returncode == 0 and (not expects_yes or value.lower() == "yes")
        status = "passed" if ok else "warning"
        if not ok:
            report["warnings"].append(f"kubectl probe failed: {name} ({value or completed.returncode})")
        results.append({"name": name, "ok": ok, "output": value})
    add_check(report, "kubectl-access", "warning" if any(not item["ok"] for item in results) else "passed", "kubectl access probes completed.", {"probes": results})


def check_cluster_state(report: dict[str, Any], args: argparse.Namespace) -> None:
    completed = run_kubectl(args, ["get", "nodes", "-o", "json"], check=False)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        add_check(report, "cluster-nodes", "failed", detail or "kubectl get nodes failed.")
        report["failures"].append("unable to read Kubernetes nodes")
        return

    payload = json.loads(completed.stdout)
    nodes = payload.get("items", [])
    if not nodes:
        add_check(report, "cluster-nodes", "failed", "No Kubernetes nodes returned.")
        report["failures"].append("cluster has no nodes")
        return

    summary = [summarize_node(node) for node in nodes]
    problem_nodes = [
        node for node in summary
        if node["conditions"].get("Ready", {}).get("status") != "True"
        or any(
            node["conditions"].get(condition, {}).get("status") == "True"
            for condition in ("DiskPressure", "MemoryPressure", "PIDPressure", "NetworkUnavailable")
        )
    ]
    report["signals"]["nodes"] = summary
    status = "warning" if problem_nodes else "passed"
    if problem_nodes:
        report["warnings"].append("one or more nodes are not Ready or report pressure conditions")
    add_check(
        report,
        "cluster-nodes",
        status,
        f"Read {len(nodes)} node(s); {len(problem_nodes)} node(s) need attention.",
        {"problem_nodes": problem_nodes},
    )

    pods = run_kubectl(args, ["get", "pods", "-A", "-o", "json"], check=False)
    if pods.returncode != 0:
        report["warnings"].append("unable to read pods across namespaces")
        add_check(report, "cluster-pods", "warning", (pods.stderr or pods.stdout).strip())
        return
    pod_payload = json.loads(pods.stdout)
    report["signals"]["pods"] = summarize_pods(pod_payload)
    unhealthy = report["signals"]["pods"]["unhealthy"]
    add_check(
        report,
        "cluster-pods",
        "warning" if unhealthy else "passed",
        f"Read {report['signals']['pods']['total']} pod(s); {len(unhealthy)} unhealthy pod(s) captured.",
        {"unhealthy": unhealthy[:20]},
    )
    if unhealthy:
        report["warnings"].append("one or more pods are not healthy")


def check_events(report: dict[str, Any], args: argparse.Namespace) -> None:
    completed = run_kubectl(args, ["get", "events", "-A", "--sort-by=.lastTimestamp", "-o", "json"], check=False)
    if completed.returncode != 0:
        add_check(report, "cluster-events", "warning", (completed.stderr or completed.stdout).strip())
        report["warnings"].append("unable to read Kubernetes events")
        return
    payload = json.loads(completed.stdout)
    warnings = []
    for event in payload.get("items", []):
        reason = str(event.get("reason") or "")
        message = str(event.get("message") or "")
        event_type = str(event.get("type") or "")
        combined = f"{reason} {message}"
        if event_type.lower() == "warning" or any(keyword.lower() in combined.lower() for keyword in EVENT_KEYWORDS):
            warnings.append(summarize_event(event))
    warnings = warnings[-max(args.event_limit, 1):]
    report["signals"]["events"] = warnings
    add_check(
        report,
        "cluster-events",
        "warning" if warnings else "passed",
        f"Captured {len(warnings)} recent warning or RCA-relevant event(s).",
        {"events": warnings},
    )


def check_helm(report: dict[str, Any], args: argparse.Namespace, repo_root: Path) -> None:
    platform_chart = repo_root / "charts" / "cluster-infra-rca-platform"
    agent_chart = repo_root / "charts" / "cluster-infra-rca-agent"
    helm_commands = [
        ("platform-helm-lint", ["lint", str(platform_chart)]),
        (
            "agent-helm-lint",
            [
                "lint",
                str(agent_chart),
                "--set",
                f"backendUrl={args.backend_url}",
                "--set",
                "secret.create=true",
                "--set",
                f"secret.clusterId={args.cluster_id}",
                "--set",
                f"secret.agentToken={args.agent_token}",
            ],
        ),
    ]
    for name, command in helm_commands:
        completed = run_command(["helm", *command], cwd=repo_root, timeout=args.command_timeout, check=False)
        status = "passed" if completed.returncode == 0 else "failed"
        add_check(report, name, status, last_lines(completed.stdout + completed.stderr, 30))
        if completed.returncode != 0:
            report["failures"].append(f"{name} failed")

    if args.skip_server_dry_run:
        add_check(report, "agent-server-dry-run", "skipped", "Server dry-run skipped by --skip-server-dry-run.")
        return

    render = run_command(
        [
            "helm",
            "template",
            "rca-agent",
            str(agent_chart),
            "--namespace",
            args.agent_namespace,
            "--set",
            f"namespace.name={args.agent_namespace}",
            "--set",
            f"backendUrl={args.backend_url}",
            "--set",
            "secret.create=true",
            "--set",
            f"secret.clusterId={args.cluster_id}",
            "--set",
            f"secret.agentToken={args.agent_token}",
        ],
        cwd=repo_root,
        timeout=args.command_timeout,
        check=False,
    )
    if render.returncode != 0:
        add_check(report, "agent-helm-template", "failed", last_lines(render.stdout + render.stderr, 30))
        report["failures"].append("agent helm template failed")
        return
    dry_run = run_kubectl(
        args,
        ["apply", "--dry-run=server", "-f", "-"],
        input_text=render.stdout,
        check=False,
    )
    if dry_run.returncode != 0:
        add_check(report, "agent-server-dry-run", "failed", last_lines(dry_run.stdout + dry_run.stderr, 40))
        report["failures"].append("agent server dry-run failed")
        return
    add_check(report, "agent-server-dry-run", "passed", last_lines(dry_run.stdout + dry_run.stderr, 40))


def check_agent_local_collect(report: dict[str, Any], args: argparse.Namespace, repo_root: Path) -> None:
    output = Path(args.agent_output)
    env = os.environ.copy()
    env.update(
        {
            "HOST_ROOT": "/",
            "HOST_PROC": "/proc",
            "HOST_SYS": "/sys",
            "HOST_ETC": "/etc",
            "HOST_VAR_LOG": "/var/log",
            "HOST_RUN": "/run",
            "AGENT_MODE": "node-diagnostics",
            "PYTHONPATH": f"{repo_root}{os.pathsep}{env.get('PYTHONPATH', '')}",
        }
    )
    completed = run_command(
        [
            sys.executable,
            "-m",
            "node_agent.main",
            "--collect-local",
            "--collectors",
            args.collectors,
            "--output",
            str(output),
        ],
        cwd=repo_root,
        timeout=max(args.command_timeout, 90),
        env=env,
        check=False,
    )
    if completed.returncode != 0:
        add_check(report, "agent-local-collect", "failed", last_lines(completed.stdout + completed.stderr, 60))
        report["failures"].append("agent local collection failed")
        return
    try:
        evidence = json.loads(output.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        add_check(report, "agent-local-collect", "failed", f"Unable to read evidence output: {exc}")
        report["failures"].append("agent local evidence output is invalid")
        return

    collectors = evidence.get("collectors", {})
    requested = [item.strip() for item in args.collectors.split(",") if item.strip()]
    missing = sorted(set(requested) - set(collectors))
    limited = {
        name: value
        for name, value in collectors.items()
        if isinstance(value, dict) and str(value.get("status", "")).lower() in {"disabled", "unsupported", "error"}
    }
    summary = summarize_agent_evidence(evidence)
    report["signals"]["agent_local_collect"] = summary
    if missing:
        report["failures"].append("agent local collection missed collector(s): " + ", ".join(missing))
    if limited:
        report["warnings"].append("agent local collection returned limited collector(s): " + ", ".join(sorted(limited)))
    add_check(
        report,
        "agent-local-collect",
        "failed" if missing else "warning" if limited else "passed",
        f"Collected {len(collectors)} collector payload(s) into {output}.",
        {"missing": missing, "limited": sorted(limited), "summary": summary},
    )


def run_kubectl(
    args: argparse.Namespace,
    command_args: list[str],
    *,
    input_text: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    command = ["kubectl"]
    if args.context:
        command.extend(["--context", args.context])
    command.extend(command_args)
    return run_command(command, input_text=input_text, timeout=args.command_timeout, check=check)


def run_command(
    command: list[str],
    *,
    cwd: Path | None = None,
    input_text: str | None = None,
    timeout: int = 45,
    env: dict[str, str] | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        input=input_text,
        text=True,
        capture_output=True,
        timeout=timeout,
        env=env,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RuntimeError(last_lines(completed.stdout + completed.stderr, 80))
    return completed


def summarize_node(node: dict[str, Any]) -> dict[str, Any]:
    metadata = node.get("metadata", {})
    status = node.get("status", {})
    conditions = {
        condition: condition_summary(status.get("conditions", []), condition)
        for condition in NODE_CONDITIONS
    }
    return {
        "name": metadata.get("name"),
        "conditions": conditions,
        "taints": metadata.get("taints", []),
        "kubelet_version": status.get("nodeInfo", {}).get("kubeletVersion"),
        "container_runtime": status.get("nodeInfo", {}).get("containerRuntimeVersion"),
        "kernel_version": status.get("nodeInfo", {}).get("kernelVersion"),
        "os_image": status.get("nodeInfo", {}).get("osImage"),
    }


def condition_summary(conditions: list[dict[str, Any]], condition_type: str) -> dict[str, Any]:
    condition = next((item for item in conditions if item.get("type") == condition_type), {})
    return {
        "status": condition.get("status"),
        "reason": condition.get("reason"),
        "message": condition.get("message"),
        "last_transition_time": condition.get("lastTransitionTime"),
    }


def summarize_pods(payload: dict[str, Any]) -> dict[str, Any]:
    unhealthy = []
    for pod in payload.get("items", []):
        status = pod.get("status", {})
        phase = status.get("phase")
        container_statuses = status.get("containerStatuses", [])
        ready = all(item.get("ready") for item in container_statuses) if container_statuses else phase == "Succeeded"
        restarts = sum(int(item.get("restartCount") or 0) for item in container_statuses)
        waiting_reasons = [
            item.get("state", {}).get("waiting", {}).get("reason")
            for item in container_statuses
            if item.get("state", {}).get("waiting", {}).get("reason")
        ]
        if phase == "Succeeded":
            continue
        if phase != "Running" or not ready or waiting_reasons:
            unhealthy.append(
                {
                    "namespace": pod.get("metadata", {}).get("namespace"),
                    "name": pod.get("metadata", {}).get("name"),
                    "node": pod.get("spec", {}).get("nodeName"),
                    "phase": phase,
                    "ready": ready,
                    "restart_count": restarts,
                    "waiting_reasons": waiting_reasons,
                }
            )
    return {
        "total": len(payload.get("items", [])),
        "unhealthy": unhealthy,
    }


def summarize_event(event: dict[str, Any]) -> dict[str, Any]:
    involved = event.get("involvedObject", {})
    return {
        "namespace": event.get("metadata", {}).get("namespace"),
        "type": event.get("type"),
        "reason": event.get("reason"),
        "object": f"{involved.get('kind', '')}/{involved.get('name', '')}".strip("/"),
        "message": str(event.get("message") or "")[:500],
        "count": event.get("count"),
        "last_timestamp": event.get("lastTimestamp") or event.get("eventTime"),
    }


def summarize_agent_evidence(evidence: dict[str, Any]) -> dict[str, Any]:
    collectors = evidence.get("collectors", {})
    return {
        "node_name": evidence.get("node_name"),
        "agent_version": evidence.get("agent_version"),
        "collector_names": sorted(collectors),
        "disk_max_usage_percent": max_numeric(collectors.get("disk"), ("usage_percent", "used_percent", "percent")),
        "inode_max_usage_percent": max_numeric(collectors.get("inode"), ("usage_percent", "used_percent", "percent")),
        "conntrack_max_usage_percent": max_numeric(collectors.get("conntrack"), ("usage_percent", "used_percent", "percent")),
        "runtime": compact_status(collectors.get("runtime")),
        "kubelet": compact_status(collectors.get("kubelet")),
        "kernel": compact_status(collectors.get("kernel")),
        "dns": compact_status(collectors.get("dns")),
        "cni": compact_status(collectors.get("cni")),
        "systemd": compact_status(collectors.get("systemd")),
    }


def max_numeric(value: Any, keys: tuple[str, ...]) -> float | None:
    values: list[float] = []

    def walk(item: Any) -> None:
        if isinstance(item, dict):
            for key, child in item.items():
                if key in keys and isinstance(child, (int, float)):
                    values.append(float(child))
                walk(child)
        elif isinstance(item, list):
            for child in item:
                walk(child)

    walk(value)
    return max(values) if values else None


def compact_status(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    keys = (
        "status",
        "healthy",
        "ready",
        "api_error",
        "error",
        "failure_total",
        "config_count",
        "containerd_socket_healthy",
        "docker_socket_healthy",
        "crio_socket_healthy",
    )
    return {key: value.get(key) for key in keys if key in value}


def add_check(
    report: dict[str, Any],
    name: str,
    status: str,
    detail: str,
    data: dict[str, Any] | None = None,
) -> None:
    item = {"name": name, "status": status, "detail": detail}
    if data is not None:
        item["data"] = data
    report["checks"].append(item)


def add_recommendations(report: dict[str, Any]) -> None:
    if report["failures"]:
        report["recommendations"].append("Fix failed readiness checks before DaemonSet rollout.")
    if report["warnings"]:
        report["recommendations"].append("Review warning signals and decide whether they are expected for this test cluster.")
    if not report["failures"]:
        report["recommendations"].append("Use Helm canary rollout before full DaemonSet deployment.")
        report["recommendations"].append("Keep APPROVED_ACTIONS_ENABLED=false unless a separate execution guard is approved.")


def finalize(report: dict[str, Any], output: str) -> None:
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if output == "-":
        print(encoded)
        return
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(encoded + "\n", encoding="utf-8")


def last_lines(text: str, limit: int) -> str:
    lines = [line.rstrip() for line in text.splitlines() if line.strip()]
    return "\n".join(lines[-limit:])


if __name__ == "__main__":
    raise SystemExit(main())
