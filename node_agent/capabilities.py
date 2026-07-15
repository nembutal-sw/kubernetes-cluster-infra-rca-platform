from __future__ import annotations

import os
import shutil
import stat
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from node_agent.collectors import AgentPaths, CommandRunner, allowed_collectors


STATUS_ORDER = {
    "disabled": 0,
    "available": 1,
    "limited": 2,
    "unavailable": 3,
}


def collect_capabilities(
    paths: AgentPaths,
    runner: CommandRunner,
    mode: str,
    *,
    ebpf_enabled: bool = False,
) -> dict[str, Any]:
    checks = [
        _path_check(
            "host_root",
            "Host root filesystem",
            paths.root,
            mode != "safe",
            ["disk", "inode"],
            sample_children=["etc", "var", "run"],
            next_step="Mount host / read-only when node-diagnostics or ebpf mode is used.",
        ),
        _path_check(
            "host_proc",
            "Host /proc",
            paths.proc,
            mode != "safe",
            ["node", "memory", "process", "network", "conntrack"],
            sample_children=["stat", "meminfo", "net"],
            next_step="Mount /proc as /host/proc read-only for node-level diagnostics.",
        ),
        _path_check(
            "host_sys",
            "Host /sys",
            paths.sys,
            mode != "safe",
            ["network", "cni"],
            sample_children=["class"],
            next_step="Mount /sys as /host/sys read-only for NIC and interface state.",
        ),
        _path_check(
            "host_etc",
            "Host /etc",
            paths.etc if mode != "safe" else paths.etc_root(),
            True,
            ["node", "cni", "dns"],
            sample_children=["os-release"],
            next_step="Mount /etc as /host/etc read-only or ensure in-container resolver files are readable.",
        ),
        _path_check(
            "host_var_log",
            "Host /var/log",
            paths.var_log,
            mode != "safe",
            ["kernel", "systemd", "kubelet"],
            sample_children=[],
            next_step="Mount /var/log as /host/var/log read-only for file-based logs.",
        ),
        _path_check(
            "host_run",
            "Host /run",
            paths.run,
            mode != "safe",
            ["runtime"],
            sample_children=[],
            next_step="Mount /run as /host/run read-only to discover CRI runtime sockets.",
        ),
        _state_dir_check(),
        _kubernetes_api_check(),
        _systemd_access_check(paths, runner),
        _runtime_socket_check(paths),
        _conntrack_check(paths),
        _cni_config_check(paths),
        _ebpf_check(mode, ebpf_enabled),
    ]
    checks_by_key = {str(check["key"]): check for check in checks}
    collector_statuses = _collector_statuses(mode, checks_by_key)
    summary = _summary(collector_statuses.values())
    overall_status = _overall_status(collector_statuses.values())
    return {
        "schema_version": "1.0",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "mode": mode,
        "overall_status": overall_status,
        "summary": summary,
        "checks": checks,
        "collectors": collector_statuses,
    }


def agent_status_for(capabilities: dict[str, Any]) -> str:
    return "degraded" if capabilities.get("overall_status") == "degraded" else "healthy"


def _path_check(
    key: str,
    label: str,
    path: Path,
    required: bool,
    collectors: list[str],
    *,
    sample_children: list[str],
    next_step: str,
) -> dict[str, Any]:
    exists, exists_error = _safe_exists(path)
    readable = _is_readable(path) if exists else False
    child_statuses = [_safe_exists(path / child)[0] for child in sample_children]
    sample_ok = all(child_statuses) if sample_children else exists
    if exists and readable and sample_ok:
        status = "available"
        message = "Required path is present and readable."
    elif exists and readable:
        status = "limited"
        message = "Path is readable, but one or more expected files or directories are missing."
    else:
        status = "unavailable" if required else "disabled"
        message = exists_error or "Path is missing or not readable."
    return _check(
        key,
        label,
        status,
        required,
        collectors,
        message=message,
        path=str(path),
        next_step=next_step,
    )


def _state_dir_check() -> dict[str, Any]:
    path = Path(os.getenv("AGENT_STATE_DIR", "/tmp/cluster-infra-rca-agent"))
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe = path / ".capability-check"
        probe.write_text(str(time.time()), encoding="utf-8")
        probe.unlink(missing_ok=True)
        return _check(
            "state_dir",
            "Agent state directory",
            "available",
            True,
            ["node"],
            message="State directory is writable.",
            path=str(path),
            next_step="Keep AGENT_STATE_DIR writable so node token and spool files survive restarts.",
        )
    except OSError as exc:
        return _check(
            "state_dir",
            "Agent state directory",
            "unavailable",
            True,
            ["node"],
            message=f"State directory is not writable: {exc}",
            path=str(path),
            next_step="Mount a writable emptyDir or hostPath at AGENT_STATE_DIR.",
        )


