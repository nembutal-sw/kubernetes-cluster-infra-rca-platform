import json
import time

from scripts.alertmanager_delivery_test import (
    DeliveryCapture,
    alertmanager_config,
    matching_statuses,
    prometheus_config,
    validate_delivery,
)


def payload(status: str, alert_name: str = "ClusterRcaLlmCircuitBreakerOpen"):
    return {
        "status": status,
        "alerts": [{"status": status, "labels": {"alertname": alert_name}}],
    }


def test_delivery_validation_requires_firing_and_resolved():
    capture = DeliveryCapture("test-token")
    capture.record(payload("firing"))
    capture.record(payload("resolved"))

    assert validate_delivery(capture, time.monotonic() + 1) == {"firing", "resolved"}


def test_delivery_validation_ignores_unrelated_alerts():
    assert matching_statuses([payload("firing", "OtherAlert")]) == set()


def test_runtime_configs_use_token_file_and_chart_rule(tmp_path):
    token_file = tmp_path / "token"
    rule_file = tmp_path / "rules.yml"

    alertmanager = alertmanager_config(19093, token_file)
    prometheus = prometheus_config(19091, 19093, rule_file)

    assert "credentials_file:" in alertmanager
    assert "send_resolved: true" in alertmanager
    assert "127.0.0.1:19093" in prometheus
    assert json.dumps(str(rule_file.resolve())) in prometheus
