from __future__ import annotations

# Internal implementation module. Public collector APIs and metadata live in
# the sibling modules and registry.

import os
import copy
import platform
import re
import json
import shutil
import socket
import ssl
import stat
import subprocess
import time
import threading
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
        "systemd": lambda: collect_systemd(paths, runner),
        "kernel": lambda: collect_kernel(paths, runner),
        "disk": lambda: collect_disk(paths),
        "inode": lambda: collect_inode(paths),
        "memory": lambda: collect_memory(paths),
        "process": lambda: collect_process(paths),
        "network": lambda: collect_network(paths),
        "conntrack": lambda: collect_conntrack(paths),
        "runtime": lambda: collect_runtime(paths, runner),
        "kubelet": lambda: collect_kubelet(paths, runner),
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


def collect_systemd(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    mode = _systemd_collector_mode()
    if mode == "file":
        return _collect_systemd_from_host_files(paths)

    units = {
        "kubelet": _systemctl_show(runner, "kubelet"),
        "containerd": _systemctl_show(runner, "containerd"),
        "crio": _systemctl_show(runner, "crio"),
        "docker": _systemctl_show(runner, "docker"),
        "cri-docker": _systemctl_show(runner, "cri-docker"),
        "rke2-server": _systemctl_show(runner, "rke2-server"),
        "rke2-agent": _systemctl_show(runner, "rke2-agent"),
        "k3s": _systemctl_show(runner, "k3s"),
        "k3s-agent": _systemctl_show(runner, "k3s-agent"),
        "k0scontroller": _systemctl_show(runner, "k0scontroller"),
        "k0sworker": _systemctl_show(runner, "k0sworker"),
        "microk8s.daemon-kubelet": _systemctl_show(runner, "snap.microk8s.daemon-kubelet"),
        "microk8s.daemon-containerd": _systemctl_show(runner, "snap.microk8s.daemon-containerd"),
    }
    kubelet = units["kubelet"]
    containerd = units["containerd"]
    rke2_server = units["rke2-server"]
    rke2_agent = units["rke2-agent"]
    failed_units = _systemctl_failed_units(runner)
    runtime_processes = _matching_processes(
        runner,
        ["rke2", "k3s", "k0s", "microk8s", "containerd", "crio", "cri-dockerd", "dockerd", "kubelet"],
    )
    runtime_units = _unit_summaries(
        units,
        ["containerd", "crio", "docker", "cri-docker", "microk8s.daemon-containerd"],
    )
    distribution_units = _unit_summaries(
        units,
        ["rke2-server", "rke2-agent", "k3s", "k3s-agent", "k0scontroller", "k0sworker", "microk8s.daemon-kubelet"],
    )
    result = {
        "collection_mode": "command",
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
        "k3s_status": units["k3s"].get("ActiveState"),
        "k3s_sub_state": units["k3s"].get("SubState"),
        "k3s_restart_count": _safe_int(units["k3s"].get("NRestarts")),
        "k3s_agent_status": units["k3s-agent"].get("ActiveState"),
        "k3s_agent_sub_state": units["k3s-agent"].get("SubState"),
        "k3s_agent_restart_count": _safe_int(units["k3s-agent"].get("NRestarts")),
        "crio_status": units["crio"].get("ActiveState"),
        "crio_sub_state": units["crio"].get("SubState"),
        "crio_restart_count": _safe_int(units["crio"].get("NRestarts")),
        "docker_status": units["docker"].get("ActiveState"),
        "docker_sub_state": units["docker"].get("SubState"),
        "docker_restart_count": _safe_int(units["docker"].get("NRestarts")),
        "runtime_units": runtime_units,
        "distribution_units": distribution_units,
        "active_runtime_units": [unit["name"] for unit in runtime_units if _unit_summary_healthy(unit)],
        "active_distribution_units": [unit["name"] for unit in distribution_units if _unit_summary_healthy(unit)],
        "embedded_kubelet_running": _process_sample_contains(runtime_processes, "kubelet"),
        "embedded_runtime_running": _process_sample_contains_any(
            runtime_processes,
            ["containerd", "crio", "cri-dockerd", "dockerd"],
        ),
        "runtime_process_sample": runtime_processes[:20],
        "rke2_embedded_kubelet_running": _process_sample_contains(runtime_processes, "kubelet"),
        "rke2_embedded_containerd_running": _process_sample_contains(runtime_processes, "containerd"),
        "rke2_process_sample": runtime_processes[:20],
        "failed_units": failed_units["units"],
        "failed_units_command": failed_units["command"],
        "units": units,
        "host_log_files": _host_service_log_excerpts(paths),
    }
    return result


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
        "cni_pod_count_on_node": 0,
        "cni_running_pod_count_on_node": 0,
        "cni_non_running_pods": [],
        "cni_high_restart_pods": [],
        "cni_daemonsets": [],
        "cni_daemonset_count": 0,
        "cni_daemonsets_unavailable": [],
        "cni_daemonset_unavailable_count": 0,
        "cni_daemonsets_not_scheduled": [],
        "cni_daemonset_not_scheduled_count": 0,
        "control_plane_peer_connectivity": [],
        "failed_peer_probe_count": 0,
        "certificate_expiration_warnings": [],
        "api_request_latencies": [],
        "api_request_error_count": 0,
        "api_timeout_detected": False,
        "api_server_latency_ms": None,
        "api_readyz_latency_ms": None,
        "api_readyz_failed_checks": [],
        "api_readyz_failed_check_count": 0,
        "api_livez_latency_ms": None,
        "api_livez_failed_checks": [],
        "api_livez_failed_check_count": 0,
        "etcd_readyz_healthy": None,
        "etcd_readyz_message": None,
        "api_server_pod_count_on_node": 0,
        "api_server_non_running_pods": [],
        "api_server_restart_count_total": 0,
        "api_server_high_restart_pods": [],
        "etcd_pod_count_on_node": 0,
        "etcd_non_running_pods": [],
        "etcd_restart_count_total": 0,
        "etcd_high_restart_pods": [],
        "topology_inventory_collected": False,
        "topology_inventory_collector_node": None,
        "topology_inventory_truncated": False,
        "topology_inventory_complete": False,
    }

    if not client.configured:
        base["api_error"] = client.config_error
        return base

    node_response = client.get_json(f"/api/v1/nodes/{urllib.parse.quote(node_name, safe='')}")
    base["api_available"] = node_response.get("ok") is True
    if not node_response.get("ok"):
        base["api_error"] = node_response.get("error")
        _summarize_api_requests(base, [("node", node_response)])
        return base

    node = _dict_value(node_response.get("data"))
    node_summary = _summarize_kubernetes_node(node)
    base.update(node_summary)

    pods_response = client.get_json(
        f"/api/v1/pods?fieldSelector={urllib.parse.quote(f'spec.nodeName={node_name}', safe='=')}"
    )
    base["pods"] = pods_response
    if pods_response.get("ok"):
        pods_on_node = _list_items(pods_response.get("data"))
        pod_summary = _summarize_kubernetes_pods(pods_on_node)
        base.update(pod_summary)
        base.update(_summarize_control_plane_pods(pods_on_node))

    cni_daemonsets_response = client.get_json("/apis/apps/v1/namespaces/kube-system/daemonsets?limit=100")
    base["cni_daemonsets_response"] = cni_daemonsets_response
    if cni_daemonsets_response.get("ok"):
        base.update(_summarize_cni_daemonsets(_list_items(cni_daemonsets_response.get("data"))))

    coredns_pods_response = client.get_json(
        "/api/v1/namespaces/kube-system/pods?"
        + urllib.parse.urlencode({"labelSelector": "k8s-app=kube-dns", "limit": "20"})
    )
    base["coredns_pods"] = coredns_pods_response
    if coredns_pods_response.get("ok"):
        base.update(_summarize_coredns_pods(_list_items(coredns_pods_response.get("data"))))

    coredns_endpoint_slices_response = client.get_json(
        "/apis/discovery.k8s.io/v1/namespaces/kube-system/endpointslices?"
        + urllib.parse.urlencode({"labelSelector": "kubernetes.io/service-name=kube-dns", "limit": "20"})
    )
    base["coredns_endpoint_slices"] = coredns_endpoint_slices_response
    if coredns_endpoint_slices_response.get("ok"):
        base.update(_summarize_coredns_endpoint_slices(_list_items(coredns_endpoint_slices_response.get("data"))))

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
        readyz_checks = _parse_readyz_checks(str(readyz_response.get("body") or ""))
        readyz_failures = _readyz_failures(readyz_checks)
        base["api_readyz_checks"] = readyz_checks
        base["api_readyz_failed_checks"] = readyz_failures
        base["api_readyz_failed_check_count"] = len(readyz_failures)
        base.update(_summarize_etcd_readyz(readyz_checks))
    base["api_readyz_latency_ms"] = readyz_response.get("latency_ms")

    livez_response = client.get_text("/livez?verbose")
    base["livez"] = livez_response
    if livez_response.get("ok"):
        livez_checks = _parse_readyz_checks(str(livez_response.get("body") or ""))
        livez_failures = _readyz_failures(livez_checks)
        base["api_livez_checks"] = livez_checks
        base["api_livez_failed_checks"] = livez_failures
        base["api_livez_failed_check_count"] = len(livez_failures)
    base["api_livez_latency_ms"] = livez_response.get("latency_ms")

    metrics_response = client.get_json(f"/apis/metrics.k8s.io/v1beta1/nodes/{urllib.parse.quote(node_name, safe='')}")
    base["metrics"] = metrics_response
    base["metrics_available"] = metrics_response.get("ok") is True
    if not metrics_response.get("ok"):
        base["metrics_error"] = metrics_response.get("error")

    nodes_response = client.get_json("/api/v1/nodes")
    base["nodes"] = nodes_response
    if nodes_response.get("ok"):
        nodes = _list_items(nodes_response.get("data"))
        peer_results = _probe_control_plane_peers(
            nodes=nodes,
            current_node_name=node_name,
            timeout_seconds=timeout_seconds,
        )
        base["control_plane_peer_connectivity"] = peer_results
        base["failed_peer_probe_count"] = sum(1 for item in peer_results if item.get("ok") is False)
        topology_collector = _topology_collector_node(nodes)
        base["topology_inventory_collector_node"] = topology_collector
        if _env_bool("KUBERNETES_TOPOLOGY_ENABLED", default=True) and topology_collector == node_name:
            topology_limit = _bounded_int(
                os.getenv("KUBERNETES_TOPOLOGY_MAX_ITEMS"),
                default=500,
                minimum=50,
                maximum=5000,
            )
            services_response = client.get_json(f"/api/v1/services?limit={topology_limit}")
            endpoint_slices_response = client.get_json(
                f"/apis/discovery.k8s.io/v1/endpointslices?limit={topology_limit}"
            )
            base["services"] = services_response
            base["endpoint_slices"] = endpoint_slices_response
            base["topology_inventory_collected"] = True
            base["topology_inventory_truncated"] = any(
                bool(_dict_value(response.get("data")).get("metadata", {}).get("continue"))
                for response in (services_response, endpoint_slices_response)
                if response.get("ok") is True
            )
            base["topology_inventory_complete"] = (
                services_response.get("ok") is True
                and endpoint_slices_response.get("ok") is True
                and not base["topology_inventory_truncated"]
            )

    _summarize_api_requests(
        base,
        [
            ("node", node_response),
            ("pods_on_node", pods_response),
            ("cni_daemonsets", cni_daemonsets_response),
            ("coredns_pods", coredns_pods_response),
            ("coredns_endpoint_slices", coredns_endpoint_slices_response),
            ("node_events", events_response),
            ("readyz", readyz_response),
            ("livez", livez_response),
            ("metrics", metrics_response),
            ("nodes", nodes_response),
        ],
    )
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
        interface["interface_kind"] = _interface_kind(interface["name"], interface)
        interface["physical_candidate"] = _is_physical_interface(interface)
    physical_interfaces = [item for item in interfaces if item.get("physical_candidate") is True]
    link_flap_threshold = _bounded_int(
        os.getenv("NIC_LINK_FLAP_CARRIER_CHANGES_THRESHOLD"),
        default=3,
        minimum=1,
        maximum=1000,
    )
    flapping_physical_interfaces = [
        item
        for item in physical_interfaces
        if (item.get("carrier_changes") or 0) >= link_flap_threshold
    ]
    snmp_metrics = _parse_proc_net_table(proc / "net/snmp")
    netstat_metrics = _parse_proc_net_table(proc / "net/netstat")
    conntrack = collect_conntrack(paths)
    uptime_seconds = _parse_first_float(proc / "uptime")
    tcp_retrans_segments = snmp_metrics.get("Tcp", {}).get("RetransSegs")
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
        "physical_interfaces": [item["name"] for item in physical_interfaces],
        "physical_interface_rx_error_total": sum(item.get("rx_errors") or 0 for item in physical_interfaces),
        "physical_interface_tx_error_total": sum(item.get("tx_errors") or 0 for item in physical_interfaces),
        "physical_interface_rx_drop_total": sum(item.get("rx_dropped") or 0 for item in physical_interfaces),
        "physical_interface_tx_drop_total": sum(item.get("tx_dropped") or 0 for item in physical_interfaces),
        "default_route_interfaces": _parse_default_route_interfaces(proc / "net/route"),
        "nic_link_flap_detected": bool(flapping_physical_interfaces),
        "nic_link_flap_threshold": link_flap_threshold,
        "flapping_physical_interfaces": [
            {
                "name": item.get("name"),
                "carrier_changes": item.get("carrier_changes"),
                "operstate": item.get("operstate"),
                "carrier": item.get("carrier"),
            }
            for item in flapping_physical_interfaces[:10]
        ],
        "mtu_mismatch_suspected": None,
        "tcp_retrans_segments": tcp_retrans_segments,
        "tcp_retrans_segments_per_hour_since_boot": _per_hour(tcp_retrans_segments, uptime_seconds),
        "tcp_attempt_fails": snmp_metrics.get("Tcp", {}).get("AttemptFails"),
        "tcp_ext_listen_overflows": netstat_metrics.get("TcpExt", {}).get("ListenOverflows"),
        "tcp_ext_listen_drops": netstat_metrics.get("TcpExt", {}).get("ListenDrops"),
        "routes_excerpt": _last_lines(_read_text(proc / "net/route", max_bytes=32768), 30),
        "tcp_snmp_excerpt": _last_lines(_read_text(proc / "net/snmp", max_bytes=32768), 40),
        "conntrack_usage_percent": conntrack.get("usage_percent"),
        "conntrack_insert_failed": conntrack.get("insert_failed"),
        "conntrack_drop_total": conntrack.get("drop"),
        "conntrack_early_drop_total": conntrack.get("early_drop"),
        "conntrack": conntrack,
    }


