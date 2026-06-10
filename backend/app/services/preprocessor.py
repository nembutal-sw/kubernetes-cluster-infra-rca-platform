from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from typing import Any

from backend.app.models import EvidenceBundle


MAX_LOG_EVENTS = 500
MAX_LOG_CLUSTERS = 30
MAX_SAMPLE_LINES = 3
MAX_SAMPLE_LINE_LENGTH = 500

NOISE_KEYS = {
    "user_agent",
    "user-agent",
    "http_user_agent",
    "ua",
    "browser",
    "browser_version",
    "os_version",
    "device",
    "device_type",
}

LOG_PATH_HINTS = {
    "log",
    "logs",
    "journal",
    "dmesg",
    "stderr",
    "stdout",
    "message",
    "messages",
    "access_log",
    "access_logs",
    "web_log",
    "web_logs",
}

IP_RE = re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b")
UUID_RE = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I)
TIMESTAMP_RE = re.compile(
    r"\b\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?\b"
)
SYSLOG_TS_RE = re.compile(r"\b[A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}\b")
HTTP_RE = re.compile(
    r'"?(?P<method>GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+'
    r'(?P<path>/[^\s"?]*)[^\"]*"?\s+(?P<status>[1-5]\d{2})\b',
    re.I,
)
ACCESS_LOG_RE = re.compile(
    r"(?P<ip>(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)).*?"
    r'"(?P<method>GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+'
    r'(?P<path>/[^\s"?]*)[^\"]*"\s+(?P<status>[1-5]\d{2})\b',
    re.I,
)
LATENCY_RE = re.compile(
    r"\b(?:latency|duration|elapsed|request_time|response_time|rt|took)[=:\s]+"
    r"(?P<value>\d+(?:\.\d+)?)(?P<unit>ms|s|sec|seconds)?\b",
    re.I,
)
USER_AGENT_HEADER_RE = re.compile(
    r"(?i)\b(user[-_ ]?agent|http_user_agent|ua|browser|os_version)\b[=:\s]+(\"[^\"]*\"|'[^']*'|[^\s,;]+)"
)
QUOTED_UA_RE = re.compile(
    r'"[^"]*(?:Mozilla|AppleWebKit|Chrome|Safari|Firefox|Edg|Windows NT|Mac OS X|Android|iPhone OS|curl|Go-http-client)[^"]*"',
    re.I,
)
AGENT_TOKEN_RE = re.compile(
    r"\b(?:Mozilla|AppleWebKit|Chrome|Safari|Firefox|Edg|Windows NT|Mac OS X|Android|iPhone OS|Version|Mobile)"
    r"[/\sA-Za-z0-9._;()+-]*",
    re.I,
)
QUERY_RE = re.compile(
    r"(?P<prefix>[?&])(?P<key>token|password|passwd|secret|authorization|api_key|apikey)=(?P<value>[^&\s]+)",
    re.I,
)


@dataclass
class LogEvent:
    source: str
    message: str
    normalized_message: str
    severity: str
    fingerprint: str
    timestamp: str | None = None
    client_ips: list[str] = field(default_factory=list)
    http: dict[str, Any] = field(default_factory=dict)


