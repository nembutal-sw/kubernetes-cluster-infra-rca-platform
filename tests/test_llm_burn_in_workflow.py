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
    assert "provider_call_budget must be between 0 and 3" in content
    assert "RCA_SMOKE_PASSWORD secret is required" in content
    assert "cancel-in-progress: false" in content


def test_workflow_reuses_only_successful_manual_burn_in_history() -> None:
    content = WORKFLOW.read_text(encoding="utf-8")

    assert "history_run_id" in content
    assert '.github/workflows/llm-burn-in.yml' in content
    assert 'run_event}" != "workflow_dispatch"' in content
    assert 'run_conclusion}" != "success"' in content
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
