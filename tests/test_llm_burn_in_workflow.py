from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "llm-burn-in.yml"


def test_manual_workflow_has_explicit_live_call_controls() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "workflow_dispatch:" in content
    assert "schedule:" not in content
    assert 'default: true\n        type: boolean' in content
    assert "confirm_live_calls" in content
    assert "change_reference" in content
    assert "change_reference_pattern" in content
    assert "provider_call_budget must be 0 or 1" in content
    assert "RCA_SMOKE_PASSWORD secret is required" in content
    assert "cancel-in-progress: false" in content
    assert "inputs.dry_run && 'llm-burn-in-preview' || 'llm-burn-in'" in content
    assert "Live campaigns must run from the default branch" in content
    assert "self-hosted-rca-demo" in content
    assert "inputs.runner == 'self-hosted-rca-demo'" in content
    assert "if: ${{ inputs.runner == 'github-hosted' }}" in content
    assert "Validate self-hosted Python runtime" in content
    assert "python3 -c 'import sys; assert sys.version_info >= (3, 11)" in content
    assert "Self-hosted live campaigns are restricted to loopback HTTP endpoints" in content
    assert "use_tailscale must be disabled for a self-hosted live campaign" in content
    assert "inputs.runner == 'github-hosted' && inputs.use_tailscale" in content
    assert "--require-new-time-bucket" in content
    assert "--planning-baseline config/llm-burn-in-planning-baseline.json" in content
    assert "scripts/llm-burn-in-planning-baseline.py" in content


def test_workflow_reuses_only_completed_manual_burn_in_history() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "history_run_id" in content
    assert '.github/workflows/llm-burn-in.yml' in content
    assert 'run_event}" != "workflow_dispatch"' in content
    assert 'run_conclusion}" != "success"' in content
    assert 'run_conclusion}" != "failure"' in content
    assert "llm-burn-in-revalidate.py" in content
    assert "Revalidate recoverable samples from a failed history run" in content
    assert "llm-burn-in-results" in content
    assert "llm-burn-in-history.py" in content
    assert "validation-results/previous-burn-in/portable-history" in content
    assert "--history validation-results/validated-history" in content
    assert "--history validation-results/previous-burn-in" not in content


def test_workflow_does_not_pass_credentials_as_cli_arguments() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "RCA_ADMIN_PASSWORD: ${{ secrets.RCA_SMOKE_PASSWORD }}" in content
    assert "--password" not in content
    assert "--provider-call-budget" in content
    assert "uses: actions/upload-artifact@v7" in content
    assert "python scripts/llm-burn-in" not in content