def collect_conntrack(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    count = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_count"))
    maximum = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_max"))
    buckets = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_buckets"))
    hashsize = _safe_int(_read_first_line(paths.sys_root() / "module/nf_conntrack/parameters/hashsize"))
    stats, stats_excerpt = _parse_nf_conntrack_stats(proc / "net/stat/nf_conntrack")
    insert_failed = stats.get("insert_failed")
    drop = stats.get("drop")
    early_drop = stats.get("early_drop")
    invalid = stats.get("invalid")
    error = stats.get("error")
    kernel_logs = "\n".join(_read_kernel_log_candidates(paths.var_log_root()))
    usage_percent = None
    available = None
    if count is not None and maximum:
        usage_percent = round((count / maximum) * 100, 2)
        available = max(maximum - count, 0)
    failure_total = sum(value or 0 for value in (insert_failed, drop, early_drop, error))
    return {
        "count": count,
        "max": maximum,
        "buckets": buckets,
        "hashsize": hashsize,
        "available": available,
        "usage_percent": usage_percent,
        "near_limit": None if usage_percent is None else usage_percent >= 80.0,
        "stats": stats,
        "stats_excerpt": stats_excerpt,
        "insert_failed": insert_failed,
        "drop": drop,
        "early_drop": early_drop,
        "invalid": invalid,
        "error": error,
        "failure_total": failure_total,
        "table_full_detected": _contains_any(
            kernel_logs,
            ["nf_conntrack: table full", "conntrack table full", "nf_conntrack table full"],
        ),
    }


def collect_runtime(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    socket_candidates = _runtime_socket_candidates(paths)
    selected_candidate = _first_existing_runtime_socket(socket_candidates) or socket_candidates[0]
    socket_path = selected_candidate["path"]
    runtime_kind = selected_candidate["kind"]
    pid_file = _runtime_pid_file(socket_path, runtime_kind)
    runtime_pid = _safe_int(_read_first_line(pid_file))
    socket_exists, socket_exists_error = _safe_exists(socket_path)
    socket_stat_error = None
    try:
        socket_is_socket = socket_exists and stat.S_ISSOCK(socket_path.stat().st_mode)
    except OSError as exc:
        socket_is_socket = False
        socket_stat_error = _clean_text(str(exc), limit=500)
    socket_probe = _probe_unix_socket(socket_path) if socket_is_socket else {"ok": False, "error": "socket not available"}
    socket_error = socket_exists_error or socket_stat_error or socket_probe.get("error")
    runtime_pid_running = (paths.proc_root() / str(runtime_pid)).exists() if runtime_pid is not None else None
    candidate_summaries = _runtime_socket_candidate_summaries(socket_candidates)
    result = {
        "runtime_kind": runtime_kind,
        "runtime_name": selected_candidate["name"],
        "runtime_socket_path": str(socket_path),
        "runtime_socket_candidates": candidate_summaries,
        "runtime_socket_exists": socket_exists,
        "runtime_socket_is_socket": socket_is_socket,
        "runtime_socket_healthy": socket_probe["ok"],
        "runtime_socket_latency_ms": socket_probe.get("latency_ms"),
        "runtime_socket_error": socket_error,
        "runtime_socket_permission_denied": _permission_denied(socket_error),
        "runtime_pid_file": str(pid_file),
        "runtime_pid": runtime_pid,
        "runtime_pid_running": runtime_pid_running,
        "containerd_socket_path": str(socket_path) if runtime_kind == "containerd" else None,
        "containerd_socket_candidates": [
            item["path"]
            for item in candidate_summaries
            if item.get("kind") == "containerd"
        ],
        "containerd_socket_exists": socket_exists if runtime_kind == "containerd" else None,
        "containerd_socket_is_socket": socket_is_socket if runtime_kind == "containerd" else None,
        "containerd_socket_healthy": socket_probe["ok"] if runtime_kind == "containerd" else None,
        "containerd_socket_latency_ms": socket_probe.get("latency_ms") if runtime_kind == "containerd" else None,
        "containerd_socket_error": socket_error if runtime_kind == "containerd" else None,
        "containerd_socket_permission_denied": _permission_denied(socket_error) if runtime_kind == "containerd" else None,
        "containerd_pid_file": str(pid_file) if runtime_kind == "containerd" else None,
        "containerd_pid": runtime_pid if runtime_kind == "containerd" else None,
        "containerd_pid_running": runtime_pid_running if runtime_kind == "containerd" else None,
    }
    if socket_is_socket and runtime_kind == "containerd":
        result["ctr_version"] = runner.run(["ctr", "--address", str(socket_path), "version"])
    if socket_is_socket and runtime_kind in {"containerd", "crio", "cri-dockerd"}:
        result["crictl_info"] = runner.run(["crictl", "--runtime-endpoint", f"unix://{socket_path}", "info"])
    return result


def collect_kubelet(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    mode = _systemd_collector_mode()
    if mode == "file":
        status = {}
        journal = _skipped_command_result(
            "journalctl disabled in file mode; DaemonSet reads host log files under /host/var/log"
        )
    else:
        status = _systemctl_show(runner, "kubelet")
        journal = runner.run(["journalctl", "-u", "kubelet", "-n", "80", "--no-pager"])
    host_logs = _host_service_log_excerpts(paths, service_names=["kubelet"])
    return {
        "collection_mode": "file" if mode == "file" else "command",
        "kubelet_status": status.get("ActiveState"),
        "kubelet_sub_state": status.get("SubState"),
        "kubelet_result": status.get("Result"),
        "kubelet_restart_count": _safe_int(status.get("NRestarts")),
        "systemd": status,
        "journal": journal,
        "host_log_files": host_logs,
        "host_log_excerpt": _service_log_excerpt(host_logs, ["kubelet"]),
    }


def collect_cni(paths: AgentPaths) -> dict[str, Any]:
    cni_dirs = _cni_config_dirs(paths)
    configs: list[dict[str, Any]] = []
    plugin_types: list[str] = []
    mtu_values: list[int] = []
    parse_errors: list[dict[str, str]] = []
    access_errors: list[dict[str, str]] = []
    directory_results = []
    for cni_dir in cni_dirs:
        dir_configs: list[dict[str, Any]] = []
        exists, exists_error = _safe_exists(cni_dir)
        if exists_error:
            access_errors.append({"dir": str(cni_dir), "error": exists_error})
        config_paths = []
        if exists:
            try:
                config_paths = sorted(cni_dir.iterdir())
            except OSError as exc:
                access_errors.append({"dir": str(cni_dir), "error": _clean_text(str(exc), limit=500)})
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
                parse_errors.append({"dir": str(cni_dir), "name": path.name, "error": "invalid JSON"})
            plugin_types.extend(_cni_plugin_types(parsed_config))
            mtu_values.extend(_find_numeric_values(parsed_config, key="mtu"))
            config = {
                "dir": str(cni_dir),
                "name": path.name,
                "path": str(path),
                "size_bytes": size_bytes,
                "excerpt": _clean_text(raw_config, limit=4096),
                "parsed": parsed_config is not None,
            }
            dir_configs.append(config)
            configs.append(config)
        directory_results.append(
            {
                "path": str(cni_dir),
                "exists": exists,
                "access_error": exists_error,
                "config_count": len(dir_configs),
            }
        )
    primary_dir = next(
        (cni_dirs[index] for index, item in enumerate(directory_results) if item["exists"]),
        cni_dirs[0],
    )
    return {
        "config_dir": str(primary_dir),
        "config_dirs": [str(path) for path in cni_dirs],
        "config_dir_results": directory_results,
        "config_dir_exists": any(item["exists"] for item in directory_results),
        "config_count": len(configs),
        "plugin_types": _dedupe(plugin_types),
        "mtu": mtu_values[0] if mtu_values else None,
        "mtu_values": mtu_values,
        "parse_errors": parse_errors,
        "access_errors": access_errors,
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
    _cache: dict[str, tuple[float, dict[str, Any]]] = {}
    _cache_lock = threading.Lock()

    def __init__(self, timeout_seconds: float) -> None:
        self.timeout_seconds = timeout_seconds
        self.cache_ttl_seconds = _bounded_float(
            os.getenv("KUBERNETES_API_CACHE_TTL_SECONDS"),
            default=10.0,
            minimum=0.0,
            maximum=30.0,
        )
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
        cached = self._cached(url)
        if cached is not None:
            cached["cache_hit"] = True
            return cached
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.token}"})
        started_at = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds, context=self.context) as response:
                body = response.read(512 * 1024).decode("utf-8", errors="replace")
            result = {
                "ok": True,
                "status_code": response.status,
                "latency_ms": round((time.monotonic() - started_at) * 1000, 2),
                "body": _clean_text(body, limit=20000),
            }
            self._store_cache(url, result)
            return result
        except urllib.error.HTTPError as exc:
            return {
                "ok": False,
                "status_code": exc.code,
                "latency_ms": round((time.monotonic() - started_at) * 1000, 2),
                "error": _clean_text(str(exc), limit=500),
            }
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            error = _clean_text(str(exc), limit=500)
            return {
                "ok": False,
                "status_code": None,
                "latency_ms": round((time.monotonic() - started_at) * 1000, 2),
                "timeout": "timed out" in error.lower() or "timeout" in error.lower(),
                "error": error,
            }

    def _cached(self, key: str) -> dict[str, Any] | None:
        if self.cache_ttl_seconds <= 0:
            return None
        with self._cache_lock:
            cached = self._cache.get(key)
            if cached is None:
                return None
            expires_at, value = cached
            if expires_at <= time.monotonic():
                self._cache.pop(key, None)
                return None
            return copy.deepcopy(value)

    def _store_cache(self, key: str, value: dict[str, Any]) -> None:
        if self.cache_ttl_seconds <= 0:
            return
        with self._cache_lock:
            self._cache[key] = (
                time.monotonic() + self.cache_ttl_seconds,
                copy.deepcopy(value),
            )


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
    cni_pods = []
    cni_non_running_pods = []
    cni_high_restart_pods = []
    non_running_pods = []
    total_restart_count = 0
    cni_restart_count = 0
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
        if _is_cni_pod(metadata):
            cni_pods.append(pod_summary)
            cni_restart_count += restart_count
            if phase != "Running":
                cni_non_running_pods.append(pod_summary)
            if restart_count >= 5:
                cni_high_restart_pods.append(pod_summary)
    return {
        "pod_count_on_node": len(pods),
        "kube_system_pod_count_on_node": kube_system_pod_count,
        "non_running_pods": non_running_pods[:30],
        "pod_restart_count_total": total_restart_count,
        "high_restart_pods": high_restart_pods[:30],
        "cni_pod_count_on_node": len(cni_pods),
        "cni_running_pod_count_on_node": sum(1 for item in cni_pods if item.get("phase") == "Running"),
        "cni_non_running_pods": cni_non_running_pods[:30],
        "cni_restart_count_total": cni_restart_count,
        "cni_high_restart_pods": cni_high_restart_pods[:30],
    }


def _summarize_control_plane_pods(pods: list[dict[str, Any]]) -> dict[str, Any]:
    api_server_pods: list[dict[str, Any]] = []
    api_server_non_running: list[dict[str, Any]] = []
    api_server_high_restart: list[dict[str, Any]] = []
    api_server_restarts = 0
    etcd_pods: list[dict[str, Any]] = []
    etcd_non_running: list[dict[str, Any]] = []
    etcd_high_restart: list[dict[str, Any]] = []
    etcd_restarts = 0

    for pod in pods:
        metadata = _dict_value(pod.get("metadata"))
        status = _dict_value(pod.get("status"))
        spec = _dict_value(pod.get("spec"))
        namespace = str(metadata.get("namespace") or "")
        name = str(metadata.get("name") or "")
        phase = str(status.get("phase") or "")
        restart_count = _pod_restart_count(status)
        pod_summary = {
            "namespace": namespace,
            "name": name,
            "phase": phase,
            "restart_count": restart_count,
            "node_name": spec.get("nodeName"),
        }
        if _is_api_server_pod(metadata):
            api_server_pods.append(pod_summary)
            api_server_restarts += restart_count
            if phase != "Running":
                api_server_non_running.append(pod_summary)
            if restart_count >= 5:
                api_server_high_restart.append(pod_summary)
        if _is_etcd_pod(metadata):
            etcd_pods.append(pod_summary)
            etcd_restarts += restart_count
            if phase != "Running":
                etcd_non_running.append(pod_summary)
            if restart_count >= 5:
                etcd_high_restart.append(pod_summary)

    return {
        "api_server_pod_count_on_node": len(api_server_pods),
        "api_server_non_running_pods": api_server_non_running[:20],
        "api_server_restart_count_total": api_server_restarts,
        "api_server_high_restart_pods": api_server_high_restart[:20],
        "etcd_pod_count_on_node": len(etcd_pods),
        "etcd_non_running_pods": etcd_non_running[:20],
        "etcd_restart_count_total": etcd_restarts,
        "etcd_high_restart_pods": etcd_high_restart[:20],
    }


def _summarize_cni_daemonsets(daemonsets: list[dict[str, Any]]) -> dict[str, Any]:
    summaries = []
    unavailable = []
    not_scheduled = []
    for daemonset in daemonsets:
        metadata = _dict_value(daemonset.get("metadata"))
        if not _is_cni_pod(metadata):
            continue
        status = _dict_value(daemonset.get("status"))
        desired = _safe_int(status.get("desiredNumberScheduled")) or 0
        current = _safe_int(status.get("currentNumberScheduled")) or 0
        ready = _safe_int(status.get("numberReady")) or 0
        available = _safe_int(status.get("numberAvailable")) or 0
        item = {
            "namespace": str(metadata.get("namespace") or "kube-system"),
            "name": str(metadata.get("name") or ""),
            "desired_number_scheduled": desired,
            "current_number_scheduled": current,
            "number_ready": ready,
            "number_available": available,
            "updated_number_scheduled": _safe_int(status.get("updatedNumberScheduled")),
            "number_misscheduled": _safe_int(status.get("numberMisscheduled")),
        }
        summaries.append(item)
        if desired == 0:
            not_scheduled.append(item)
        elif ready < desired or available < desired:
            unavailable.append(item)
    return {
        "cni_daemonsets": summaries[:20],
        "cni_daemonset_count": len(summaries),
        "cni_daemonsets_unavailable": unavailable[:20],
        "cni_daemonset_unavailable_count": len(unavailable),
        "cni_daemonsets_not_scheduled": not_scheduled[:20],
        "cni_daemonset_not_scheduled_count": len(not_scheduled),
    }


def _summarize_coredns_pods(pods: list[dict[str, Any]]) -> dict[str, Any]:
    non_running_pods = []
    running_count = 0
    restart_count = 0
    for pod in pods:
        metadata = _dict_value(pod.get("metadata"))
        status = _dict_value(pod.get("status"))
        spec = _dict_value(pod.get("spec"))
        phase = str(status.get("phase") or "")
        if phase == "Running":
            running_count += 1
        if phase not in {"Running", "Succeeded"}:
            non_running_pods.append(
                {
                    "namespace": str(metadata.get("namespace") or ""),
                    "name": str(metadata.get("name") or ""),
                    "phase": phase,
                    "node_name": spec.get("nodeName"),
                    "restart_count": _pod_restart_count(status),
                }
            )
        restart_count += _pod_restart_count(status)
    return {
        "coredns_pod_count": len(pods),
        "coredns_running_pod_count": running_count,
        "coredns_non_running_pods": non_running_pods[:20],
        "coredns_restart_count_total": restart_count,
    }


def _summarize_coredns_endpoint_slices(endpoint_slices: list[dict[str, Any]]) -> dict[str, Any]:
    endpoint_count = 0
    ready_endpoint_count = 0
    not_ready_endpoint_count = 0
    for endpoint_slice in endpoint_slices:
        for endpoint in _safe_json_list(endpoint_slice.get("endpoints")):
            endpoint_count += 1
            conditions = _dict_value(endpoint.get("conditions"))
            ready = conditions.get("ready")
            if ready is False or str(ready).lower() == "false":
                not_ready_endpoint_count += 1
            else:
                ready_endpoint_count += 1
    return {
        "coredns_endpoint_slice_count": len(endpoint_slices),
        "coredns_service_observed": bool(endpoint_slices),
        "coredns_endpoint_count": endpoint_count,
        "coredns_ready_endpoint_count": ready_endpoint_count,
        "coredns_not_ready_endpoint_count": not_ready_endpoint_count,
        "coredns_has_ready_endpoints": bool(ready_endpoint_count),
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


def _topology_collector_node(nodes: list[dict[str, Any]]) -> str | None:
    candidates = []
    for node in nodes:
        metadata = _dict_value(node.get("metadata"))
        name = str(metadata.get("name") or "").strip()
        if not name:
            continue
        candidates.append((0 if _is_control_plane_node(metadata) else 1, name))
    return min(candidates)[1] if candidates else None


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
        for token in ("cilium", "calico", "flannel", "kindnet", "weave", "antrea", "canal")
    )


def _is_api_server_pod(metadata: dict[str, Any]) -> bool:
    namespace = str(metadata.get("namespace") or "").lower()
    name = str(metadata.get("name") or "").lower()
    labels = _dict_value(metadata.get("labels"))
    label_text = " ".join(f"{key}={value}".lower() for key, value in labels.items())
    combined = f"{name} {label_text}"
    return namespace == "kube-system" and (
        name.startswith("kube-apiserver")
        or "component=kube-apiserver" in combined
        or "k8s-app=kube-apiserver" in combined
    )


def _is_etcd_pod(metadata: dict[str, Any]) -> bool:
    namespace = str(metadata.get("namespace") or "").lower()
    name = str(metadata.get("name") or "").lower()
    labels = _dict_value(metadata.get("labels"))
    label_text = " ".join(f"{key}={value}".lower() for key, value in labels.items())
    combined = f"{name} {label_text}"
    return namespace == "kube-system" and (
        name.startswith("etcd")
        or "component=etcd" in combined
        or "k8s-app=etcd" in combined
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
    return [item["message"] for item in _readyz_failures(_parse_readyz_checks(text))]


def _parse_readyz_checks(text: str) -> list[dict[str, Any]]:
    checks = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith(("[+]", "[-]")):
            continue
        healthy = stripped.startswith("[+]")
        rest = stripped[3:].strip()
        check_name = rest
        detail = ""
        if ":" in rest:
            check_name, detail = rest.split(":", 1)
            detail = detail.strip()
        elif rest.endswith(" ok"):
            check_name = rest[:-3].strip()
            detail = "ok"
        elif rest.endswith(" failed"):
            check_name = rest[:-7].strip()
            detail = "failed"
        checks.append(
            {
                "check": _clean_text(check_name, limit=120),
                "healthy": healthy,
                "status": "ok" if healthy else "failed",
                "message": _clean_text(stripped, limit=500),
                "detail": _clean_text(detail, limit=500),
            }
        )
    return checks[:100]


def _readyz_failures(checks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [item for item in checks if item.get("healthy") is False][:50]


def _summarize_etcd_readyz(checks: list[dict[str, Any]]) -> dict[str, Any]:
    for item in checks:
        if str(item.get("check") or "").strip().lower() != "etcd":
            continue
        return {
            "etcd_readyz_healthy": item.get("healthy") is True,
            "etcd_readyz_message": item.get("message"),
        }
    return {"etcd_readyz_healthy": None, "etcd_readyz_message": None}


def _summarize_api_requests(base: dict[str, Any], responses: list[tuple[str, dict[str, Any]]]) -> None:
    latencies = []
    errors = 0
    timeout_detected = False
    max_latency: float | None = None
    for operation, response in responses:
        if not isinstance(response, dict):
            continue
        latency = _safe_float(response.get("latency_ms"))
        item = {
            "operation": operation,
            "ok": response.get("ok") is True,
            "status_code": response.get("status_code"),
        }
        if latency is not None:
            item["latency_ms"] = latency
            max_latency = latency if max_latency is None else max(max_latency, latency)
        if response.get("ok") is not True:
            errors += 1
            error = _clean_text(str(response.get("error") or ""), limit=300)
            item["error"] = error
            if response.get("timeout") is True or "timed out" in error.lower() or "timeout" in error.lower():
                timeout_detected = True
        latencies.append(item)
    base["api_request_latencies"] = latencies[:50]
    base["api_request_error_count"] = errors
    base["api_timeout_detected"] = timeout_detected
    base["api_server_latency_ms"] = max_latency


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


def _systemd_collector_mode() -> str:
    mode = os.getenv("SYSTEMD_COLLECTOR_MODE", "auto").strip().lower()
    return mode if mode in {"auto", "command", "file"} else "auto"


def _collect_systemd_from_host_files(paths: AgentPaths) -> dict[str, Any]:
    unit_files = _systemd_unit_file_summaries(paths)
    units = {item["name"]: item for item in unit_files}
    runtime_unit_names = {"containerd", "crio", "docker", "cri-docker", "microk8s.daemon-containerd"}
    distribution_unit_names = {
        "rke2-server",
        "rke2-agent",
        "k3s",
        "k3s-agent",
        "k0scontroller",
        "k0sworker",
        "microk8s.daemon-kubelet",
    }
    host_logs = _host_service_log_excerpts(paths)
    return {
        "collection_mode": "file",
        "systemctl_skipped": True,
        "systemctl_skip_reason": "SYSTEMD_COLLECTOR_MODE=file; DaemonSet cannot reliably query host systemd over DBus.",
        "kubelet_status": None,
        "kubelet_sub_state": None,
        "kubelet_result": None,
        "kubelet_restart_count": None,
        "containerd_status": None,
        "containerd_sub_state": None,
        "containerd_result": None,
        "containerd_restart_count": None,
        "rke2_server_status": None,
        "rke2_server_sub_state": None,
        "rke2_server_result": None,
        "rke2_server_restart_count": None,
        "rke2_agent_status": None,
        "rke2_agent_sub_state": None,
        "rke2_agent_result": None,
        "rke2_agent_restart_count": None,
        "runtime_units": [item for item in unit_files if item["name"] in runtime_unit_names],
        "distribution_units": [item for item in unit_files if item["name"] in distribution_unit_names],
        "active_runtime_units": [],
        "active_distribution_units": [],
        "embedded_kubelet_running": None,
        "embedded_runtime_running": None,
        "runtime_process_sample": [],
        "rke2_embedded_kubelet_running": None,
        "rke2_embedded_containerd_running": None,
        "rke2_process_sample": [],
        "failed_units": [],
        "failed_units_command": _skipped_command_result("systemctl disabled in file mode"),
        "units": units,
        "unit_files": unit_files,
        "host_log_files": host_logs,
        "kubelet_log_excerpt": _service_log_excerpt(host_logs, ["kubelet"]),
        "runtime_log_excerpt": _service_log_excerpt(
            host_logs,
            ["containerd", "crio", "cri-o", "cri-dockerd", "dockerd"],
        ),
    }


def _systemd_unit_file_summaries(paths: AgentPaths) -> list[dict[str, Any]]:
    unit_names = [
        "kubelet",
        "containerd",
        "crio",
        "docker",
        "cri-docker",
        "rke2-server",
        "rke2-agent",
        "k3s",
        "k3s-agent",
        "k0scontroller",
        "k0sworker",
        "microk8s.daemon-kubelet",
        "microk8s.daemon-containerd",
    ]
    roots = _systemd_unit_file_roots(paths)
    summaries: list[dict[str, Any]] = []
    for unit_name in unit_names:
        file_name = unit_name if unit_name.endswith(".service") else f"{unit_name}.service"
        matches: list[str] = []
        for root in roots:
            candidate = root / file_name
            exists, _error = _safe_exists(candidate)
            if exists:
                matches.append(str(candidate))
        summaries.append(
            {
                "name": unit_name,
                "unit": file_name,
                "unit_file_present": bool(matches),
                "paths": matches[:5],
                "status": None,
                "sub_state": None,
            }
        )
    return summaries


def _systemd_unit_file_roots(paths: AgentPaths) -> list[Path]:
    roots = [
        paths.etc_root() / "systemd/system",
    ]
    host_root = paths.host_root()
    if host_root is not None:
        roots.extend(
            [
                host_root / "etc/systemd/system",
                host_root / "usr/lib/systemd/system",
                host_root / "lib/systemd/system",
            ]
        )
    return _dedupe_paths(roots)


def _host_service_log_excerpts(paths: AgentPaths, service_names: list[str] | None = None) -> list[dict[str, Any]]:
    var_log = paths.var_log_root()
    candidates = _host_log_candidate_paths(var_log, service_names)
    excerpts: list[dict[str, Any]] = []
    filters = [item.lower() for item in (service_names or [
        "kubelet",
        "containerd",
        "crio",
        "cri-o",
        "cri-dockerd",
        "dockerd",
        "rke2",
        "k3s",
        "k0s",
        "microk8s",
        "kernel",
        "oom",
        "blocked",
        "error",
        "failed",
    ])]
    max_files = _bounded_int(
        os.getenv("HOST_LOG_MAX_FILES"),
        default=12,
        minimum=1,
        maximum=50,
    )
    max_bytes = _bounded_int(
        os.getenv("HOST_LOG_MAX_BYTES_PER_FILE"),
        default=262144,
        minimum=4096,
        maximum=2 * 1024 * 1024,
    )
    max_lines = _bounded_int(
        os.getenv("HOST_LOG_MAX_LINES"),
        default=80,
        minimum=10,
        maximum=500,
    )
    for path in candidates[:max_files]:
        exists, exists_error = _safe_exists(path)
        if not exists:
            if exists_error:
                excerpts.append({"path": str(path), "status": "error", "error": exists_error})
            continue
        text = _read_text(path, max_bytes=max_bytes)
        if not text:
            continue
        lines = text.splitlines()
        matching = [
            _clean_text(line, limit=1000)
            for line in lines
            if any(token in line.lower() for token in filters)
        ]
        selected_lines = (
            matching[-max_lines:]
            if matching
            else [_clean_text(line, limit=1000) for line in lines[-max(10, max_lines // 2):]]
        )
        excerpts.append(
            {
                "path": str(path),
                "status": "ok",
                "line_count_sampled": len(lines),
                "matched_line_count": len(matching),
                "excerpt": selected_lines,
            }
        )
    return excerpts


def _host_log_candidate_paths(var_log: Path, service_names: list[str] | None) -> list[Path]:
    common_names = [
        "syslog",
        "messages",
        "daemon.log",
        "kern.log",
        "kubelet.log",
        "containerd.log",
        "crio.log",
        "docker.log",
        "rke2/rke2.log",
        "pods",
    ]
    if service_names:
        common_names.extend(f"{service_name}.log" for service_name in service_names)
    candidates = [var_log / name for name in common_names]
    try:
        candidates.extend(
            path
            for path in var_log.iterdir()
            if path.is_file()
            and any(token in path.name.lower() for token in ["syslog", "message", "daemon", "kern", "kubelet", "container", "crio", "docker", "rke2", "k3s"])
        )
    except OSError:
        pass
    return _dedupe_paths(candidates)


def _service_log_excerpt(logs: list[dict[str, Any]], keywords: list[str]) -> list[str]:
    normalized_keywords = [keyword.lower() for keyword in keywords]
    lines: list[str] = []
    for item in logs:
        excerpt = item.get("excerpt")
        if not isinstance(excerpt, list):
            continue
        for line in excerpt:
            text = str(line)
            if any(keyword in text.lower() for keyword in normalized_keywords):
                lines.append(text)
    return lines[-80:]


def _skipped_command_result(reason: str) -> dict[str, Any]:
    return {
        "ok": False,
        "skipped": True,
        "exit_code": None,
        "stdout": "",
        "stderr": reason,
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


def _matching_processes(runner: CommandRunner, keywords: list[str]) -> list[dict[str, str]]:
    targeted_pattern = "rke2 server|k3s server|k3s agent|k0s|microk8s|containerd|crio|cri-dockerd|dockerd|kubelet"
    targeted = runner.run(["pgrep", "-af", targeted_pattern])
    if targeted.get("ok") and targeted.get("stdout"):
        matches = _parse_pgrep_processes(str(targeted.get("stdout") or ""))
        if matches:
            return matches

    result = runner.run(["ps", "-eo", "pid=,comm=,args="])
    if not result.get("ok"):
        return []
    lowered_keywords = [keyword.lower() for keyword in keywords]
    matches = []
    for line in str(result.get("stdout") or "").splitlines():
        cleaned = line.strip()
        if not cleaned:
            continue
        lowered = cleaned.lower()
        if not any(keyword in lowered for keyword in lowered_keywords):
            continue
        fields = cleaned.split(None, 2)
        if len(fields) < 2:
            continue
        matches.append(
            {
                "pid": fields[0],
                "command": fields[1],
                "args": _clean_text(fields[2] if len(fields) > 2 else fields[1], limit=500),
            }
        )
    return matches


def _parse_pgrep_processes(output: str) -> list[dict[str, str]]:
    matches = []
    for line in output.splitlines():
        cleaned = line.strip()
        if not cleaned or "pgrep -af" in cleaned:
            continue
        fields = cleaned.split(None, 1)
        if len(fields) != 2:
            continue
        args = fields[1]
        command = Path(args.split()[0]).name if args.split() else ""
        matches.append({"pid": fields[0], "command": command, "args": _clean_text(args, limit=500)})
    return matches


def _process_sample_contains(processes: list[dict[str, str]], keyword: str) -> bool:
    lowered_keyword = keyword.lower()
    return any(lowered_keyword in str(item.get("args") or item.get("command") or "").lower() for item in processes)


def _process_sample_contains_any(processes: list[dict[str, str]], keywords: list[str]) -> bool:
    return any(_process_sample_contains(processes, keyword) for keyword in keywords)


def _unit_summaries(units: dict[str, dict[str, Any]], names: list[str]) -> list[dict[str, Any]]:
    summaries = []
    for name in names:
        unit = units.get(name, {})
        summaries.append(
            {
                "name": name,
                "status": unit.get("ActiveState"),
                "sub_state": unit.get("SubState"),
                "result": unit.get("Result"),
                "restart_count": _safe_int(unit.get("NRestarts")),
            }
        )
    return summaries


def _unit_summary_healthy(unit: dict[str, Any]) -> bool:
    status = str(unit.get("status") or "").lower()
    sub_state = str(unit.get("sub_state") or "").lower()
    return status == "active" and sub_state in {"running", "exited", ""}


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
            "device_path_exists": (interface_dir / "device").exists(),
        }
    return states


def _interface_kind(name: str, interface: dict[str, Any]) -> str:
    lowered = name.lower()
    if lowered == "lo":
        return "loopback"
    virtual_prefixes = (
        "veth",
        "cni",
        "flannel",
        "cilium",
        "lxc",
        "docker",
        "br-",
        "virbr",
        "kube-ipvs",
        "kube-bridge",
        "cali",
        "tunl",
        "ip6tnl",
        "vxlan",
        "genev",
        "dummy",
        "tailscale",
        "wg",
        "zt",
        "tap",
        "tun",
    )
    if lowered.startswith(virtual_prefixes):
        return "virtual"
    if interface.get("device_path_exists") is True:
        return "physical"
    if re.match(r"^(eth|en[ospx]?\d|eno|ens|enp|bond|team|ib|wl|ww)", lowered):
        return "physical_candidate"
    return "unknown"


def _is_physical_interface(interface: dict[str, Any]) -> bool:
    return interface.get("interface_kind") in {"physical", "physical_candidate"}


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


def _runtime_socket_candidates(paths: AgentPaths) -> list[dict[str, Any]]:
    env_candidates = _runtime_socket_env_candidates(paths)
    if env_candidates:
        return env_candidates

    raw_candidates = [
        ("containerd", "containerd", "/run/containerd/containerd.sock"),
        ("containerd", "containerd-rke2-k3s", "/run/k3s/containerd/containerd.sock"),
        ("containerd", "containerd-rke2", "/run/rke2/containerd/containerd.sock"),
        ("containerd", "containerd-k0s", "/run/k0s/containerd.sock"),
        ("containerd", "containerd-k0s-var", "/var/lib/k0s/run/containerd.sock"),
        ("containerd", "containerd-microk8s", "/var/snap/microk8s/common/run/containerd.sock"),
        ("crio", "crio", "/run/crio/crio.sock"),
        ("crio", "crio-var-run", "/var/run/crio/crio.sock"),
        ("cri-dockerd", "cri-dockerd", "/run/cri-dockerd.sock"),
        ("cri-dockerd", "cri-dockerd-var-run", "/var/run/cri-dockerd.sock"),
        ("docker", "docker", "/run/docker.sock"),
        ("docker", "docker-var-run", "/var/run/docker.sock"),
    ]
    return _dedupe_runtime_candidates(
        [
            {"kind": kind, "name": name, "path": _resolve_host_path(paths, raw_path)}
            for kind, name, raw_path in raw_candidates
        ]
    )


def _runtime_socket_env_candidates(paths: AgentPaths) -> list[dict[str, Any]]:
    raw_value = (
        os.getenv("CONTAINER_RUNTIME_SOCKET_PATHS")
        or os.getenv("RUNTIME_SOCKET_PATHS")
        or os.getenv("CONTAINERD_SOCKET_PATH")
    )
    if not raw_value:
        return []
    candidates = []
    for raw_entry in re.split(r"[,;]", raw_value):
        entry = raw_entry.strip()
        if not entry:
            continue
        raw_kind = None
        raw_path = entry
        if "=" in entry:
            raw_kind, raw_path = entry.split("=", 1)
        elif ":" in entry and not Path(entry).is_absolute():
            raw_kind, raw_path = entry.split(":", 1)
        kind = _normalize_runtime_kind(raw_kind or _infer_runtime_kind(raw_path))
        candidates.append(
            {
                "kind": kind,
                "name": kind,
                "path": _resolve_host_path(paths, raw_path.strip()),
            }
        )
    return _dedupe_runtime_candidates(candidates)


def _dedupe_runtime_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped = []
    seen = set()
    for candidate in candidates:
        key = (candidate.get("kind"), str(candidate.get("path")))
        if key in seen:
            continue
        seen.add(key)
        deduped.append(candidate)
    return deduped


def _infer_runtime_kind(path: str) -> str:
    lowered = path.lower()
    if "crio" in lowered:
        return "crio"
    if "cri-dockerd" in lowered or "cri_dockerd" in lowered:
        return "cri-dockerd"
    if "docker.sock" in lowered:
        return "docker"
    if "containerd" in lowered:
        return "containerd"
    return "unknown"


def _normalize_runtime_kind(value: str) -> str:
    lowered = value.strip().lower()
    if lowered in {"containerd", "crio", "cri-dockerd", "docker"}:
        return lowered
    if lowered in {"cri-o", "cri_o"}:
        return "crio"
    if lowered in {"cri-docker", "cridockerd"}:
        return "cri-dockerd"
    return "unknown"


def _runtime_pid_file(socket_path: Path, runtime_kind: str) -> Path:
    pid_names = {
        "containerd": "containerd.pid",
        "crio": "crio.pid",
        "cri-dockerd": "cri-dockerd.pid",
        "docker": "docker.pid",
    }
    return socket_path.with_name(pid_names.get(runtime_kind, f"{socket_path.stem}.pid"))


def _runtime_socket_candidate_summaries(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    summaries = []
    for candidate in candidates:
        path = candidate["path"]
        exists, exists_error = _safe_exists(path)
        is_socket = False
        stat_error = None
        if exists:
            try:
                is_socket = stat.S_ISSOCK(path.stat().st_mode)
            except OSError as exc:
                stat_error = _clean_text(str(exc), limit=500)
        summaries.append(
            {
                "kind": candidate.get("kind"),
                "name": candidate.get("name"),
                "path": str(path),
                "exists": exists,
                "is_socket": is_socket,
                "error": exists_error or stat_error,
            }
        )
    return summaries


def _cni_config_dirs(paths: AgentPaths) -> list[Path]:
    raw_dirs = os.getenv("CNI_CONFIG_DIRS")
    if raw_dirs:
        return _dedupe_paths(
            [
                _resolve_host_path(paths, raw_dir.strip())
                for raw_dir in re.split(r"[,;:]", raw_dirs)
                if raw_dir.strip()
            ]
        )

    host_root = paths.host_root()
    candidates = [
        paths.etc_root() / "cni/net.d",
    ]
    if host_root is not None:
        candidates.extend(
            [
                host_root / "var/lib/rancher/rke2/agent/etc/cni/net.d",
                host_root / "var/lib/rancher/k3s/agent/etc/cni/net.d",
                host_root / "var/lib/k0s/etc/cni/net.d",
                host_root / "var/lib/k0s/kubelet-plugins/net.d",
                host_root / "var/snap/microk8s/current/args/cni-network",
                host_root / "var/snap/microk8s/common/etc/cni/net.d",
            ]
        )
    else:
        candidates.extend(
            [
                paths.root / "var/lib/rancher/rke2/agent/etc/cni/net.d",
                paths.root / "var/lib/rancher/k3s/agent/etc/cni/net.d",
                paths.root / "var/lib/k0s/etc/cni/net.d",
                paths.root / "var/lib/k0s/kubelet-plugins/net.d",
                paths.root / "var/snap/microk8s/current/args/cni-network",
                paths.root / "var/snap/microk8s/common/etc/cni/net.d",
            ]
        )
    return _dedupe_paths(candidates)


def _resolve_host_path(paths: AgentPaths, raw_path: str) -> Path:
    normalized = raw_path.strip()
    if normalized.startswith("/var/run/"):
        return paths.run / normalized.removeprefix("/var/run/")
    if normalized == "/var/run":
        return paths.run
    if normalized.startswith("/run/"):
        return paths.run / normalized.removeprefix("/run/")
    if normalized == "/run":
        return paths.run
    if normalized.startswith("/etc/"):
        return paths.etc_root() / normalized.removeprefix("/etc/")
    if normalized == "/etc":
        return paths.etc_root()
    path = Path(raw_path)
    if not path.is_absolute():
        return path
    try:
        return paths.run / path.relative_to("/var/run")
    except ValueError:
        pass
    try:
        return paths.etc_root() / path.relative_to("/etc")
    except ValueError:
        pass
    try:
        return paths.run / path.relative_to("/run")
    except ValueError:
        pass
    host_root = paths.host_root()
    return (host_root or paths.root) / path.relative_to("/")


def _dedupe_paths(paths: list[Path]) -> list[Path]:
    deduped: list[Path] = []
    seen = set()
    for path in paths:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(path)
    return deduped


def _first_existing_runtime_socket(candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
    for candidate in candidates:
        path = candidate["path"]
        try:
            if path.exists() and stat.S_ISSOCK(path.stat().st_mode):
                return candidate
        except OSError:
            continue
    return None


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


def _parse_nf_conntrack_stats(path: Path) -> tuple[dict[str, int], list[str]]:
    text = _read_text(path, max_bytes=65536)
    lines = [line.split() for line in text.splitlines() if line.strip()]
    if len(lines) < 2:
        return {}, _last_lines(text, 20)
    headers = lines[0]
    totals: dict[str, int] = {}
    for row in lines[1:]:
        for index, name in enumerate(headers[: len(row)]):
            value = _parse_conntrack_stat_number(row[index])
            if value is None:
                continue
            if name == "entries":
                totals[name] = max(totals.get(name, 0), value)
            else:
                totals[name] = totals.get(name, 0) + value
    return totals, _last_lines(text, 20)


def _parse_conntrack_stat_number(value: str) -> int | None:
    try:
        return int(value, 16)
    except ValueError:
        try:
            return int(value)
        except ValueError:
            return None


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


def _permission_denied(value: object) -> bool:
    return "permission denied" in str(value or "").lower() or "errno 13" in str(value or "").lower()


def _safe_exists(path: Path) -> tuple[bool, str | None]:
    try:
        return path.exists(), None
    except OSError as exc:
        return False, _clean_text(str(exc), limit=500)


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


def _bounded_int(value: object, default: int, minimum: int, maximum: int) -> int:
    parsed = _safe_int(value)
    if parsed is None:
        return default
    return min(max(parsed, minimum), maximum)


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _per_hour(value: object, uptime_seconds: object) -> float | None:
    parsed_value = _safe_float(value)
    parsed_uptime = _safe_float(uptime_seconds)
    if parsed_value is None or parsed_uptime is None or parsed_uptime <= 0:
        return None
    return round(parsed_value / (parsed_uptime / 3600), 2)


def _dedupe(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result
