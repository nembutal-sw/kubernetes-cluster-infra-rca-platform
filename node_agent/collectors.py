from __future__ import annotations

import os
import platform
import re
import json
import shutil
import socket
import ssl
import stat
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any


Collector = Callable[[], dict[str, Any]]

DEFAULT_COLLECTORS = [
    "node",
    "kubernetes",
    "systemd",
    "kernel",
    "disk",
    "inode",
    "memory",
    "process",
    "network",
    "conntrack",
    "runtime",
    "kubelet",
    "cni",
    "dns",
]

SENSITIVE_PATTERNS = [
    re.compile(r"(?i)(token|password|passwd|secret|authorization|api[_-]?key)\s*[:=]\s*([^\s,;]+)"),
    re.compile(r"(?i)(bearer)\s+([a-z0-9._~+/-]+)"),
]


@dataclass(frozen=True)
class AgentPaths:
    root: Path = Path("/host/root")
    proc: Path = Path("/host/proc")
    sys: Path = Path("/host/sys")
    etc: Path = Path("/host/etc")
    var_log: Path = Path("/host/var/log")
    run: Path = Path("/host/run")

    @classmethod
    def from_env(cls) -> "AgentPaths":
        return cls(
            root=Path(os.getenv("HOST_ROOT", "/host/root")),
            proc=Path(os.getenv("HOST_PROC", "/host/proc")),
            sys=Path(os.getenv("HOST_SYS", "/host/sys")),
            etc=Path(os.getenv("HOST_ETC", "/host/etc")),
            var_log=Path(os.getenv("HOST_VAR_LOG", "/host/var/log")),
            run=Path(os.getenv("HOST_RUN", "/host/run")),
        )

    def proc_root(self) -> Path:
        return self.proc if self.proc.exists() else Path("/proc")

    def sys_root(self) -> Path:
        return self.sys if self.sys.exists() else Path("/sys")

    def etc_root(self) -> Path:
        return self.etc if self.etc.exists() else Path("/etc")

    def var_log_root(self) -> Path:
        return self.var_log if self.var_log.exists() else Path("/var/log")

    def host_root(self) -> Path | None:
        return self.root if self.root.exists() else None


@dataclass(frozen=True)
class CommandRunner:
    timeout_seconds: float = 5

    def run(self, command: list[str]) -> dict[str, Any]:
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                check=False,
                text=True,
                timeout=self.timeout_seconds,
            )
            return {
                "ok": completed.returncode == 0,
                "exit_code": completed.returncode,
                "stdout": _clean_text(completed.stdout),
                "stderr": _clean_text(completed.stderr),
            }
        except FileNotFoundError:
            return {
                "ok": False,
                "exit_code": None,
                "stdout": "",
                "stderr": f"command not found: {command[0]}",
            }
        except subprocess.TimeoutExpired as exc:
            return {
                "ok": False,
                "exit_code": None,
                "stdout": _clean_text(exc.stdout or ""),
                "stderr": f"command timed out after {self.timeout_seconds}s",
            }
        except OSError as exc:
            return {
                "ok": False,
                "exit_code": None,
                "stdout": "",
                "stderr": _clean_text(str(exc)),
            }


def collect_evidence(
    requested_collectors: list[str] | None,
    paths: AgentPaths | None = None,
    runner: CommandRunner | None = None,
    registry: Mapping[str, Collector] | None = None,
) -> dict[str, Any]:
    paths = paths or AgentPaths.from_env()
    runner = runner or CommandRunner()
    registry = registry or build_registry(paths, runner)
    selected = requested_collectors or DEFAULT_COLLECTORS

    evidence: dict[str, Any] = {}
    for collector_name in _dedupe(selected):
        collector = registry.get(collector_name)
        if collector is None:
            evidence[collector_name] = {
                "status": "unsupported",
                "error": f"collector is not supported: {collector_name}",
            }
            continue
        evidence[collector_name] = _safe_collect(collector)
    return evidence


def build_registry(paths: AgentPaths, runner: CommandRunner) -> dict[str, Collector]:
    return {
        "node": lambda: collect_node(paths),
        "kubernetes": lambda: collect_kubernetes(),
        "systemd": lambda: collect_systemd(runner),
        "kernel": lambda: collect_kernel(paths, runner),
        "disk": lambda: collect_disk(paths),
        "inode": lambda: collect_inode(paths),
        "memory": lambda: collect_memory(paths),
        "process": lambda: collect_process(paths),
        "network": lambda: collect_network(paths),
        "conntrack": lambda: collect_conntrack(paths),
        "runtime": lambda: collect_runtime(paths, runner),
        "kubelet": lambda: collect_kubelet(runner),
        "cni": lambda: collect_cni(paths),
        "dns": lambda: collect_dns(paths),
    }