def _kubernetes_api_check() -> dict[str, Any]:
    host = os.getenv("KUBERNETES_SERVICE_HOST")
    port = os.getenv("KUBERNETES_SERVICE_PORT")
    token_path = Path(os.getenv("KUBERNETES_SERVICEACCOUNT_TOKEN", "/var/run/secrets/kubernetes.io/serviceaccount/token"))
    ca_path = Path(os.getenv("KUBERNETES_SERVICEACCOUNT_CA", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"))
    token_readable = _is_readable(token_path)
    ca_readable = _is_readable(ca_path)
    if host and port and token_readable:
        status = "available" if ca_readable else "limited"
        message = "Kubernetes service account token is readable."
        if not ca_readable:
            message = "Kubernetes token is readable, but CA bundle is missing or unreadable."
    else:
        status = "unavailable"
        message = "Kubernetes in-cluster API environment or service account token is missing."
    return _check(
        "kubernetes_api",
        "Kubernetes API access",
        status,
        True,
        ["kubernetes"],
        message=message,
        path=str(token_path),
        next_step="Run the Agent as a Kubernetes ServiceAccount with read-only node, pod, event, service, and readyz RBAC.",
        details={
            "service_host_configured": bool(host),
            "service_port_configured": bool(port),
            "token_readable": token_readable,
            "ca_readable": ca_readable,
        },
    )


def _systemd_access_check(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    mode = os.getenv("SYSTEMD_COLLECTOR_MODE", "file").strip().lower()
    if mode == "file":
        log_root = paths.var_log_root()
        candidates = [log_root / "syslog", log_root / "messages", log_root / "kern.log"]
        readable_logs = [str(path) for path in candidates if _is_readable(path)]
        status = "available" if readable_logs else "limited"
        message = "File-based systemd/kubelet log candidates are readable." if readable_logs else (
            "File-based mode is enabled, but common host log files were not found."
        )
        return _check(
            "systemd_access",
            "systemd and kubelet log access",
            status,
            True,
            ["systemd", "kubelet", "kernel"],
            message=message,
            path=str(log_root),
            next_step="Keep SYSTEMD_COLLECTOR_MODE=file and mount /var/log read-only; add host log path overrides if needed.",
            details={"collection_mode": mode, "readable_log_files": readable_logs},
        )

    systemctl = shutil.which("systemctl")
    if not systemctl:
        return _check(
            "systemd_access",
            "systemd command access",
            "unavailable",
            True,
            ["systemd", "kubelet"],
            message="SYSTEMD_COLLECTOR_MODE=command but systemctl is not available in the container.",
            next_step="Use SYSTEMD_COLLECTOR_MODE=file for DaemonSet deployments.",
            details={"collection_mode": mode},
        )
    probe = runner.run([systemctl, "--version"])
    return _check(
        "systemd_access",
        "systemd command access",
        "available" if probe.get("ok") else "limited",
        True,
        ["systemd", "kubelet"],
        message="systemctl command is available." if probe.get("ok") else "systemctl exists but did not run cleanly.",
        path=systemctl,
        next_step="Prefer file mode unless the container intentionally shares the host systemd namespace.",
        details={"collection_mode": mode, "probe": probe},
    )


def _runtime_socket_check(paths: AgentPaths) -> dict[str, Any]:
    candidates = _runtime_socket_candidates(paths)
    existing = [item for item in candidates if item.get("exists")]
    healthy = [item for item in existing if item.get("is_socket")]
    if healthy:
        status = "available"
        message = "At least one CRI runtime socket is present."
    elif existing:
        status = "limited"
        message = "Runtime socket path exists, but it is not a Unix socket."
    else:
        status = "limited"
        message = "No CRI runtime socket was found at configured or common paths."
    return _check(
        "runtime_socket",
        "Container runtime socket",
        status,
        True,
        ["runtime"],
        message=message,
        next_step="Set CONTAINER_RUNTIME_SOCKET_PATHS for non-standard containerd, CRI-O, cri-dockerd, Docker, k3s, rke2, k0s, or MicroK8s paths.",
        details={"candidates": candidates[:20]},
    )


def _conntrack_check(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    count = proc / "sys/net/netfilter/nf_conntrack_count"
    maximum = proc / "sys/net/netfilter/nf_conntrack_max"
    count_readable = _is_readable(count)
    max_readable = _is_readable(maximum)
    status = "available" if count_readable and max_readable else "limited"
    return _check(
        "conntrack_files",
        "conntrack counters",
        status,
        True,
        ["conntrack", "network"],
        message=(
            "conntrack count and max files are readable."
            if status == "available"
            else "conntrack counters are missing or unreadable; module may be absent or host /proc is not mounted."
        ),
        path=str(count),
        next_step="Mount host /proc read-only and verify nf_conntrack is loaded when conntrack pressure analysis is required.",
        details={"count_readable": count_readable, "max_readable": max_readable},
    )


def _cni_config_check(paths: AgentPaths) -> dict[str, Any]:
    candidates = [
        paths.etc_root() / "cni/net.d",
        paths.root / "var/lib/rancher/rke2/agent/etc/cni/net.d",
        paths.root / "var/lib/rancher/k3s/agent/etc/cni/net.d",
        paths.root / "var/lib/k0s/etc/cni/net.d",
        paths.root / "var/snap/microk8s/common/etc/cni/net.d",
    ]
    readable_dirs = [str(path) for path in candidates if _is_readable(path)]
    status = "available" if readable_dirs else "limited"
    return _check(
        "cni_config",
        "CNI configuration",
        status,
        True,
        ["cni"],
        message="CNI configuration directory is readable." if readable_dirs else "No readable CNI configuration directory was found.",
        next_step="Mount /etc and host distribution-specific CNI directories read-only, or set CNI_CONFIG_DIRS.",
        details={"readable_dirs": readable_dirs, "candidate_dirs": [str(path) for path in candidates]},
    )


def _ebpf_check(mode: str, ebpf_enabled: bool) -> dict[str, Any]:
    required = mode == "ebpf" and ebpf_enabled
    if not required:
        return _check(
            "ebpf_runtime",
            "eBPF runtime prerequisites",
            "disabled",
            False,
            ["ebpf"],
            message="eBPF collection is disabled.",
            next_step="Use mode=ebpf and EBPF_ENABLED=true only after validating kernel support and required capabilities.",
            details={"mode": mode, "ebpf_enabled": ebpf_enabled},
        )

    root = _effective_uid() == 0
    bpf_fs = _is_readable(Path("/sys/fs/bpf"))
    debug_fs = _is_readable(Path("/sys/kernel/debug"))
    modules = _is_readable(Path("/lib/modules"))
    if root and bpf_fs and (debug_fs or modules):
        status = "available"
        message = "Basic eBPF filesystem and privilege prerequisites are present."
    else:
        status = "limited"
        message = "One or more eBPF prerequisites are missing."
    return _check(
        "ebpf_runtime",
        "eBPF runtime prerequisites",
        status,
        True,
        ["ebpf"],
        message=message,
        next_step="Validate BPF/PERFMON capabilities, /sys/fs/bpf, /sys/kernel/debug, and kernel headers/modules on a canary node.",
        details={
            "effective_uid_root": root,
            "bpf_fs_readable": bpf_fs,
            "debug_fs_readable": debug_fs,
            "modules_readable": modules,
        },
    )


def _collector_statuses(mode: str, checks_by_key: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    enabled = allowed_collectors(mode)
    requirements = {
        "node": ["state_dir", "host_proc", "host_etc"],
        "kubernetes": ["kubernetes_api"],
        "systemd": ["host_var_log", "systemd_access"],
        "kernel": ["host_var_log", "host_proc"],
        "disk": ["host_root", "host_proc"],
        "inode": ["host_root"],
        "memory": ["host_proc"],
        "process": ["host_proc"],
        "network": ["host_proc", "host_sys"],
        "conntrack": ["host_proc", "conntrack_files"],
        "runtime": ["host_run", "runtime_socket"],
        "kubelet": ["host_var_log", "systemd_access"],
        "cni": ["host_etc", "host_sys", "cni_config"],
        "dns": ["host_etc"],
        "ebpf": ["ebpf_runtime"],
    }
    result: dict[str, dict[str, Any]] = {}
    for collector, keys in requirements.items():
        if collector != "ebpf" and collector not in enabled:
            result[collector] = {
                "status": "disabled",
                "reason": f"collector disabled in AGENT_MODE={mode}",
                "checks": keys,
            }
            continue
        statuses = [checks_by_key[key]["status"] for key in keys if key in checks_by_key]
        status = _worst_status(statuses)
        if collector == "ebpf" and checks_by_key.get("ebpf_runtime", {}).get("status") == "disabled":
            status = "disabled"
        result[collector] = {
            "status": status,
            "reason": _collector_reason(status),
            "checks": keys,
        }
    return result


def _runtime_socket_candidates(paths: AgentPaths) -> list[dict[str, Any]]:
    configured = os.getenv("CONTAINER_RUNTIME_SOCKET_PATHS") or os.getenv("RUNTIME_SOCKET_PATHS") or ""
    raw_paths = []
    if configured.strip():
        for entry in configured.replace(";", ",").split(","):
            entry = entry.strip()
            if not entry:
                continue
            _, raw_path = entry.split("=", 1) if "=" in entry else ("runtime", entry)
            raw_paths.append(raw_path.strip())
    raw_paths.extend([
        "/run/containerd/containerd.sock",
        "/run/k3s/containerd/containerd.sock",
        "/run/rke2/containerd/containerd.sock",
        "/run/k0s/containerd.sock",
        "/var/lib/k0s/run/containerd.sock",
        "/var/snap/microk8s/common/run/containerd.sock",
        "/run/crio/crio.sock",
        "/var/run/crio/crio.sock",
        "/run/cri-dockerd.sock",
        "/var/run/cri-dockerd.sock",
        "/run/docker.sock",
        "/var/run/docker.sock",
    ])
    result = []
    seen = set()
    for raw_path in raw_paths:
        resolved = _resolve_host_path(paths, raw_path)
        key = str(resolved)
        if key in seen:
            continue
        seen.add(key)
        exists, error = _safe_exists(resolved)
        is_socket = False
        if exists:
            try:
                is_socket = stat.S_ISSOCK(resolved.stat().st_mode)
            except OSError as exc:
                error = str(exc)
        result.append({
            "path": key,
            "exists": exists,
            "is_socket": is_socket,
            "error": error,
        })
    return result


def _resolve_host_path(paths: AgentPaths, raw_path: str) -> Path:
    normalized = raw_path.strip().replace("\\", "/")
    if normalized.startswith("/var/run/"):
        return paths.run / normalized.removeprefix("/var/run/")
    if normalized == "/var/run":
        return paths.run
    if normalized.startswith("/run/"):
        return paths.run / normalized.removeprefix("/run/")
    if normalized == "/run":
        return paths.run
    if normalized.startswith("/"):
        return paths.root / normalized.lstrip("/")
    path = Path(raw_path)
    if not path.is_absolute():
        return path
    try:
        return paths.root / path.relative_to("/")
    except ValueError:
        return path


def _check(
    key: str,
    label: str,
    status: str,
    required: bool,
    collectors: list[str],
    *,
    message: str,
    path: str | None = None,
    next_step: str,
    details: dict[str, Any] | None = None,
) -> dict[str, Any]:
    result = {
        "key": key,
        "label": label,
        "status": status,
        "required": required,
        "collectors": collectors,
        "message": message,
        "next_step": next_step,
    }
    if path is not None:
        result["path"] = path
    if details:
        result["details"] = details
    return result


def _summary(values: Any) -> dict[str, int]:
    summary = {"available": 0, "limited": 0, "unavailable": 0, "disabled": 0}
    for value in values:
        status = value.get("status") if isinstance(value, dict) else str(value)
        if status in summary:
            summary[status] += 1
    return summary


def _overall_status(values: Any) -> str:
    statuses = [
        value.get("status")
        for value in values
        if isinstance(value, dict) and value.get("status") != "disabled"
    ]
    if any(status == "unavailable" for status in statuses):
        return "degraded"
    if any(status == "limited" for status in statuses):
        return "limited"
    return "ready"


def _worst_status(statuses: list[str]) -> str:
    if not statuses:
        return "disabled"
    return max(statuses, key=lambda value: STATUS_ORDER.get(value, 99))


def _collector_reason(status: str) -> str:
    if status == "available":
        return "All required prerequisites are available."
    if status == "limited":
        return "Some prerequisites are present but incomplete."
    if status == "unavailable":
        return "At least one required prerequisite is unavailable."
    return "Collector is disabled for this agent mode."


def _safe_exists(path: Path) -> tuple[bool, str | None]:
    try:
        return path.exists(), None
    except OSError as exc:
        return False, str(exc)


def _is_readable(path: Path) -> bool:
    try:
        if not path.exists():
            return False
        if path.is_dir():
            next(path.iterdir(), None)
            return True
        with path.open("rb") as handle:
            handle.read(1)
        return True
    except (OSError, StopIteration):
        return False


def _effective_uid() -> int | None:
    getter = getattr(os, "geteuid", None)
    return getter() if callable(getter) else None
