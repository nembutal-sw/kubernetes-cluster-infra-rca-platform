from __future__ import annotations

import json
import logging
import os
import queue
import re
import shlex
import subprocess
import threading
import time
from datetime import datetime, timezone
from typing import Any


LOGGER = logging.getLogger("cluster-infra-rca-agent.ebpf")


class EbpfEventManager:
    def __init__(self, enabled: bool = False, queue_size: int = 1000) -> None:
        self.enabled = enabled
        self.events: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=max(10, queue_size))
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []

    def start(self) -> None:
        if not self.enabled:
            return
        plugins = [
            ("oom", os.getenv("EBPF_OOM_COMMAND", "oomkill-bpfcc")),
            ("tcp", os.getenv("EBPF_TCP_COMMAND", "tcpretrans-bpfcc")),
            ("dns", os.getenv("EBPF_DNS_COMMAND", "gethostlatency-bpfcc")),
        ]
        for name, command in plugins:
            thread = threading.Thread(
                target=self._run_plugin, args=(name, command), daemon=True, name=f"ebpf-{name}"
            )
            thread.start()
            self.threads.append(thread)

    def stop(self) -> None:
        self.stop_event.set()

    def drain(self, limit: int = 100) -> list[dict[str, Any]]:
        drained: list[dict[str, Any]] = []
        while len(drained) < max(1, limit):
            try:
                drained.append(self.events.get_nowait())
            except queue.Empty:
                break
        return drained

    def requeue(self, events: list[dict[str, Any]]) -> None:
        for event in events:
            self._put(event)

    def _run_plugin(self, name: str, command: str) -> None:
        backoff = 2.0
        while not self.stop_event.is_set():
            try:
                process = subprocess.Popen(
                    shlex.split(command),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    bufsize=1,
                    env={**os.environ, "PYTHONUNBUFFERED": "1"},
                )
                LOGGER.info("started eBPF plugin %s", name)
                assert process.stdout is not None
                for line in process.stdout:
                    if self.stop_event.is_set():
                        process.terminate()
                        return
                    event = parse_event(name, line)
                    if event is not None:
                        self._put(event)
                error = process.stderr.read()[-1000:] if process.stderr is not None else ""
                LOGGER.warning("eBPF plugin %s exited: %s", name, error.strip())
            except (OSError, ValueError) as exc:
                LOGGER.warning("eBPF plugin %s unavailable: %s", name, exc)
            self.stop_event.wait(backoff)
            backoff = min(backoff * 2, 60)

    def _put(self, event: dict[str, Any]) -> None:
        try:
            self.events.put_nowait(event)
        except queue.Full:
            try:
                self.events.get_nowait()
            except queue.Empty:
                pass
            self.events.put_nowait(event)
            LOGGER.warning("eBPF event queue was full; oldest event was discarded")


def parse_event(plugin: str, line: str) -> dict[str, Any] | None:
    value = line.strip()
    if not value or value.lower().startswith(("time", "tracing", "pid", "comm")):
        return None
    try:
        payload = json.loads(value)
        if isinstance(payload, dict):
            return _event(
                str(payload.get("event_type") or plugin),
                str(payload.get("component") or _component(plugin)),
                str(payload.get("severity") or "warning"),
                payload,
            )
    except json.JSONDecodeError:
        pass

    if plugin == "oom":
        match = re.search(r"(?P<comm>\S+)\s+(?P<pid>\d+)\s+.*?(?P<killed>\S+)\s+(?P<kpid>\d+)", value)
        payload = {"raw": value}
        if match:
            payload.update(match.groupdict())
        return _event("oom_kill", "memory", "critical", payload)
    if plugin == "tcp":
        if "retrans" not in value.lower() and not re.search(r"\b(?:R|L)\b", value):
            return None
        return _event("tcp_retransmit", "network", "warning", {"raw": value})
    if plugin == "dns":
        match = re.search(r"(?P<latency>[0-9.]+)\s*(?P<unit>msecs?|ms|usecs?|us)?$", value, re.I)
        if not match:
            return None
        latency = float(match.group("latency"))
        if (match.group("unit") or "").lower() in {"usec", "usecs", "us"}:
            latency /= 1000
        threshold = float(os.getenv("EBPF_DNS_LATENCY_THRESHOLD_MS", "1000"))
        if latency < threshold:
            return None
        event_type = "dns_timeout" if latency >= threshold else "dns_latency"
        return _event(event_type, "dns", "critical", {"raw": value, "latency_ms": latency})
    return None


def _event(event_type: str, component: str, severity: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "event_type": event_type,
        "component": component,
        "severity": severity,
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "payload": payload,
    }


def _component(plugin: str) -> str:
    return {"oom": "memory", "tcp": "network", "dns": "dns"}.get(plugin, "kernel")
