import json

from backend.app.models import EvidenceBundle
from backend.app.services.preprocessor import build_preprocessed_evidence


def test_preprocessor_clusters_web_logs_without_user_agent_noise() -> None:
    evidence = EvidenceBundle(
        cluster_id="cluster-1",
        node_name="worker-3",
        alert_name="HTTP5xxIncrease",
        collectors={
            "web": {
                "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
                "os_version": "Windows NT 10.0",
                "access_logs": [
                    (
                        '10.0.1.20 - - [10/Jun/2026:09:15:12 +0900] '
                        '"GET /api/orders/123?token=secret-1 HTTP/1.1" 500 42 "-" '
                        '"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36" '
                        "duration=800ms"
                    ),
                    (
                        '10.0.1.21 - - [10/Jun/2026:09:15:18 +0900] '
                        '"GET /api/orders/456?token=secret-2 HTTP/1.1" 503 42 "-" '
                        '"Mozilla/5.0 (Mac OS X 14_5) Firefox/127.0" '
                        "duration=1200ms"
                    ),
                ],
            }
        },
    )

    payload = build_preprocessed_evidence(evidence)
    encoded = json.dumps(payload)

    assert payload["schema_version"] == "preprocessed-evidence/v2"
    assert "Mozilla" not in encoded
    assert "Windows NT" not in encoded
    assert "Chrome" not in encoded
    assert "Firefox" not in encoded
    assert "secret-1" not in encoded
    assert "secret-2" not in encoded

    cluster = payload["log_clusters"][0]
    assert cluster["severity"] == "error"
    assert cluster["count"] == 2
    assert cluster["client_ips"] == ["10.0.1.20", "10.0.1.21"]
    assert cluster["normalized_message"] == "http GET /api/orders/:id status_5xx"
    assert cluster["http"]["paths"] == ["/api/orders/:id"]
    assert set(cluster["http"]["status_codes"]) == {500, 503}
    assert cluster["http"]["max_latency_ms"] == 1200.0
    assert payload["log_summary"]["severity_counts"]["error"] == 2
    assert payload["log_summary"]["http_status_family_counts"]["5xx"] == 2
    assert payload["log_summary"]["top_http_error_paths"] == [{"value": "/api/orders/:id", "count": 2}]
    assert payload["log_summary"]["unique_client_ip_count"] == 2
    assert payload["llm_input_policy"]["web_user_agent_removed"] is True
    assert payload["llm_input_policy"]["client_ips_preserved_for_filtering"] is True


def test_preprocessor_keeps_infra_metrics_and_command_failures() -> None:
    evidence = EvidenceBundle(
        cluster_id="cluster-1",
        node_name="worker-3",
        alert_name="NodeNotReady",
        collectors={
            "systemd": {
                "status": "ok",
                "kubelet_status": "failed",
                "kubelet_restart_count": 8,
                "failed_units": [{"unit": "kubelet.service"}],
                "command": {"ok": False, "exit_code": 1, "stdout": "", "stderr": "systemctl failed"},
            },
            "runtime": {
                "containerd_socket_healthy": False,
                "containerd_socket_error": "connection refused",
            },
            "network": {
                "conntrack_usage_percent": 92,
                "conntrack": {"count": 920, "max": 1000, "available": 80, "near_limit": True},
            },
        },
    )

    payload = build_preprocessed_evidence(
        evidence,
        derived_signals=[
            {
                "signal": "containerd_socket_unhealthy",
                "component": "containerd",
                "severity": "critical",
            }
        ],
    )

    assert payload["key_metrics"]["systemd"]["kubelet_status"] == "failed"
    assert payload["key_metrics"]["runtime"]["containerd_socket_healthy"] is False
    assert payload["key_metrics"]["conntrack"]["near_limit"] is True
    assert payload["evidence_quality"]["expected_collectors"] == [
        "node",
        "systemd",
        "runtime",
        "kernel",
        "network",
        "conntrack",
    ]
    assert payload["evidence_quality"]["critical_or_error_signal_count"] == 1
    assert payload["derived_signals"][0]["signal"] == "containerd_socket_unhealthy"
    assert payload["component_health"]["containerd"]["status"] == "critical"
    assert "containerd" in payload["incident_focus"]["primary_components"]
    assert any(
        mode["mode"] == "containerd_socket_unhealthy"
        for mode in payload["incident_focus"]["observed_failure_modes"]
    )
    assert payload["command_failures"][0]["source"] == "collectors.systemd.command"
