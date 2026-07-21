import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-run-metadata.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_run_metadata", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("conclusion", ["success", "failure"])
def test_completed_manual_burn_in_run_is_accepted(conclusion):
    module = load_module()

    assert module.validate_run(
        {
            "path": ".github/workflows/llm-burn-in.yml",
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": conclusion,
        }
    ) == conclusion


@pytest.mark.parametrize(
    "override",
    [
        {"path": ".github/workflows/ci.yml"},
        {"event": "push"},
        {"status": "in_progress", "conclusion": None},
        {"conclusion": "cancelled"},
    ],
)
def test_untrusted_or_incomplete_history_run_is_rejected(override):
    module = load_module()
    payload = {
        "path": ".github/workflows/llm-burn-in.yml",
        "event": "workflow_dispatch",
        "status": "completed",
        "conclusion": "success",
    }
    payload.update(override)

    with pytest.raises(ValueError):
        module.validate_run(payload)
