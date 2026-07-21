from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_agent_fleet_burn_in_is_manual_approval_gated_and_reuses_kind_fleet() -> None:
    workflow = (ROOT / ".github" / "workflows" / "agent-fleet-burn-in.yml").read_text(encoding="utf-8")
    kind_script = (ROOT / "scripts" / "kind-smoke.sh").read_text(encoding="utf-8")

    assert "workflow_dispatch:" in workflow
    assert "push:" not in workflow
    assert "RUN-STANDARD-FLEET" in workflow
    assert "RUN-EXTENDED-FLEET" in workflow
    assert "change_reference" in workflow
    assert "agent-fleet-standard" in workflow
    assert "agent-fleet-extended" in workflow
    assert "RCA_AGENT_FLEET_PROFILE" in workflow
    assert "timeout-minutes: 350" in workflow
    assert "config: config/kind-multi-node.yaml" in workflow
    assert "RCA_AGENT_SOAK_MINIMUM_PODS: \"3\"" in workflow
    assert "persist-credentials: false" in workflow
    assert "retention-days:" in workflow
    assert "Worst steady RSS slope" in workflow
    assert "Worst steady RSS range" in workflow
    assert "Agent evidence observations" in workflow

    assert 'agent_soak_profile="${RCA_AGENT_SOAK_PROFILE:-smoke}"' in kind_script
    assert '--profile "${agent_soak_profile}"' in kind_script
    assert '--minimum-agent-pods "${agent_soak_minimum_pods}"' in kind_script
    assert "RCA_AGENT_SOAK_PLATFORM_ACCESS_TOKEN" in kind_script
    assert "--platform-evidence-fleet" in kind_script


def test_managed_canary_uses_scoped_runner_environment_and_redacted_artifact() -> None:
    workflow = (ROOT / ".github" / "workflows" / "managed-cluster-canary.yml").read_text(encoding="utf-8")
    attestation = (ROOT / "scripts" / "managed-canary-attestation.py").read_text(encoding="utf-8")

    required = (
        "workflow_dispatch:",
        "managed-canary-${{ inputs.platform }}",
        "- managed-canary",
        "RCA_MANAGED_CANARY_KUBECONFIG",
        "RCA_MANAGED_CANARY_ENVIRONMENT",
        "RCA_MANAGED_CANARY_PASSWORD",
        'APPROVED_ACTIONS_ENABLED: "false"',
        "PREFLIGHT-${platform_upper}",
        "RUN-${platform_upper}-CANARY",
        "--readiness-only",
        "managed-canary-attestation.py",
        "Remove private canary material",
        "persist-credentials: false",
        "path: validation-results/managed-canary/attestation.json",
    )
    for marker in required:
        assert marker in workflow

    assert "push:" not in workflow
    assert "--keep-resources" not in workflow
    assert "APPROVED_ACTIONS_ENABLED=true" not in workflow
    assert "path: ${{ runner.temp }}" not in workflow
    assert "validation-results/managed-canary/attestation.json" in workflow
    assert '"automatic_matrix_update": False' in attestation
