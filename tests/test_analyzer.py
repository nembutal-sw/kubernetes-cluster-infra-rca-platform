from backend.app.models import EvidenceBundle
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.policy import PolicyEngine


def test_analyzer_suppresses_standalone_units_when_rke2_embedded_runtime_is_running() -> None:
    analyzer = RuleBasedRcaAnalyzer(PolicyEngine())

    report = analyzer.analyze(
        "report-rke2",
        EvidenceBundle(
            cluster_id="cluster-1",
            node_name="core-a",
            alert_name="NodeNotReady",
            collectors={
                "systemd": {
                    "kubelet_status": "inactive",
                    "kubelet_sub_state": "dead",
                    "containerd_status": "inactive",
                    "containerd_sub_state": "dead",
                    "rke2_server_status": "active",
                    "rke2_server_sub_state": "running",
                    "rke2_embedded_kubelet_running": True,
                    "rke2_embedded_containerd_running": True,
                },
                "runtime": {"containerd_socket_healthy": True},
            },
        ),
    )

    signal_names = _signal_names(report.evidence)

    assert "kubelet_unit_unhealthy" not in signal_names
    assert "containerd_unit_unhealthy" not in signal_names


def test_analyzer_ignores_virtual_interface_noise_and_cumulative_tcp_counters() -> None:
    analyzer = RuleBasedRcaAnalyzer(PolicyEngine())

    report = analyzer.analyze(
        "report-network",
        EvidenceBundle(
            cluster_id="cluster-1",
            node_name="core-a",
            alert_name="NetworkUnavailable",
            collectors={
                "network": {
                    "interfaces_down": [],
                    "nic_link_flap_detected": False,
                    "interface_tx_drop_total": 278080198,
                    "interface_rx_drop_total": 0,
                    "interface_rx_error_total": 0,
                    "interface_tx_error_total": 0,
                    "physical_interfaces": ["enp0s6"],
                    "physical_interface_rx_error_total": 0,
                    "physical_interface_tx_error_total": 0,
                    "physical_interface_rx_drop_total": 0,
                    "physical_interface_tx_drop_total": 0,
                    "tcp_retrans_segments": 15720382,
                    "tcp_retrans_segments_per_hour_since_boot": 19124.11,
                    "tcp_ext_listen_overflows": 0,
                    "tcp_ext_listen_drops": 0,
                    "conntrack_usage_percent": 0.85,
                    "conntrack": {"usage_percent": 0.85, "near_limit": False},
                },
            },
        ),
    )

    signal_names = _signal_names(report.evidence)

    assert "interface_packet_errors" not in signal_names
    assert "tcp_error_counters_high" not in signal_names


def test_analyzer_separates_containerd_socket_permission_from_runtime_outage() -> None:
    analyzer = RuleBasedRcaAnalyzer(PolicyEngine())

    report = analyzer.analyze(
        "report-runtime-permission",
        EvidenceBundle(
            cluster_id="cluster-1",
            node_name="core-a",
            alert_name="ContainerdDown",
            collectors={
                "runtime": {
                    "containerd_socket_path": "/run/k3s/containerd/containerd.sock",
                    "containerd_socket_exists": True,
                    "containerd_socket_is_socket": True,
                    "containerd_socket_healthy": False,
                    "containerd_socket_error": "[Errno 13] Permission denied",
                    "containerd_socket_permission_denied": True,
                },
            },
        ),
    )

    signal_names = _signal_names(report.evidence)

    assert "containerd_socket_permission_denied" in signal_names
    assert "containerd_socket_unhealthy" not in signal_names


def _signal_names(evidence_sections: list[dict]) -> set[str]:
    for section in evidence_sections:
        if section.get("type") == "derived_signals":
            return {signal["signal"] for signal in section["signals"]}
    return set()
