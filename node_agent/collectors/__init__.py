from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from node_agent.collectors import _legacy
from node_agent.collectors.cni import collect_cni
from node_agent.collectors.common import AgentPaths, CommandRunner
from node_agent.collectors.conntrack import collect_conntrack
from node_agent.collectors.disk import collect_disk
from node_agent.collectors.dns import collect_dns
from node_agent.collectors.inode import collect_inode
from node_agent.collectors.kernel import collect_kernel
from node_agent.collectors.kubelet import collect_kubelet
from node_agent.collectors.kubernetes import collect_kubernetes
from node_agent.collectors.memory import collect_memory
from node_agent.collectors.modes import AGENT_MODES, agent_mode, allowed_collectors
from node_agent.collectors.network import collect_network
from node_agent.collectors.node import collect_node
from node_agent.collectors.process import collect_process
from node_agent.collectors.registry import (
    Collector,
    CollectorDefinition,
    CollectorMetadata,
    build_registry,
    collector_metadata,
    definitions,
)
from node_agent.collectors.runtime import collect_runtime
from node_agent.collectors.systemd import collect_systemd

DEFAULT_COLLECTORS = list(_legacy.DEFAULT_COLLECTORS)
EVIDENCE_SCHEMA_VERSION = "collector-evidence/v1"
stat = _legacy.stat
_probe_unix_socket = _legacy._probe_unix_socket


def collect_evidence(
    requested_collectors: list[str] | None,
    paths: AgentPaths | None = None,
    runner: CommandRunner | None = None,
    registry: Mapping[str, Collector] | None = None,
) -> dict[str, Any]:
    paths = paths or AgentPaths.from_env()
    runner = runner or CommandRunner()
    available = registry or build_registry(paths, runner)
    selected = requested_collectors or DEFAULT_COLLECTORS
    evidence: dict[str, Any] = {}
    for collector_name in _legacy._dedupe(selected):
        collector = available.get(collector_name)
        if collector is None:
            known = collector_name in build_registry(paths, runner, "node-diagnostics")
            evidence[collector_name] = {
                "_schema_version": EVIDENCE_SCHEMA_VERSION,
                "status": "disabled" if known else "unsupported",
                "error": (
                    f"collector is not available in AGENT_MODE={agent_mode()}: {collector_name}"
                    if known
                    else f"collector is not supported: {collector_name}"
                ),
            }
            continue
        evidence[collector_name] = {
            "_schema_version": EVIDENCE_SCHEMA_VERSION,
            **_legacy._safe_collect(collector),
        }
    return evidence


__all__ = [
    "AgentPaths",
    "AGENT_MODES",
    "Collector",
    "CollectorDefinition",
    "CollectorMetadata",
    "CommandRunner",
    "DEFAULT_COLLECTORS",
    "build_registry",
    "agent_mode",
    "allowed_collectors",
    "collect_cni",
    "collect_conntrack",
    "collect_disk",
    "collect_dns",
    "collect_evidence",
    "collect_inode",
    "collect_kernel",
    "collect_kubelet",
    "collect_kubernetes",
    "collect_memory",
    "collect_network",
    "collect_node",
    "collect_process",
    "collect_runtime",
    "collect_systemd",
    "collector_metadata",
    "definitions",
]
