from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass
from typing import Any

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
from node_agent.collectors.network import collect_network
from node_agent.collectors.node import collect_node
from node_agent.collectors.process import collect_process
from node_agent.collectors.runtime import collect_runtime
from node_agent.collectors.systemd import collect_systemd


Collector = Callable[[], dict[str, Any]]


@dataclass(frozen=True)
class CollectorMetadata:
    name: str
    risk_level: str = "read_only"
    requires_host_network: bool = False
    requires_host_pid: bool = False
    requires_privileged: bool = False
    default_timeout_seconds: int = 5
    max_output_bytes: int = 1_048_576
    enabled_by_default: bool = True

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class CollectorDefinition:
    metadata: CollectorMetadata
    collect: Collector


def definitions(paths: AgentPaths, runner: CommandRunner) -> dict[str, CollectorDefinition]:
    return {
        "node": _definition("node", lambda: collect_node(paths), host_pid=True),
        "kubernetes": _definition("kubernetes", collect_kubernetes, host_network=True),
        "systemd": _definition("systemd", lambda: collect_systemd(paths, runner), host_pid=True),
        "kernel": _definition("kernel", lambda: collect_kernel(paths, runner), host_pid=True),
        "disk": _definition("disk", lambda: collect_disk(paths), host_pid=True),
        "inode": _definition("inode", lambda: collect_inode(paths), host_pid=True),
        "memory": _definition("memory", lambda: collect_memory(paths), host_pid=True),
        "process": _definition("process", lambda: collect_process(paths), host_pid=True),
        "network": _definition("network", lambda: collect_network(paths), host_network=True),
        "conntrack": _definition("conntrack", lambda: collect_conntrack(paths), host_network=True),
        "runtime": _definition("runtime", lambda: collect_runtime(paths, runner), host_pid=True),
        "kubelet": _definition("kubelet", lambda: collect_kubelet(paths, runner), host_pid=True),
        "cni": _definition("cni", lambda: collect_cni(paths), host_network=True),
        "dns": _definition("dns", lambda: collect_dns(paths), host_network=True),
    }


def build_registry(paths: AgentPaths, runner: CommandRunner) -> dict[str, Collector]:
    return {name: definition.collect for name, definition in definitions(paths, runner).items()}


def collector_metadata(paths: AgentPaths, runner: CommandRunner) -> list[dict[str, Any]]:
    return [item.metadata.as_dict() for item in definitions(paths, runner).values()]


def _definition(
    name: str,
    collect: Collector,
    *,
    host_network: bool = False,
    host_pid: bool = False,
) -> CollectorDefinition:
    return CollectorDefinition(
        CollectorMetadata(
            name=name,
            requires_host_network=host_network,
            requires_host_pid=host_pid,
        ),
        collect,
    )