def collect_node(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    etc = paths.etc_root()
    kernel_tainted_raw = _safe_int(_read_first_line(proc / "sys/kernel/tainted"))
    return {
        "host_name": _read_first_line(proc / "sys/kernel/hostname") or socket.gethostname(),
        "agent_node_name": os.getenv("NODE_NAME"),
        "kernel_version": platform.release(),
        "platform": platform.platform(),
        "os_release": _parse_os_release(etc / "os-release"),
        "boot_id": _read_first_line(proc / "sys/kernel/random/boot_id"),
        "kernel_tainted": None if kernel_tainted_raw is None else kernel_tainted_raw > 0,
        "kernel_tainted_raw": kernel_tainted_raw,
        "uptime_seconds": _parse_first_float(proc / "uptime"),
        "load_average": _read_first_line(proc / "loadavg"),
    }


def collect_systemd(runner: CommandRunner) -> dict[str, Any]:
    units = {
        "kubelet": _systemctl_show(runner, "kubelet"),
        "containerd": _systemctl_show(runner, "containerd"),
        "rke2-server": _systemctl_show(runner, "rke2-server"),
        "rke2-agent": _systemctl_show(runner, "rke2-agent"),
    }
    kubelet = units["kubelet"]
    containerd = units["containerd"]
    rke2_server = units["rke2-server"]
    rke2_agent = units["rke2-agent"]
    failed_units = _systemctl_failed_units(runner)
    return {
        "kubelet_status": kubelet.get("ActiveState"),
        "kubelet_sub_state": kubelet.get("SubState"),
        "kubelet_result": kubelet.get("Result"),
        "kubelet_restart_count": _safe_int(kubelet.get("NRestarts")),
        "containerd_status": containerd.get("ActiveState"),
        "containerd_sub_state": containerd.get("SubState"),
        "containerd_result": containerd.get("Result"),
        "containerd_restart_count": _safe_int(containerd.get("NRestarts")),
        "rke2_server_status": rke2_server.get("ActiveState"),
        "rke2_server_sub_state": rke2_server.get("SubState"),
        "rke2_server_result": rke2_server.get("Result"),
        "rke2_server_restart_count": _safe_int(rke2_server.get("NRestarts")),
        "rke2_agent_status": rke2_agent.get("ActiveState"),
        "rke2_agent_sub_state": rke2_agent.get("SubState"),
        "rke2_agent_result": rke2_agent.get("Result"),
        "rke2_agent_restart_count": _safe_int(rke2_agent.get("NRestarts")),
        "failed_units": failed_units["units"],
        "failed_units_command": failed_units["command"],
        "units": units,
    }


def collect_kubernetes() -> dict[str, Any]:
    node_name = os.getenv("NODE_NAME") or socket.gethostname()
    timeout_seconds = _bounded_float(os.getenv("KUBERNETES_API_TIMEOUT_SECONDS"), default=3.0, minimum=0.5, maximum=30.0)
    client = _KubernetesApiClient(timeout_seconds=timeout_seconds)
    base: dict[str, Any] = {
        "node_name": node_name,
        "api_available": False,
        "metrics_available": False,
        "node_ready": None,
        "node_pressure": {},
        "pod_count_on_node": None,
        "high_restart_pods": [],
        "cni_high_restart_pods": [],
        "control_plane_peer_connectivity": [],
        "failed_peer_probe_count": 0,
        "certificate_expiration_warnings": [],
    }

    if not client.configured:
        base["api_error"] = client.config_error
        return base

    node_response = client.get_json(f"/api/v1/nodes/{urllib.parse.quote(node_name, safe='')}")
    base["api_available"] = node_response.get("ok") is True
    if not node_response.get("ok"):
        base["api_error"] = node_response.get("error")
        return base

    node = _dict_value(node_response.get("data"))
    node_summary = _summarize_kubernetes_node(node)
    base.update(node_summary)

    pods_response = client.get_json(
        f"/api/v1/pods?fieldSelector={urllib.parse.quote(f'spec.nodeName={node_name}', safe='=')}"
    )
    base["pods"] = pods_response
    if pods_response.get("ok"):
        pod_summary = _summarize_kubernetes_pods(_list_items(pods_response.get("data")))
        base.update(pod_summary)

    events_response = client.get_json(
        "/api/v1/events?fieldSelector="
        + urllib.parse.quote(f"involvedObject.kind=Node,involvedObject.name={node_name}", safe="=,")
    )
    base["node_events"] = events_response
    if events_response.get("ok"):
        base["certificate_expiration_warnings"] = _certificate_expiration_warnings(_list_items(events_response.get("data")))

    readyz_response = client.get_text("/readyz?verbose")
    base["readyz"] = readyz_response
    if readyz_response.get("ok"):
        base["api_readyz_failed_checks"] = _parse_readyz_failures(str(readyz_response.get("body") or ""))

    metrics_response = client.get_json(f"/apis/metrics.k8s.io/v1beta1/nodes/{urllib.parse.quote(node_name, safe='')}")
    base["metrics"] = metrics_response
    base["metrics_available"] = metrics_response.get("ok") is True
    if not metrics_response.get("ok"):
        base["metrics_error"] = metrics_response.get("error")

    nodes_response = client.get_json("/api/v1/nodes")
    base["nodes"] = nodes_response
    if nodes_response.get("ok"):
        peer_results = _probe_control_plane_peers(
            nodes=_list_items(nodes_response.get("data")),
            current_node_name=node_name,
            timeout_seconds=timeout_seconds,
        )
        base["control_plane_peer_connectivity"] = peer_results
        base["failed_peer_probe_count"] = sum(1 for item in peer_results if item.get("ok") is False)

    return base


def collect_kernel(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    proc = paths.proc_root()
    dmesg = runner.run(["dmesg", "--ctime", "--level=err,warn"])
    logs = _read_kernel_log_candidates(paths.var_log_root())
    combined = "\n".join([dmesg.get("stdout", ""), *logs])
    kernel_tainted_raw = _safe_int(_read_first_line(proc / "sys/kernel/tainted"))
    return {
        "dmesg": dmesg,
        "kernel_log_excerpt": _last_lines(combined, 80),
        "kernel_tainted": None if kernel_tainted_raw is None else kernel_tainted_raw > 0,
        "kernel_tainted_raw": kernel_tainted_raw,
        "io_error_detected": _contains_any(combined, ["I/O error", "blk_update_request", "EXT4-fs error"]),
        "nic_error_detected": _contains_any(combined, ["link is down", "link down", "NIC", "tx timeout"]),
        "oom_detected": _contains_any(combined, ["Out of memory", "oom-killer", "Killed process"]),
        "blocked_task_detected": _contains_any(combined, ["blocked for more than", "task blocked"]),
        "read_only_filesystem_detected": _contains_any(
            combined,
            ["Remounting filesystem read-only", "read-only filesystem"],
        ),
    }


def collect_disk(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    root_path = paths.host_root()
    candidates = [
        ("root", root_path),
        ("var_log", paths.var_log_root()),
        ("etc", paths.etc_root()),
    ]
    filesystems = [
        _filesystem_usage(path, role=role)
        for role, path in candidates
        if path is not None and path.exists()
    ]
    root_filesystem = next((item for item in filesystems if item["role"] == "root"), None)
    kernel_logs = "\n".join(_read_kernel_log_candidates(paths.var_log_root()))
    mounts_text = _read_text(proc / "mounts", max_bytes=32768)
    return {
        "root_path_available": root_path is not None,
        "root_usage_percent": root_filesystem["usage_percent"] if root_filesystem else None,
        "inode_usage_percent": root_filesystem["inode_usage_percent"] if root_filesystem else None,
        "root_mount_read_only": _root_mount_read_only(mounts_text),
        "io_wait_percent": None,
        "io_wait_percent_since_boot": _cpu_iowait_percent(proc),
        "io_pressure": _parse_pressure_file(proc / "pressure/io"),
        "kernel_io_error_detected": _contains_any(kernel_logs, ["I/O error", "blk_update_request", "EXT4-fs error"]),
        "filesystems": filesystems,
        "mounts_excerpt": _last_lines(mounts_text, 40),
        "diskstats_excerpt": _last_lines(_read_text(proc / "diskstats", max_bytes=32768), 40),
    }


def collect_inode(paths: AgentPaths) -> dict[str, Any]:
    paths_to_check = [
        ("root", paths.host_root()),
        ("var_log", paths.var_log_root()),
        ("etc", paths.etc_root()),
    ]
    return {
        "filesystems": [
            {
                "role": role,
                "path": str(path),
                "inode_usage_percent": _inode_usage_percent(path),
            }
            for role, path in paths_to_check
            if path is not None and path.exists()
        ]
    }


def collect_memory(paths: AgentPaths) -> dict[str, Any]:
    values = _parse_key_value_file(paths.proc_root() / "meminfo")
    total = values.get("MemTotal")
    available = values.get("MemAvailable") or values.get("MemFree")
    swap_total = values.get("SwapTotal")
    swap_free = values.get("SwapFree")
    usage_percent = None
    if total and available is not None:
        usage_percent = round(((total - available) / total) * 100, 2)
    swap_used = None
    swap_usage_percent = None
    if swap_total is not None and swap_free is not None:
        swap_used = max(swap_total - swap_free, 0)
        swap_usage_percent = round((swap_used / swap_total) * 100, 2) if swap_total else 0.0
    return {
        "usage_percent": usage_percent,
        "mem_total_kib": total,
        "mem_available_kib": available,
        "mem_free_kib": values.get("MemFree"),
        "buffers_kib": values.get("Buffers"),
        "cached_kib": values.get("Cached"),
        "swap_total_kib": swap_total,
        "swap_free_kib": swap_free,
        "swap_used_kib": swap_used,
        "swap_usage_percent": swap_usage_percent,
        "dirty_kib": values.get("Dirty"),
        "writeback_kib": values.get("Writeback"),
        "slab_kib": values.get("Slab"),
        "pressure": _parse_pressure_file(paths.proc_root() / "pressure/memory"),
        "oom_kill_detected": _contains_any(
            "\n".join(_read_kernel_log_candidates(paths.var_log_root())),
            ["Out of memory", "oom-killer", "Killed process"],
        ),
    }


def collect_process(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    try:
        process_dirs = [item for item in proc.iterdir() if item.name.isdigit()] if proc.exists() else []
    except OSError:
        process_dirs = []
    process_count = len(process_dirs) if proc.exists() else None
    pid_max = _safe_int(_read_first_line(proc / "sys/kernel/pid_max"))
    usage_percent = None
    if process_count is not None and pid_max:
        usage_percent = round((process_count / pid_max) * 100, 4)
    return {
        "process_count": process_count,
        "zombie_process_count": _count_zombie_processes(process_dirs),
        "pid_max": pid_max,
        "pid_usage_percent": usage_percent,
    }


def collect_network(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    interfaces = _parse_net_dev(paths.proc_root() / "net/dev")
    interface_state = _read_interface_state(paths.sys_root())
    for interface in interfaces:
        interface.update(interface_state.get(interface["name"], {}))
    snmp_metrics = _parse_proc_net_table(proc / "net/snmp")
    netstat_metrics = _parse_proc_net_table(proc / "net/netstat")
    conntrack = collect_conntrack(paths)
    return {
        "interfaces": interfaces,
        "interfaces_down": [
            item["name"]
            for item in interfaces
            if item.get("operstate") not in (None, "up", "unknown")
        ],
        "interface_rx_error_total": sum(item.get("rx_errors") or 0 for item in interfaces),
        "interface_tx_error_total": sum(item.get("tx_errors") or 0 for item in interfaces),
        "interface_rx_drop_total": sum(item.get("rx_dropped") or 0 for item in interfaces),
        "interface_tx_drop_total": sum(item.get("tx_dropped") or 0 for item in interfaces),
        "default_route_interfaces": _parse_default_route_interfaces(proc / "net/route"),
        "nic_link_flap_detected": any((item.get("carrier_changes") or 0) > 0 for item in interfaces),
        "mtu_mismatch_suspected": None,
        "tcp_retrans_segments": snmp_metrics.get("Tcp", {}).get("RetransSegs"),
        "tcp_attempt_fails": snmp_metrics.get("Tcp", {}).get("AttemptFails"),
        "tcp_ext_listen_overflows": netstat_metrics.get("TcpExt", {}).get("ListenOverflows"),
        "tcp_ext_listen_drops": netstat_metrics.get("TcpExt", {}).get("ListenDrops"),
        "routes_excerpt": _last_lines(_read_text(proc / "net/route", max_bytes=32768), 30),
        "tcp_snmp_excerpt": _last_lines(_read_text(proc / "net/snmp", max_bytes=32768), 40),
        "conntrack_usage_percent": conntrack.get("usage_percent"),
        "conntrack": conntrack,
    }


def collect_conntrack(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    count = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_count"))
    maximum = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_max"))
    usage_percent = None
    available = None
    if count is not None and maximum:
        usage_percent = round((count / maximum) * 100, 2)
        available = max(maximum - count, 0)
    return {
        "count": count,
        "max": maximum,
        "available": available,
        "usage_percent": usage_percent,
        "near_limit": None if usage_percent is None else usage_percent >= 80.0,
    }


def collect_runtime(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    socket_path = paths.run / "containerd/containerd.sock"
    pid_file = paths.run / "containerd/containerd.pid"
    containerd_pid = _safe_int(_read_first_line(pid_file))
    socket_exists = socket_path.exists()
    socket_stat_error = None
    try:
        socket_is_socket = socket_exists and stat.S_ISSOCK(socket_path.stat().st_mode)
    except OSError as exc:
        socket_is_socket = False
        socket_stat_error = _clean_text(str(exc), limit=500)
    socket_probe = _probe_unix_socket(socket_path) if socket_is_socket else {"ok": False, "error": "socket not available"}
    result = {
        "containerd_socket_path": str(socket_path),
        "containerd_socket_exists": socket_exists,
        "containerd_socket_is_socket": socket_is_socket,
        "containerd_socket_healthy": socket_probe["ok"],
        "containerd_socket_latency_ms": socket_probe.get("latency_ms"),
        "containerd_socket_error": socket_stat_error or socket_probe.get("error"),
        "containerd_pid_file": str(pid_file),
        "containerd_pid": containerd_pid,
        "containerd_pid_running": (paths.proc_root() / str(containerd_pid)).exists()
        if containerd_pid is not None
        else None,
    }
    if socket_exists:
        result["ctr_version"] = runner.run(["ctr", "--address", str(socket_path), "version"])
    return result


def collect_kubelet(runner: CommandRunner) -> dict[str, Any]:
    status = _systemctl_show(runner, "kubelet")
    journal = runner.run(["journalctl", "-u", "kubelet", "-n", "80", "--no-pager"])
    return {
        "kubelet_status": status.get("ActiveState"),
        "kubelet_sub_state": status.get("SubState"),
        "kubelet_result": status.get("Result"),
        "kubelet_restart_count": _safe_int(status.get("NRestarts")),
        "systemd": status,
        "journal": journal,
    }


def collect_cni(paths: AgentPaths) -> dict[str, Any]:
    cni_dir = paths.etc_root() / "cni/net.d"
    if not cni_dir.exists():
        return {
            "config_dir": str(cni_dir),
            "config_dir_exists": False,
            "config_count": 0,
            "plugin_types": [],
            "mtu": None,
            "mtu_values": [],
            "parse_errors": [],
            "plugin_errors_detected": None,
            "configs": [],
        }
    configs = []
    plugin_types: list[str] = []
    mtu_values: list[int] = []
    parse_errors: list[dict[str, str]] = []
    try:
        config_paths = sorted(cni_dir.iterdir())
    except OSError:
        config_paths = []
    for path in config_paths:
        if not path.is_file():
            continue
        try:
            size_bytes = path.stat().st_size
        except OSError:
            size_bytes = None
        raw_config = _read_text(path, max_bytes=65536)
        parsed_config = _parse_json(raw_config)
        if raw_config and parsed_config is None:
            parse_errors.append({"name": path.name, "error": "invalid JSON"})
        plugin_types.extend(_cni_plugin_types(parsed_config))
        mtu_values.extend(_find_numeric_values(parsed_config, key="mtu"))
        configs.append(
            {
                "name": path.name,
                "size_bytes": size_bytes,
                "excerpt": _clean_text(raw_config, limit=4096),
                "parsed": parsed_config is not None,
            }
        )
    return {
        "config_dir": str(cni_dir),
        "config_dir_exists": True,
        "config_count": len(configs),
        "plugin_types": _dedupe(plugin_types),
        "mtu": mtu_values[0] if mtu_values else None,
        "mtu_values": mtu_values,
        "parse_errors": parse_errors,
        "plugin_errors_detected": None,
        "configs": configs,
    }


def collect_dns(paths: AgentPaths) -> dict[str, Any]:
    resolv_conf_path = paths.etc_root() / "resolv.conf"
    resolv_conf = _read_text(resolv_conf_path, max_bytes=8192)
    nameservers = []
    search = []
    options = []
    for line in resolv_conf.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if parts[0] == "nameserver" and len(parts) > 1:
            nameservers.append(parts[1])
        elif parts[0] == "search":
            search.extend(parts[1:])
        elif parts[0] == "options":
            options.extend(parts[1:])
    parsed_options = _parse_resolv_options(options)
    return {
        "resolv_conf_path": str(resolv_conf_path),
        "resolv_conf_exists": resolv_conf_path.exists(),
        "resolv_conf_excerpt": _clean_text(resolv_conf),
        "nameservers": nameservers,
        "nameserver_count": len(nameservers),
        "dns_configured": bool(nameservers),
        "dns_lookup_latency_ms": None,
        "search": search,
        "options": options,
        "ndots": parsed_options.get("ndots"),
        "timeout_seconds": parsed_options.get("timeout_seconds"),
        "attempts": parsed_options.get("attempts"),
        "rotate": parsed_options.get("rotate"),
        "single_request_reopen": parsed_options.get("single_request_reopen"),
    }


class _KubernetesApiClient:
    def __init__(self, timeout_seconds: float) -> None:
        self.timeout_seconds = timeout_seconds
        host = os.getenv("KUBERNETES_SERVICE_HOST")
        port = os.getenv("KUBERNETES_SERVICE_PORT", "443")
        token_path = Path(os.getenv("KUBERNETES_SERVICEACCOUNT_TOKEN", "/var/run/secrets/kubernetes.io/serviceaccount/token"))
        ca_path = Path(os.getenv("KUBERNETES_SERVICEACCOUNT_CA", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"))
        self.config_error: str | None = None
        self.base_url = ""
        self.token = ""
        self.context = None

        if not host:
            self.config_error = "KUBERNETES_SERVICE_HOST is not set"
            return
        self.base_url = f"https://{host}:{port}"
        self.token = _read_first_line(token_path) or ""
        if not self.token:
            self.config_error = f"service account token is not readable: {token_path}"
            return
        try:
            self.context = ssl.create_default_context(cafile=str(ca_path)) if ca_path.exists() else None
        except OSError as exc:
            self.config_error = _clean_text(str(exc), limit=500)

    @property
    def configured(self) -> bool:
        return self.config_error is None

    def get_json(self, path: str) -> dict[str, Any]:
        response = self.get_text(path)
        if not response.get("ok"):
            return response
        try:
            response["data"] = json.loads(str(response.get("body") or "{}"))
            response.pop("body", None)
            return response
        except json.JSONDecodeError as exc:
            return {
                "ok": False,
                "status_code": response.get("status_code"),
                "error": f"invalid JSON response: {exc}",
            }

    def get_text(self, path: str) -> dict[str, Any]:
        url = f"{self.base_url}{path}"
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.token}"})
        try:
            started_at = time.monotonic()
            with urllib.request.urlopen(request, timeout=self.timeout_seconds, context=self.context) as response:
                body = response.read(512 * 1024).decode("utf-8", errors="replace")
            return {
                "ok": True,
                "status_code": response.status,
                "latency_ms": round((time.monotonic() - started_at) * 1000, 2),
                "body": _clean_text(body, limit=20000),
            }
        except urllib.error.HTTPError as exc:
            return {
                "ok": False,
                "status_code": exc.code,
                "error": _clean_text(str(exc), limit=500),
            }
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            return {
                "ok": False,
                "status_code": None,
                "error": _clean_text(str(exc), limit=500),
            }


def _summarize_kubernetes_node(node: dict[str, Any]) -> dict[str, Any]:
    status = _dict_value(node.get("status"))
    metadata = _dict_value(node.get("metadata"))
    node_info = _dict_value(status.get("nodeInfo"))
    conditions = _node_conditions(status.get("conditions"))
    pressure = {
        key: value.get("status")
        for key, value in conditions.items()
        if key in {"DiskPressure", "MemoryPressure", "PIDPressure", "NetworkUnavailable"}
    }
    return {
        "node_uid": metadata.get("uid"),
        "node_labels": _redact_mapping(_dict_value(metadata.get("labels"))),
        "node_taints": _safe_json_list(_dict_value(node.get("spec")).get("taints")),
        "node_conditions": conditions,
        "node_ready": conditions.get("Ready", {}).get("status") == "True",
        "node_pressure": pressure,
        "kubelet_version": node_info.get("kubeletVersion"),
        "container_runtime_version": node_info.get("containerRuntimeVersion"),
        "kernel_version": node_info.get("kernelVersion"),
        "addresses": _node_addresses(status.get("addresses")),
        "capacity": _dict_value(status.get("capacity")),
        "allocatable": _dict_value(status.get("allocatable")),
    }


def _summarize_kubernetes_pods(pods: list[dict[str, Any]]) -> dict[str, Any]:
    high_restart_pods = []
    cni_high_restart_pods = []
    non_running_pods = []
    total_restart_count = 0
    kube_system_pod_count = 0
    for pod in pods:
        metadata = _dict_value(pod.get("metadata"))
        status = _dict_value(pod.get("status"))
        spec = _dict_value(pod.get("spec"))
        namespace = str(metadata.get("namespace") or "")
        name = str(metadata.get("name") or "")
        phase = str(status.get("phase") or "")
        if namespace == "kube-system":
            kube_system_pod_count += 1
        if phase not in {"Running", "Succeeded"}:
            non_running_pods.append({"namespace": namespace, "name": name, "phase": phase})
        restart_count = _pod_restart_count(status)
        total_restart_count += restart_count
        pod_summary = {
            "namespace": namespace,
            "name": name,
            "phase": phase,
            "restart_count": restart_count,
            "node_name": spec.get("nodeName"),
        }
        if restart_count >= 5:
            high_restart_pods.append(pod_summary)
        if restart_count >= 5 and _is_cni_pod(metadata):
            cni_high_restart_pods.append(pod_summary)
    return {
        "pod_count_on_node": len(pods),
        "kube_system_pod_count_on_node": kube_system_pod_count,
        "non_running_pods": non_running_pods[:30],
        "pod_restart_count_total": total_restart_count,
        "high_restart_pods": high_restart_pods[:30],
        "cni_high_restart_pods": cni_high_restart_pods[:30],
    }


def _probe_control_plane_peers(
    nodes: list[dict[str, Any]],
    current_node_name: str,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    ports = _probe_ports()
    results = []
    for node in nodes:
        metadata = _dict_value(node.get("metadata"))
        node_name = str(metadata.get("name") or "")
        if not node_name or node_name == current_node_name or not _is_control_plane_node(metadata):
            continue
        address = _primary_node_address(_node_addresses(_dict_value(node.get("status")).get("addresses")))
        if not address:
            continue
        for port in ports:
            results.append({"node": node_name, "address": address, "port": port, **_probe_tcp(address, port, timeout_seconds)})
    return results[:60]


def _probe_ports() -> list[int]:
    raw = os.getenv("CONTROL_PLANE_PROBE_PORTS", "6443,9345")
    ports = []
    for item in raw.split(","):
        value = _safe_int(item)
        if value is not None and 0 < value < 65536:
            ports.append(value)
    return ports or [6443, 9345]


def _probe_tcp(host: str, port: int, timeout_seconds: float) -> dict[str, Any]:
    started_at = time.monotonic()
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout_seconds)
    try:
        sock.connect((host, port))
        return {"ok": True, "latency_ms": round((time.monotonic() - started_at) * 1000, 2)}
    except OSError as exc:
        return {"ok": False, "error": _clean_text(str(exc), limit=300)}
    finally:
        sock.close()


def _node_conditions(value: Any) -> dict[str, dict[str, Any]]:
    conditions: dict[str, dict[str, Any]] = {}
    if not isinstance(value, list):
        return conditions
    for item in value:
        if not isinstance(item, dict):
            continue
        condition_type = str(item.get("type") or "")
        if not condition_type:
            continue
        conditions[condition_type] = {
            "status": item.get("status"),
            "reason": item.get("reason"),
            "message": _clean_text(str(item.get("message") or ""), limit=500),
            "last_transition_time": item.get("lastTransitionTime"),
            "last_heartbeat_time": item.get("lastHeartbeatTime"),
        }
    return conditions


def _node_addresses(value: Any) -> list[dict[str, str]]:
    if not isinstance(value, list):
        return []
    addresses = []
    for item in value:
        if isinstance(item, dict) and item.get("address"):
            addresses.append({"type": str(item.get("type") or ""), "address": str(item.get("address"))})
    return addresses


def _primary_node_address(addresses: list[dict[str, str]]) -> str | None:
    for address_type in ("InternalIP", "ExternalIP", "Hostname"):
        for item in addresses:
            if item.get("type") == address_type and item.get("address"):
                return item["address"]
    return None


def _pod_restart_count(status: dict[str, Any]) -> int:
    total = 0
    for key in ("initContainerStatuses", "containerStatuses"):
        values = status.get(key)
        if not isinstance(values, list):
            continue
        for item in values:
            if isinstance(item, dict):
                total += _safe_int(item.get("restartCount")) or 0
    return total


def _is_cni_pod(metadata: dict[str, Any]) -> bool:
    namespace = str(metadata.get("namespace") or "").lower()
    name = str(metadata.get("name") or "").lower()
    labels = _dict_value(metadata.get("labels"))
    label_text = " ".join(str(value).lower() for value in labels.values())
    return namespace == "kube-system" and any(
        token in f"{name} {label_text}"
        for token in ("cilium", "calico", "flannel", "weave", "antrea", "canal")
    )


def _is_control_plane_node(metadata: dict[str, Any]) -> bool:
    labels = _dict_value(metadata.get("labels"))
    return any(
        key in labels
        for key in (
            "node-role.kubernetes.io/control-plane",
            "node-role.kubernetes.io/master",
            "node-role.kubernetes.io/etcd",
        )
    )


def _certificate_expiration_warnings(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    warnings = []
    for event in events:
        if event.get("reason") != "CertificateExpirationWarning":
            continue
        warnings.append(
            {
                "reason": event.get("reason"),
                "message": _clean_text(str(event.get("message") or ""), limit=1000),
                "last_timestamp": event.get("lastTimestamp") or event.get("eventTime"),
                "count": event.get("count"),
            }
        )
    return warnings[:10]


def _parse_readyz_failures(text: str) -> list[str]:
    failures = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("[-]"):
            failures.append(_clean_text(stripped, limit=500))
    return failures


def _list_items(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, dict) and isinstance(value.get("items"), list):
        return [item for item in value["items"] if isinstance(item, dict)]
    return []


def _dict_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _safe_json_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _redact_mapping(value: dict[str, Any]) -> dict[str, Any]:
    redacted = {}
    for key, item in value.items():
        text_key = str(key)
        if any(pattern.search(text_key) for pattern in SENSITIVE_PATTERNS):
            redacted[text_key] = "<redacted>"
        else:
            redacted[text_key] = item
    return redacted


def _safe_collect(collector: Collector) -> dict[str, Any]:
    try:
        data = collector()
        return {"status": "ok", **data}
    except Exception as exc:  # noqa: BLE001 - evidence collection must not crash the agent.
        return {
            "status": "error",
            "error": _clean_text(str(exc), limit=1000),
        }


def _systemctl_show(runner: CommandRunner, unit: str) -> dict[str, Any]:
    result = runner.run(
        [
            "systemctl",
            "show",
            unit,
            "--property=Id,ActiveState,SubState,NRestarts,Result",
            "--no-pager",
        ]
    )
    parsed = _parse_systemctl_show(result.get("stdout", ""))
    parsed["command"] = result
    return parsed


def _systemctl_failed_units(runner: CommandRunner) -> dict[str, Any]:
    result = runner.run(["systemctl", "--failed", "--no-legend", "--plain", "--no-pager"])
    return {
        "units": _parse_failed_units(result.get("stdout", "")) if result.get("ok") else [],
        "command": result,
    }


def _parse_systemctl_show(output: str) -> dict[str, str]:
    parsed = {}
    for line in output.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed[key] = value
    return parsed


def _parse_failed_units(output: str) -> list[dict[str, str]]:
    units = []
    for line in output.splitlines():
        fields = line.split(None, 4)
        if len(fields) < 4:
            continue
        unit = {
            "unit": fields[0],
            "load": fields[1],
            "active": fields[2],
            "sub": fields[3],
            "description": fields[4] if len(fields) > 4 else "",
        }
        units.append(unit)
    return units


def _filesystem_usage(path: Path, role: str) -> dict[str, Any]:
    usage = shutil.disk_usage(path)
    return {
        "role": role,
        "path": str(path),
        "total_bytes": usage.total,
        "used_bytes": usage.used,
        "free_bytes": usage.free,
        "usage_percent": round((usage.used / usage.total) * 100, 2) if usage.total else None,
        "inode_usage_percent": _inode_usage_percent(path),
    }


def _inode_usage_percent(path: Path) -> float | None:
    try:
        values = os.statvfs(path)
    except (AttributeError, OSError):
        return None
    total = values.f_files
    free = values.f_ffree
    if not total:
        return None
    return round(((total - free) / total) * 100, 2)


def _root_mount_read_only(mounts_text: str) -> bool | None:
    for line in mounts_text.splitlines():
        fields = line.split()
        if len(fields) < 4:
            continue
        if fields[1] != "/":
            continue
        options = fields[3].split(",")
        return "ro" in options
    return None


def _cpu_iowait_percent(proc: Path) -> float | None:
    first_line = _read_first_line(proc / "stat")
    if not first_line:
        return None
    fields = first_line.split()
    if not fields or fields[0] != "cpu" or len(fields) < 6:
        return None
    values = [_safe_int(value) or 0 for value in fields[1:]]
    total = sum(values)
    if total == 0:
        return None
    iowait = values[4]
    return round((iowait / total) * 100, 4)


def _parse_pressure_file(path: Path) -> dict[str, dict[str, float | int]]:
    parsed: dict[str, dict[str, float | int]] = {}
    for line in _read_text(path, max_bytes=8192).splitlines():
        fields = line.split()
        if not fields:
            continue
        category = fields[0]
        values: dict[str, float | int] = {}
        for field in fields[1:]:
            if "=" not in field:
                continue
            key, raw_value = field.split("=", 1)
            if key == "total":
                int_value = _safe_int(raw_value)
                if int_value is not None:
                    values[key] = int_value
                continue
            float_value = _safe_float(raw_value)
            if float_value is not None:
                values[key] = float_value
        parsed[category] = values
    return parsed


def _count_zombie_processes(process_dirs: list[Path]) -> int:
    count = 0
    for process_dir in process_dirs:
        status_text = _read_text(process_dir / "status", max_bytes=4096)
        for line in status_text.splitlines():
            if line.startswith("State:") and re.search(r"\bZ\b", line):
                count += 1
                break
    return count


def _read_interface_state(sys_root: Path) -> dict[str, dict[str, Any]]:
    net_root = sys_root / "class/net"
    if not net_root.exists():
        return {}
    states = {}
    try:
        interface_dirs = list(net_root.iterdir())
    except OSError:
        return {}
    for interface_dir in interface_dirs:
        if not interface_dir.is_dir():
            continue
        states[interface_dir.name] = {
            "operstate": _read_first_line(interface_dir / "operstate"),
            "carrier": _safe_int(_read_first_line(interface_dir / "carrier")),
            "carrier_changes": _safe_int(_read_first_line(interface_dir / "carrier_changes")),
            "mtu": _safe_int(_read_first_line(interface_dir / "mtu")),
        }
    return states


def _parse_default_route_interfaces(path: Path) -> list[str]:
    interfaces = []
    for line in _read_text(path, max_bytes=32768).splitlines()[1:]:
        fields = line.split()
        if len(fields) < 3:
            continue
        interface_name, destination, gateway = fields[0], fields[1], fields[2]
        if destination == "00000000" and gateway != "00000000":
            interfaces.append(interface_name)
    return _dedupe(interfaces)


def _probe_unix_socket(path: Path, timeout_seconds: float = 1) -> dict[str, Any]:
    started_at = time.monotonic()
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout_seconds)
    try:
        sock.connect(str(path))
        latency_ms = round((time.monotonic() - started_at) * 1000, 2)
        return {"ok": True, "latency_ms": latency_ms}
    except OSError as exc:
        return {"ok": False, "error": _clean_text(str(exc), limit=500)}
    finally:
        sock.close()


def _parse_net_dev(path: Path) -> list[dict[str, Any]]:
    text = _read_text(path, max_bytes=65536)
    interfaces = []
    for line in text.splitlines()[2:]:
        if ":" not in line:
            continue
        name, values = line.split(":", 1)
        fields = values.split()
        if len(fields) < 16:
            continue
        interfaces.append(
            {
                "name": name.strip(),
                "rx_bytes": _safe_int(fields[0]),
                "rx_errors": _safe_int(fields[2]),
                "rx_dropped": _safe_int(fields[3]),
                "tx_bytes": _safe_int(fields[8]),
                "tx_errors": _safe_int(fields[10]),
                "tx_dropped": _safe_int(fields[11]),
            }
        )
    return interfaces


def _parse_proc_net_table(path: Path) -> dict[str, dict[str, int]]:
    parsed: dict[str, dict[str, int]] = {}
    pending_headers: dict[str, list[str]] = {}
    for line in _read_text(path, max_bytes=65536).splitlines():
        if ":" not in line:
            continue
        protocol, raw_fields = line.split(":", 1)
        fields = raw_fields.split()
        if not fields:
            continue
        previous_headers = pending_headers.get(protocol)
        if previous_headers and len(previous_headers) == len(fields):
            values = [_safe_int(value) for value in fields]
            if all(value is not None for value in values):
                parsed[protocol] = dict(zip(previous_headers, values))
                pending_headers.pop(protocol, None)
                continue
        pending_headers[protocol] = fields
    return parsed


def _parse_json(text: str) -> Any | None:
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def _cni_plugin_types(value: Any) -> list[str]:
    if isinstance(value, dict):
        plugin_types = []
        raw_type = value.get("type")
        if isinstance(raw_type, str):
            plugin_types.append(raw_type)
        raw_plugins = value.get("plugins")
        if isinstance(raw_plugins, list):
            for plugin in raw_plugins:
                plugin_types.extend(_cni_plugin_types(plugin))
        return plugin_types
    if isinstance(value, list):
        plugin_types = []
        for item in value:
            plugin_types.extend(_cni_plugin_types(item))
        return plugin_types
    return []


def _find_numeric_values(value: Any, key: str) -> list[int]:
    if isinstance(value, dict):
        values = []
        for raw_key, raw_value in value.items():
            if str(raw_key).lower() == key and isinstance(raw_value, int) and not isinstance(raw_value, bool):
                values.append(raw_value)
            values.extend(_find_numeric_values(raw_value, key))
        return values
    if isinstance(value, list):
        values = []
        for item in value:
            values.extend(_find_numeric_values(item, key))
        return values
    return []


def _parse_resolv_options(options: list[str]) -> dict[str, Any]:
    parsed: dict[str, Any] = {
        "ndots": None,
        "timeout_seconds": None,
        "attempts": None,
        "rotate": False,
        "single_request_reopen": False,
    }
    for option in options:
        if ":" in option:
            key, value = option.split(":", 1)
        else:
            key, value = option, None
        if key == "ndots":
            parsed["ndots"] = _safe_int(value)
        elif key == "timeout":
            parsed["timeout_seconds"] = _safe_int(value)
        elif key == "attempts":
            parsed["attempts"] = _safe_int(value)
        elif key == "rotate":
            parsed["rotate"] = True
        elif key == "single-request-reopen":
            parsed["single_request_reopen"] = True
    return parsed


def _parse_key_value_file(path: Path) -> dict[str, int]:
    values = {}
    for line in _read_text(path, max_bytes=32768).splitlines():
        if ":" not in line:
            continue
        key, raw_value = line.split(":", 1)
        match = re.search(r"\d+", raw_value)
        if match:
            values[key] = int(match.group(0))
    return values


def _parse_os_release(path: Path) -> dict[str, str]:
    data = {}
    for line in _read_text(path, max_bytes=8192).splitlines():
        if "=" not in line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        data[key] = value.strip().strip('"')
    return data


def _read_kernel_log_candidates(var_log: Path) -> list[str]:
    candidates = [
        var_log / "kern.log",
        var_log / "messages",
        var_log / "syslog",
    ]
    return [_read_text(path, max_bytes=65536) for path in candidates if path.exists()]


def _read_first_line(path: Path) -> str | None:
    text = _read_text(path, max_bytes=4096)
    if not text:
        return None
    return text.splitlines()[0].strip()


def _parse_first_float(path: Path) -> float | None:
    first_line = _read_first_line(path)
    if not first_line:
        return None
    try:
        return float(first_line.split()[0])
    except (IndexError, ValueError):
        return None


def _read_text(path: Path, max_bytes: int) -> str:
    try:
        with path.open("rb") as handle:
            return handle.read(max_bytes).decode("utf-8", errors="replace")
    except OSError:
        return ""


def _last_lines(text: str, max_lines: int) -> list[str]:
    if not text:
        return []
    return [_clean_text(line) for line in text.splitlines()[-max_lines:]]


def _clean_text(text: str | bytes, limit: int = 4000) -> str:
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    for pattern in SENSITIVE_PATTERNS:
        text = pattern.sub(lambda match: f"{match.group(1)}=<redacted>", text)
    text = text.replace("\x00", "")
    if len(text) > limit:
        return text[:limit] + "...<truncated>"
    return text


def _contains_any(text: str, keywords: list[str]) -> bool:
    lowered = text.lower()
    return any(keyword.lower() in lowered for keyword in keywords)


def _safe_int(value: object) -> int | None:
    if value is None:
        return None
    try:
        return int(str(value).strip())
    except ValueError:
        return None


def _safe_float(value: object) -> float | None:
    if value is None:
        return None
    try:
        return float(str(value).strip())
    except ValueError:
        return None


def _bounded_float(value: object, default: float, minimum: float, maximum: float) -> float:
    parsed = _safe_float(value)
    if parsed is None:
        return default
    return min(max(parsed, minimum), maximum)


def _dedupe(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result
