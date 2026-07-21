from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "operational-burn-in.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
KIND_SMOKE = ROOT / "scripts" / "kind-smoke.sh"


def test_workflow_is_manual_read_only_and_self_hosted() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "workflow_dispatch:" in content
    assert "schedule:" not in content
    assert "runs-on: rca-demo" in content
    assert "cancel-in-progress: false" in content
    assert "agent-soak-validation.py" in content
    assert "--discover-agent-pod" in content
    assert "--discover-agent-pods" in content
    assert "--minimum-agent-pods" in content
    assert "--require-runtime-observation" in content
    assert "observe_agent_pod:" not in content
    assert "agent_pid:" not in content
    assert "agent_state_dir:" not in content
    assert "fleet_mode:" in content
    assert "real-cluster-readiness-check.py" in content
    assert "operational-burn-in-summary.py" in content
    assert "--provider-call-budget 0" in content
    assert "--dry-run" in content
    assert "--skip-connectivity-test" not in content
    assert "APPROVED_ACTIONS_ENABLED=true" not in content
    assert "kubectl apply" not in content
    assert "kubectl delete" not in content
    assert "kubectl drain" not in content
    assert "kubectl cordon" not in content
    assert "systemctl restart" not in content
    assert "reboot" not in content


def test_workflow_preserves_results_and_uses_canonical_llm_history() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "RCA_LLM_BURN_IN_HISTORY_RUN_ID" in content
    assert "llm-burn-in-run-metadata.py" in content
    assert "uses: actions/download-artifact@v8" in content
    assert "uses: actions/upload-artifact@v7" in content
    assert "retention-days: 30" in content
    assert "GITHUB_STEP_SUMMARY" in content
    assert "persist-credentials: false" in content
    assert "BURN_IN_PRIVATE_DIR=%s/rca-operational-burn-in-%s" in content
    assert '"${RUNNER_TEMP}" "${GITHUB_RUN_ID}" >> "${GITHUB_ENV}"' in content
    assert "Remove private LLM history from self-hosted runner" in content
    assert "real-cluster-agent-evidence.json" in content
    assert "unlink(missing_ok=True)" in content


def test_ci_runs_agent_pod_runtime_observation_in_kind() -> None:
    workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    script = KIND_SMOKE.read_text(encoding="utf-8")

    assert "config: config/kind-multi-node.yaml" in workflow
    assert "name: kind-agent-fleet-runtime" in workflow
    assert "path: validation-results/kind-agent-runtime" in workflow
    assert "agent-soak-validation.py" in script
    assert "--discover-agent-pods" in script
    assert "--minimum-agent-pods" in script
    assert "--require-runtime-observation" in script
    assert "--retain-evidence" not in script
