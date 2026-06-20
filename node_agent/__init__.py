from __future__ import annotations

__version__ = "0.1.0"
AGENT_PROTOCOL_VERSION = "1"

SUPPORTED_COLLECTORS = [
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
