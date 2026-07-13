from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_real_cluster_agent_e2e_keeps_mutations_explicit_and_owned() -> None:
    script = (ROOT / "scripts" / "real-cluster-agent-e2e.sh").read_text(encoding="utf-8")

    required = (
        "Without --apply it performs preflight",
        'if [[ "${apply}" != "true" ]]',
        "namespace_owned_by_run",
        '"cluster-infra.rca.io/e2e-run-id=${run_id}"',
        "statePersistence.enabled=false",
        "developmentSourceBundle.enabled=true",
        "verify_evidence_bundle.py",
        "never restarts, reboots, cordons, or drains a node",
        'cleanup_state="pending"',
        "namespace-pending.json",
    )
    for marker in required:
        assert marker in script

    assert "APPROVED_ACTIONS_ENABLED=true" not in script
    assert "kubectl drain" not in script
    assert "kubectl cordon" not in script
    assert "systemctl restart" not in script


def test_agent_chart_canary_options_are_safe_by_default() -> None:
    values = (ROOT / "charts" / "cluster-infra-rca-agent" / "values.yaml").read_text(encoding="utf-8")
    daemonset = (
        ROOT / "charts" / "cluster-infra-rca-agent" / "templates" / "daemonset.yaml"
    ).read_text(encoding="utf-8")

    assert "developmentSourceBundle:\n  enabled: false" in values
    assert "statePersistence:\n  enabled: true" in values
    assert "filter='data'" in daemonset
    assert 'or (eq $mode "safe") (not .Values.statePersistence.enabled)' in daemonset
    assert "mountPath: /app\n              readOnly: true" in daemonset
