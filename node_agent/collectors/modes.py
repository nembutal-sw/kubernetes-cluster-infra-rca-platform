from __future__ import annotations

import os


AGENT_MODES = {"safe", "node-diagnostics", "ebpf"}
SAFE_COLLECTORS = {"node", "kubernetes", "dns"}
NODE_DIAGNOSTIC_COLLECTORS = {
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
}


def agent_mode() -> str:
    value = os.getenv("AGENT_MODE", "safe").strip().lower()
    if value not in AGENT_MODES:
        raise ValueError(f"AGENT_MODE must be one of: {', '.join(sorted(AGENT_MODES))}")
    return value


def allowed_collectors(mode: str | None = None) -> set[str]:
    selected = mode or agent_mode()
    if selected == "safe":
        return set(SAFE_COLLECTORS)
    return set(NODE_DIAGNOSTIC_COLLECTORS)
