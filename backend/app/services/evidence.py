from __future__ import annotations

from backend.app.models import AlertmanagerAlert, Cluster, EvidenceBundle


class FakeEvidenceCollector:
    """Returns deterministic evidence until the real node agent exists."""

    def collect(self, cluster: Cluster, alert: AlertmanagerAlert) -> EvidenceBundle:
        labels = alert.labels
        alert_name = labels.get("alertname", "UnknownAlert")
        node_name = labels.get("node") or labels.get("instance") or "unknown-node"

        collectors = {
            "node": {
                "condition": alert_name,
                "ready": alert_name != "NodeNotReady",
                "kernel_version": "fake-6.8.0",
            },
            "systemd": {
                "kubelet_status": "active",
                "kubelet_restart_count": 0,
                "containerd_status": "active",
                "containerd_restart_count": 0,
            },
            "runtime": {
                "containerd_socket_healthy": True,
                "containerd_socket_latency_ms": 20,
            },
            "disk": {
                "root_usage_percent": 72,
                "inode_usage_percent": 55,
                "io_wait_percent": 8,
                "kernel_io_error_detected": False,
            },
            "memory": {
                "usage_percent": 67,
                "oom_kill_detected": False,
            },
            "network": {
                "nic_link_flap_detected": False,
                "conntrack_usage_percent": 42,
                "dns_lookup_latency_ms": 15,
                "mtu_mismatch_suspected": False,
            },
            "cni": {
                "plugin_errors_detected": False,
                "mtu": 1450,
            },
        }

        if alert_name == "NodeNotReady":
            collectors["systemd"]["kubelet_status"] = "restarting"
            collectors["systemd"]["kubelet_restart_count"] = 7
            collectors["runtime"]["containerd_socket_healthy"] = False
            collectors["runtime"]["containerd_socket_latency_ms"] = 5000
            collectors["network"]["conntrack_usage_percent"] = 91
        elif alert_name == "DiskPressure":
            collectors["disk"]["root_usage_percent"] = 93
            collectors["disk"]["inode_usage_percent"] = 98
            collectors["disk"]["io_wait_percent"] = 37
        elif alert_name == "MemoryPressure":
            collectors["memory"]["usage_percent"] = 96
            collectors["memory"]["oom_kill_detected"] = True
        elif alert_name == "NetworkUnavailable":
            collectors["network"]["nic_link_flap_detected"] = True
            collectors["network"]["conntrack_usage_percent"] = 89
            collectors["network"]["dns_lookup_latency_ms"] = 1200
            collectors["cni"]["plugin_errors_detected"] = True
        elif alert_name in {"KubeletDown", "KubeletUnhealthy"}:
            collectors["systemd"]["kubelet_status"] = "failed"
            collectors["systemd"]["kubelet_restart_count"] = 12
        elif alert_name in {"ContainerdDown", "ContainerRuntimeUnhealthy"}:
            collectors["runtime"]["containerd_socket_healthy"] = False
            collectors["systemd"]["containerd_status"] = "failed"
            collectors["systemd"]["containerd_restart_count"] = 5

        return EvidenceBundle(
            cluster_id=cluster.cluster_id,
            node_name=node_name,
            alert_name=alert_name,
            collectors=collectors,
        )

