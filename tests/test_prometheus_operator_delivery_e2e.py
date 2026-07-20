from pathlib import Path

from scripts.operator_webhook_sink import EXPECTED_ALERT, matching_alerts


ROOT = Path(__file__).resolve().parents[1]


def test_operator_delivery_script_is_apply_gated_and_ownership_scoped() -> None:
    script = (ROOT / "scripts" / "prometheus-operator-delivery-e2e.sh").read_text(encoding="utf-8")

    required = (
        "Without --apply it prints a safety summary",
        'if [[ "${apply}" != "true" ]]',
        "--confirm-context is required with --apply",
        "namespace_owned_by_run",
        "cluster-infra.rca.io/e2e-run-id",
        "prometheusrules.monitoring.coreos.com",
        "alertmanagerconfigs.monitoring.coreos.com",
        "RCA_OPERATOR_DELIVERY",
        "wait_for_status firing",
        "wait_for_status resolved",
        '"value":"vector(0) > 0"',
        "credentials:",
        "sendResolved: true",
    )
    for marker in required:
        assert marker in script

    assert "kubectl delete namespace --all" not in script
    assert "systemctl" not in script
    assert "kubectl drain" not in script
    assert "kubectl cordon" not in script


def test_operator_webhook_sink_selects_only_the_canary_alert() -> None:
    alerts = [
        {"status": "firing", "labels": {"alertname": EXPECTED_ALERT}},
        {"status": "firing", "labels": {"alertname": "OtherAlert"}},
        "malformed",
    ]

    assert matching_alerts(alerts) == [alerts[0]]
