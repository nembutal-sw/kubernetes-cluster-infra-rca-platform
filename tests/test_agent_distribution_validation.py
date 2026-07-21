from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "validate-agent-distributions.sh"


def test_opensuse_validation_uses_only_the_base_oss_repository() -> None:
    content = SCRIPT.read_text(encoding="utf-8")

    assert 'run_case "openSUSE Leap 15.6" "opensuse/leap:15.6"' in content
    assert "modifyrepo --disable --all" in content
    assert "modifyrepo --enable repo-oss" in content
    assert "install --no-recommends -y python311" in content
    assert "|| true" not in content
