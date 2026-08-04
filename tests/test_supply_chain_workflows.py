from __future__ import annotations

import importlib.util
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "verify-supply-chain-workflows.py"
SPEC = importlib.util.spec_from_file_location("verify_supply_chain_workflows", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def copy_contract(tmp_path: Path) -> Path:
    for relative_path in [
        ".github/workflows/security.yml",
        ".github/workflows/publish-images.yml",
        ".github/workflows/release.yml",
        ".github/dependabot.yml",
        "Dockerfile.agent",
        "Dockerfile.web-console",
    ]:
        source = ROOT / relative_path
        destination = tmp_path / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    return tmp_path


def replace(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    assert old in content
    path.write_text(content.replace(old, new), encoding="utf-8")


def test_repository_supply_chain_contract_passes() -> None:
    assert MODULE.verify(ROOT) == []


def test_invalid_workflow_yaml_fails_clearly(tmp_path: Path) -> None:
    root = copy_contract(tmp_path)
    (root / ".github/workflows/publish-images.yml").write_text("jobs: [unterminated", encoding="utf-8")

    errors = MODULE.verify(root)

    assert any("publish-images.yml is not valid UTF-8 YAML" in error for error in errors)


def test_publish_workflow_requires_same_repository_main_push(tmp_path: Path) -> None:
    root = copy_contract(tmp_path)
    path = root / ".github/workflows/publish-images.yml"
    replace(
        path,
        "github.event.workflow_run.head_repository.full_name == github.repository",
        "github.event.workflow_run.head_repository.full_name != github.repository",
    )

    errors = MODULE.verify(root)

    assert any("same-repository main push CI" in error for error in errors)


def test_publish_workflow_requires_successful_ci_for_manual_runs(tmp_path: Path) -> None:
    root = copy_contract(tmp_path)
    path = root / ".github/workflows/publish-images.yml"
    replace(path, "-f status=success", "-f status=failure")

    errors = MODULE.verify(root)

    assert any("manual edge publishing" in error for error in errors)


def test_publish_workflow_requires_existing_dockerfiles(tmp_path: Path) -> None:
    root = copy_contract(tmp_path)
    (root / "Dockerfile.agent").unlink()

    errors = MODULE.verify(root)

    assert "edge workflow Dockerfile is missing: Dockerfile.agent" in errors


def test_missing_publish_workflow_fails_clearly(tmp_path: Path) -> None:
    root = copy_contract(tmp_path)
    (root / ".github/workflows/publish-images.yml").unlink()

    errors = MODULE.verify(root)

    assert ".github/workflows/publish-images.yml is missing" in errors