def build_preprocessed_evidence(
    evidence: EvidenceBundle,
    derived_signals: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    collectors = evidence.collectors
    log_events = _extract_log_events(collectors)
    return {
        "schema_version": "preprocessed-evidence/v1",
        "alert": {
            "cluster_id": evidence.cluster_id,
            "node_name": evidence.node_name,
            "alert_name": evidence.alert_name,
            "collected_at": evidence.collected_at.isoformat(),
        },
        "node": _node_summary(collectors, evidence.node_name),
        "collector_status": _collector_status(collectors),
        "key_metrics": _key_metrics(collectors),
        "derived_signals": derived_signals or [],
        "log_clusters": _cluster_log_events(log_events),
        "command_failures": _command_failures(collectors),
        "config_findings": _config_findings(collectors),
        "llm_input_policy": {
            "use_this_payload_only": True,
            "raw_collectors_excluded": True,
            "dropped_noise_fields": sorted(NOISE_KEYS),
            "web_user_agent_removed": True,
            "client_ips_preserved_for_filtering": True,
        },
    }


def _node_summary(collectors: dict[str, Any], fallback_node_name: str) -> dict[str, Any]:
    node = _dict_value(collectors.get("node"))
    return {
        "node_name": node.get("agent_node_name") or node.get("host_name") or fallback_node_name,
        "host_name": node.get("host_name"),
        "kernel_version": node.get("kernel_version"),
        "boot_id": node.get("boot_id"),
        "kernel_tainted": node.get("kernel_tainted"),
        "uptime_seconds": node.get("uptime_seconds"),
        "load_average": node.get("load_average"),
    }


def _collector_status(collectors: dict[str, Any]) -> dict[str, str | None]:
    statuses = {}
    for name, value in collectors.items():
        item = _dict_value(value)
        statuses[name] = item.get("status") if item else None
    return statuses


def _key_metrics(collectors: dict[str, Any]) -> dict[str, Any]:
    systemd = _dict_value(collectors.get("systemd"))
    runtime = _dict_value(collectors.get("runtime"))
    disk = _dict_value(collectors.get("disk"))
    memory = _dict_value(collectors.get("memory"))
    process = _dict_value(collectors.get("process"))
    network = _dict_value(collectors.get("network"))
    conntrack = _dict_value(collectors.get("conntrack")) or _dict_value(network.get("conntrack"))
    cni = _dict_value(collectors.get("cni"))
    dns = _dict_value(collectors.get("dns"))
    kernel = _dict_value(collectors.get("kernel"))

    return {
        "systemd": _drop_none(
            {
                "kubelet_status": systemd.get("kubelet_status"),
                "kubelet_sub_state": systemd.get("kubelet_sub_state"),
                "kubelet_restart_count": systemd.get("kubelet_restart_count"),
                "containerd_status": systemd.get("containerd_status"),
                "containerd_sub_state": systemd.get("containerd_sub_state"),
                "containerd_restart_count": systemd.get("containerd_restart_count"),
                "failed_unit_count": len(systemd.get("failed_units", []))
                if isinstance(systemd.get("failed_units"), list)
                else None,
            }
        ),
        "runtime": _drop_none(
            {
                "containerd_socket_healthy": runtime.get("containerd_socket_healthy"),
                "containerd_socket_latency_ms": runtime.get("containerd_socket_latency_ms"),
                "containerd_pid_running": runtime.get("containerd_pid_running"),
                "containerd_socket_error": runtime.get("containerd_socket_error"),
            }
        ),
        "disk": _drop_none(
            {
                "root_usage_percent": disk.get("root_usage_percent"),
                "inode_usage_percent": disk.get("inode_usage_percent"),
                "root_mount_read_only": disk.get("root_mount_read_only"),
                "io_wait_percent_since_boot": disk.get("io_wait_percent_since_boot"),
                "kernel_io_error_detected": disk.get("kernel_io_error_detected"),
                "io_pressure": disk.get("io_pressure"),
            }
        ),
        "memory": _drop_none(
            {
                "usage_percent": memory.get("usage_percent"),
                "mem_available_kib": memory.get("mem_available_kib"),
                "swap_usage_percent": memory.get("swap_usage_percent"),
                "dirty_kib": memory.get("dirty_kib"),
                "writeback_kib": memory.get("writeback_kib"),
                "oom_kill_detected": memory.get("oom_kill_detected"),
                "pressure": memory.get("pressure"),
            }
        ),
        "process": _drop_none(
            {
                "process_count": process.get("process_count"),
                "zombie_process_count": process.get("zombie_process_count"),
                "pid_usage_percent": process.get("pid_usage_percent"),
            }
        ),
        "network": _drop_none(
            {
                "interfaces_down": network.get("interfaces_down"),
                "default_route_interfaces": network.get("default_route_interfaces"),
                "nic_link_flap_detected": network.get("nic_link_flap_detected"),
                "interface_rx_error_total": network.get("interface_rx_error_total"),
                "interface_tx_error_total": network.get("interface_tx_error_total"),
                "interface_rx_drop_total": network.get("interface_rx_drop_total"),
                "interface_tx_drop_total": network.get("interface_tx_drop_total"),
                "tcp_retrans_segments": network.get("tcp_retrans_segments"),
                "tcp_ext_listen_overflows": network.get("tcp_ext_listen_overflows"),
                "conntrack_usage_percent": network.get("conntrack_usage_percent"),
            }
        ),
        "conntrack": _drop_none(
            {
                "count": conntrack.get("count"),
                "max": conntrack.get("max"),
                "available": conntrack.get("available"),
                "usage_percent": conntrack.get("usage_percent"),
                "near_limit": conntrack.get("near_limit"),
            }
        ),
        "cni": _drop_none(
            {
                "config_count": cni.get("config_count"),
                "plugin_types": cni.get("plugin_types"),
                "mtu": cni.get("mtu"),
                "mtu_values": cni.get("mtu_values"),
                "parse_errors": cni.get("parse_errors"),
            }
        ),
        "dns": _drop_none(
            {
                "dns_configured": dns.get("dns_configured"),
                "nameserver_count": dns.get("nameserver_count"),
                "nameservers": dns.get("nameservers"),
                "search": dns.get("search"),
                "ndots": dns.get("ndots"),
                "timeout_seconds": dns.get("timeout_seconds"),
                "attempts": dns.get("attempts"),
            }
        ),
        "kernel": _drop_none(
            {
                "kernel_tainted": kernel.get("kernel_tainted"),
                "kernel_tainted_raw": kernel.get("kernel_tainted_raw"),
                "io_error_detected": kernel.get("io_error_detected"),
                "nic_error_detected": kernel.get("nic_error_detected"),
                "oom_detected": kernel.get("oom_detected"),
                "blocked_task_detected": kernel.get("blocked_task_detected"),
                "read_only_filesystem_detected": kernel.get("read_only_filesystem_detected"),
            }
        ),
    }


def _extract_log_events(collectors: dict[str, Any]) -> list[LogEvent]:
    raw_events: list[tuple[str, str]] = []
    _walk_for_log_text(collectors, "collectors", raw_events)
    events = []
    for source, line in raw_events[:MAX_LOG_EVENTS]:
        for split_line in _split_log_text(line):
            event = _build_log_event(source, split_line)
            if event is not None:
                events.append(event)
    return events


def _walk_for_log_text(value: Any, path: str, events: list[tuple[str, str]]) -> None:
    if len(events) >= MAX_LOG_EVENTS:
        return
    if isinstance(value, dict):
        for raw_key, child in value.items():
            key = str(raw_key)
            if key.lower() in NOISE_KEYS:
                continue
            child_path = f"{path}.{key}"
            if isinstance(child, str) and _is_log_path(child_path):
                events.append((child_path, child))
            else:
                _walk_for_log_text(child, child_path, events)
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            child_path = f"{path}[{index}]"
            if isinstance(child, str) and (_is_log_path(path) or _looks_like_log_line(child)):
                events.append((path, child))
            else:
                _walk_for_log_text(child, child_path, events)


def _split_log_text(text: str) -> list[str]:
    lines = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or len(line) < 5:
            continue
        lines.append(line)
    return lines


def _build_log_event(source: str, line: str) -> LogEvent | None:
    sanitized = _sanitize_log_line(line)
    if not sanitized:
        return None
    timestamp = _extract_timestamp(sanitized)
    ips = _dedupe(IP_RE.findall(sanitized))
    http = _extract_http(sanitized)
    severity = _infer_severity(sanitized, http)
    normalized = _normalize_log_message(sanitized, http)
    if not normalized:
        return None
    return LogEvent(
        source=source,
        message=_truncate(sanitized, MAX_SAMPLE_LINE_LENGTH),
        normalized_message=normalized,
        severity=severity,
        fingerprint=_fingerprint(normalized),
        timestamp=timestamp,
        client_ips=ips,
        http=http,
    )


def _sanitize_log_line(line: str) -> str:
    text = QUERY_RE.sub(lambda match: f"{match.group('prefix')}{match.group('key')}=<redacted>", line)
    text = USER_AGENT_HEADER_RE.sub(lambda match: f"{match.group(1)}=<redacted>", text)
    text = QUOTED_UA_RE.sub('"<user-agent-redacted>"', text)
    text = AGENT_TOKEN_RE.sub("<client-agent>", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _extract_timestamp(line: str) -> str | None:
    match = TIMESTAMP_RE.search(line) or SYSLOG_TS_RE.search(line)
    return match.group(0) if match else None


def _extract_http(line: str) -> dict[str, Any]:
    match = ACCESS_LOG_RE.search(line) or HTTP_RE.search(line)
    if not match:
        return {}
    status = int(match.group("status"))
    latency = _extract_latency(line)
    return _drop_none(
        {
            "method": match.group("method").upper(),
            "path": _normalize_path(match.group("path")),
            "status_code": status,
            "status_family": f"{status // 100}xx",
            "latency_ms": latency,
        }
    )


def _extract_latency(line: str) -> float | None:
    match = LATENCY_RE.search(line)
    if not match:
        return None
    value = float(match.group("value"))
    unit = (match.group("unit") or "ms").lower()
    if unit in {"s", "sec", "seconds"}:
        value *= 1000
    return round(value, 2)


def _infer_severity(line: str, http: dict[str, Any]) -> str:
    status_code = http.get("status_code")
    if isinstance(status_code, int):
        if status_code >= 500:
            return "error"
        if status_code >= 400:
            return "warning"
    lowered = line.lower()
    if any(token in lowered for token in ["panic", "fatal", "error", "failed", "exception", "timeout", "i/o error"]):
        return "error"
    if any(token in lowered for token in ["warn", "retry", "throttle", "backoff", "slow"]):
        return "warning"
    return "info"


def _normalize_log_message(line: str, http: dict[str, Any]) -> str:
    if http:
        method = http.get("method", "HTTP")
        path = http.get("path", "/")
        family = http.get("status_family", "unknown")
        return f"http {method} {path} status_{family}"

    lowered = line.lower()
    lowered = TIMESTAMP_RE.sub("<time>", lowered)
    lowered = SYSLOG_TS_RE.sub("<time>", lowered)
    lowered = IP_RE.sub("<ip>", lowered)
    lowered = UUID_RE.sub("<uuid>", lowered)
    lowered = re.sub(r"\b[0-9a-f]{12,}\b", "<hex>", lowered)
    lowered = re.sub(r"\b\d+\b", "<num>", lowered)
    lowered = re.sub(r"pod/[a-z0-9_.-]+", "pod/<name>", lowered)
    lowered = re.sub(r"\b[a-z0-9]([-a-z0-9]*[a-z0-9])?-[a-f0-9]{8,10}\b", "<generated-name>", lowered)
    lowered = re.sub(r"[^a-z0-9_./:<>\-\s]", " ", lowered)
    lowered = re.sub(r"\s+", " ", lowered).strip()
    return lowered


def _normalize_path(path: str) -> str:
    path = path.split("?", 1)[0] or "/"
    parts = []
    for part in path.split("/"):
        if not part:
            continue
        if part.isdigit() or UUID_RE.fullmatch(part) or re.fullmatch(r"[0-9a-f]{8,}", part, re.I):
            parts.append(":id")
        else:
            parts.append(part)
    return "/" + "/".join(parts)


def _cluster_log_events(events: list[LogEvent]) -> list[dict[str, Any]]:
    clusters: list[dict[str, Any]] = []
    for event in events:
        cluster = _find_similar_cluster(clusters, event)
        if cluster is None:
            clusters.append(_new_log_cluster(event))
        else:
            _merge_event(cluster, event)

    clusters.sort(key=lambda item: (_log_severity_rank(item["severity"]), -item["count"], item["normalized_message"]))
    return clusters[:MAX_LOG_CLUSTERS]


def _find_similar_cluster(clusters: list[dict[str, Any]], event: LogEvent) -> dict[str, Any] | None:
    for cluster in clusters:
        if cluster["severity"] != event.severity:
            continue
        ratio = SequenceMatcher(None, cluster["normalized_message"], event.normalized_message).ratio()
        if ratio >= 0.86:
            return cluster
    return None


def _new_log_cluster(event: LogEvent) -> dict[str, Any]:
    return {
        "fingerprint": event.fingerprint,
        "severity": event.severity,
        "count": 1,
        "normalized_message": event.normalized_message,
        "sources": [event.source],
        "client_ips": event.client_ips,
        "http": _cluster_http(event.http),
        "first_seen": event.timestamp,
        "last_seen": event.timestamp,
        "sample_lines": [event.message],
    }


def _merge_event(cluster: dict[str, Any], event: LogEvent) -> None:
    cluster["count"] += 1
    cluster["sources"] = _dedupe([*cluster["sources"], event.source])
    cluster["client_ips"] = _dedupe([*cluster["client_ips"], *event.client_ips])
    cluster["http"] = _merge_http(cluster.get("http", {}), event.http)
    cluster["first_seen"] = _min_present(cluster.get("first_seen"), event.timestamp)
    cluster["last_seen"] = _max_present(cluster.get("last_seen"), event.timestamp)
    if len(cluster["sample_lines"]) < MAX_SAMPLE_LINES and event.message not in cluster["sample_lines"]:
        cluster["sample_lines"].append(event.message)


def _cluster_http(http: dict[str, Any]) -> dict[str, Any]:
    if not http:
        return {}
    return {
        "methods": [http["method"]] if http.get("method") else [],
        "paths": [http["path"]] if http.get("path") else [],
        "status_codes": [http["status_code"]] if http.get("status_code") else [],
        "status_families": [http["status_family"]] if http.get("status_family") else [],
        "max_latency_ms": http.get("latency_ms"),
    }


def _merge_http(current: dict[str, Any], http: dict[str, Any]) -> dict[str, Any]:
    if not http:
        return current
    merged = dict(current)
    for key, output_key in [
        ("method", "methods"),
        ("path", "paths"),
        ("status_code", "status_codes"),
        ("status_family", "status_families"),
    ]:
        value = http.get(key)
        if value is not None:
            merged[output_key] = _dedupe([*merged.get(output_key, []), value])
    latency = http.get("latency_ms")
    if latency is not None:
        previous = merged.get("max_latency_ms")
        merged["max_latency_ms"] = max(previous, latency) if previous is not None else latency
    return merged


def _command_failures(collectors: dict[str, Any]) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    _walk_for_command_failures(collectors, "collectors", failures)
    return failures[:20]


def _walk_for_command_failures(value: Any, path: str, failures: list[dict[str, Any]]) -> None:
    if len(failures) >= 20:
        return
    if isinstance(value, dict):
        if value.get("ok") is False and ("stdout" in value or "stderr" in value):
            failures.append(
                _drop_none(
                    {
                        "source": path,
                        "exit_code": value.get("exit_code"),
                        "stderr": _truncate(_sanitize_log_line(str(value.get("stderr") or "")), 500),
                        "stdout_excerpt": _truncate(_sanitize_log_line(str(value.get("stdout") or "")), 500),
                    }
                )
            )
            return
        for raw_key, child in value.items():
            if str(raw_key).lower() in NOISE_KEYS:
                continue
            _walk_for_command_failures(child, f"{path}.{raw_key}", failures)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _walk_for_command_failures(child, f"{path}[{index}]", failures)


def _config_findings(collectors: dict[str, Any]) -> dict[str, Any]:
    cni = _dict_value(collectors.get("cni"))
    dns = _dict_value(collectors.get("dns"))
    systemd = _dict_value(collectors.get("systemd"))
    return {
        "cni": _drop_none(
            {
                "config_dir_exists": cni.get("config_dir_exists"),
                "config_count": cni.get("config_count"),
                "plugin_types": cni.get("plugin_types"),
                "mtu_values": cni.get("mtu_values"),
                "parse_errors": cni.get("parse_errors"),
            }
        ),
        "dns": _drop_none(
            {
                "resolv_conf_exists": dns.get("resolv_conf_exists"),
                "dns_configured": dns.get("dns_configured"),
                "nameserver_count": dns.get("nameserver_count"),
                "nameservers": dns.get("nameservers"),
                "search": dns.get("search"),
                "options": dns.get("options"),
                "ndots": dns.get("ndots"),
                "timeout_seconds": dns.get("timeout_seconds"),
                "attempts": dns.get("attempts"),
            }
        ),
        "systemd": _drop_none(
            {
                "failed_units": systemd.get("failed_units"),
            }
        ),
    }


def _is_log_path(path: str) -> bool:
    lowered = path.lower().replace("-", "_")
    return any(hint in lowered for hint in LOG_PATH_HINTS)


def _looks_like_log_line(line: str) -> bool:
    return bool(
        TIMESTAMP_RE.search(line)
        or SYSLOG_TS_RE.search(line)
        or HTTP_RE.search(line)
        or ACCESS_LOG_RE.search(line)
        or re.search(r"\b(error|warn|failed|panic|timeout|exception|oom|denied)\b", line, re.I)
    )


def _fingerprint(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:16]


def _dict_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _drop_none(value: dict[str, Any]) -> dict[str, Any]:
    return {key: item for key, item in value.items() if item is not None}


def _dedupe(values: list[Any]) -> list[Any]:
    result = []
    seen = set()
    for value in values:
        marker = repr(value)
        if marker in seen:
            continue
        seen.add(marker)
        result.append(value)
    return result


def _truncate(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "...<truncated>"


def _min_present(left: str | None, right: str | None) -> str | None:
    if left is None:
        return right
    if right is None:
        return left
    return min(left, right)


def _max_present(left: str | None, right: str | None) -> str | None:
    if left is None:
        return right
    if right is None:
        return left
    return max(left, right)


def _log_severity_rank(severity: str) -> int:
    return {"error": 0, "warning": 1, "info": 2}.get(severity, 3)
