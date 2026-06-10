from __future__ import annotations

import os
import platform
import re
import shutil
import socket
import stat
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any


Collector = Callable[[], dict[str, Any]]

DEFAULT_COLLECTORS = [
    "node",
    "systemd",
    "kernel",
    "disk",
    "memory",
    "network",
    "runtime",
]

SENSITIVE_PATTERNS = [
    re.compile(r"(?i)(token|password|passwd|secret|authorization|api[_-]?key)\s*[:=]\s*([^\s,;]+)"),
    re.compile(r"(?i)(bearer)\s+([a-z0-9._~+/-]+)"),
]


@dataclass(frozen=True)
class AgentPaths:
    proc: Path = Path("/host/proc")
    sys: Path = Path("/host/sys")
    etc: Path = Path("/host/etc")
    var_log: Path = Path("/host/var/log")
    run: Path = Path("/host/run")

    @classmethod
    def from_env(cls) -> "AgentPaths":
        return cls(
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
    return {
        "host_name": _read_first_line(proc / "sys/kernel/hostname") or socket.gethostname(),
        "agent_node_name": os.getenv("NODE_NAME"),
        "kernel_version": platform.release(),
        "platform": platform.platform(),
        "os_release": _parse_os_release(etc / "os-release"),
        "uptime_seconds": _parse_first_float(proc / "uptime"),
        "load_average": _read_first_line(proc / "loadavg"),
    }


def collect_systemd(runner: CommandRunner) -> dict[str, Any]:
    units = {
        "kubelet": _systemctl_show(runner, "kubelet"),
        "containerd": _systemctl_show(runner, "containerd"),
    }
    kubelet = units["kubelet"]
    containerd = units["containerd"]
    return {
        "kubelet_status": kubelet.get("ActiveState"),
        "kubelet_restart_count": _safe_int(kubelet.get("NRestarts")),
        "containerd_status": containerd.get("ActiveState"),
        "containerd_restart_count": _safe_int(containerd.get("NRestarts")),
        "units": units,
    }


def collect_kernel(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    dmesg = runner.run(["dmesg", "--ctime", "--level=err,warn"])
    logs = _read_kernel_log_candidates(paths.var_log_root())
    combined = "\n".join([dmesg.get("stdout", ""), *logs])
    return {
        "dmesg": dmesg,
        "kernel_log_excerpt": _last_lines(combined, 80),
        "io_error_detected": _contains_any(combined, ["I/O error", "blk_update_request", "EXT4-fs error"]),
        "nic_error_detected": _contains_any(combined, ["link is down", "link down", "NIC", "tx timeout"]),
        "oom_detected": _contains_any(combined, ["Out of memory", "oom-killer", "Killed process"]),
    }


def collect_disk(paths: AgentPaths) -> dict[str, Any]:
    candidates = [paths.var_log_root(), paths.etc_root(), Path("/")]
    filesystems = [_filesystem_usage(path) for path in candidates if path.exists()]
    root_usage = filesystems[0]["usage_percent"] if filesystems else None
    inode_usage = filesystems[0]["inode_usage_percent"] if filesystems else None
    return {
        "root_usage_percent": root_usage,
        "inode_usage_percent": inode_usage,
        "filesystems": filesystems,
        "mounts_excerpt": _last_lines(_read_text(paths.proc_root() / "mounts", max_bytes=32768), 40),
        "diskstats_excerpt": _last_lines(_read_text(paths.proc_root() / "diskstats", max_bytes=32768), 40),
    }


def collect_inode(paths: AgentPaths) -> dict[str, Any]:
    paths_to_check = [paths.var_log_root(), paths.etc_root(), Path("/")]
    return {
        "filesystems": [
            {
                "path": str(path),
                "inode_usage_percent": _inode_usage_percent(path),
            }
            for path in paths_to_check
            if path.exists()
        ]
    }


def collect_memory(paths: AgentPaths) -> dict[str, Any]:
    values = _parse_key_value_file(paths.proc_root() / "meminfo")
    total = values.get("MemTotal")
    available = values.get("MemAvailable") or values.get("MemFree")
    usage_percent = None
    if total and available is not None:
        usage_percent = round(((total - available) / total) * 100, 2)
    return {
        "usage_percent": usage_percent,
        "mem_total_kib": total,
        "mem_available_kib": available,
        "swap_total_kib": values.get("SwapTotal"),
        "swap_free_kib": values.get("SwapFree"),
    }


def collect_process(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    process_count = sum(1 for item in proc.iterdir() if item.name.isdigit()) if proc.exists() else None
    pid_max = _safe_int(_read_first_line(proc / "sys/kernel/pid_max"))
    usage_percent = None
    if process_count is not None and pid_max:
        usage_percent = round((process_count / pid_max) * 100, 4)
    return {
        "process_count": process_count,
        "pid_max": pid_max,
        "pid_usage_percent": usage_percent,
    }


def collect_network(paths: AgentPaths) -> dict[str, Any]:
    interfaces = _parse_net_dev(paths.proc_root() / "net/dev")
    conntrack = collect_conntrack(paths)
    return {
        "interfaces": interfaces,
        "routes_excerpt": _last_lines(_read_text(paths.proc_root() / "net/route", max_bytes=32768), 30),
        "tcp_snmp_excerpt": _last_lines(_read_text(paths.proc_root() / "net/snmp", max_bytes=32768), 40),
        "conntrack_usage_percent": conntrack.get("usage_percent"),
        "conntrack": conntrack,
    }


def collect_conntrack(paths: AgentPaths) -> dict[str, Any]:
    proc = paths.proc_root()
    count = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_count"))
    maximum = _safe_int(_read_first_line(proc / "sys/net/netfilter/nf_conntrack_max"))
    usage_percent = None
    if count is not None and maximum:
        usage_percent = round((count / maximum) * 100, 2)
    return {
        "count": count,
        "max": maximum,
        "usage_percent": usage_percent,
    }


def collect_runtime(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    socket_path = paths.run / "containerd/containerd.sock"
    socket_exists = socket_path.exists()
    socket_is_socket = socket_exists and stat.S_ISSOCK(socket_path.stat().st_mode)
    result = {
        "containerd_socket_path": str(socket_path),
        "containerd_socket_exists": socket_exists,
        "containerd_socket_healthy": socket_is_socket,
    }
    if socket_exists:
        result["ctr_version"] = runner.run(["ctr", "--address", str(socket_path), "version"])
    return result


def collect_kubelet(runner: CommandRunner) -> dict[str, Any]:
    status = _systemctl_show(runner, "kubelet")
    journal = runner.run(["journalctl", "-u", "kubelet", "-n", "80", "--no-pager"])
    return {
        "status": status.get("ActiveState"),
        "restart_count": _safe_int(status.get("NRestarts")),
        "systemd": status,
        "journal": journal,
    }


def collect_cni(paths: AgentPaths) -> dict[str, Any]:
    cni_dir = paths.etc_root() / "cni/net.d"
    if not cni_dir.exists():
        return {
            "config_dir": str(cni_dir),
            "config_dir_exists": False,
            "configs": [],
        }
    configs = []
    for path in sorted(cni_dir.iterdir()):
        if not path.is_file():
            continue
        configs.append(
            {
                "name": path.name,
                "size_bytes": path.stat().st_size,
                "excerpt": _clean_text(_read_text(path, max_bytes=4096)),
            }
        )
    return {
        "config_dir": str(cni_dir),
        "config_dir_exists": True,
        "configs": configs,
    }


def collect_dns(paths: AgentPaths) -> dict[str, Any]:
    resolv_conf = _read_text(paths.etc_root() / "resolv.conf", max_bytes=8192)
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
    return {
        "resolv_conf_excerpt": _clean_text(resolv_conf),
        "nameservers": nameservers,
        "search": search,
        "options": options,
    }


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


def _parse_systemctl_show(output: str) -> dict[str, str]:
    parsed = {}
    for line in output.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed[key] = value
    return parsed


def _filesystem_usage(path: Path) -> dict[str, Any]:
    usage = shutil.disk_usage(path)
    return {
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
    except OSError:
        return None
    total = values.f_files
    free = values.f_ffree
    if not total:
        return None
    return round(((total - free) / total) * 100, 2)


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


def _dedupe(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result
