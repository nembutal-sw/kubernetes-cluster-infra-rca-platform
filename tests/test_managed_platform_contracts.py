import json
import subprocess
import sys
from pathlib import Path

import pytest

from scripts.cluster_compatibility import build_cluster_fingerprint, evaluate_compatibility, load_catalog


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "tests" / "fixtures" / "managed-platforms"
EKS_FIXTURE_PATH = FIXTURE_DIR / "eks-contracts.json"
AKS_FIXTURE_PATH = FIXTURE_DIR / "aks-contracts.json"
CATALOG = load_catalog(ROOT / "config" / "platform-compatibility-matrix.json")
EKS_FIXTURES = json.loads(EKS_FIXTURE_PATH.read_text(encoding="utf-8"))["fixtures"]
AKS_FIXTURES = json.loads(AKS_FIXTURE_PATH.read_text(encoding="utf-8"))["fixtures"]
FIXTURES = EKS_FIXTURES + AKS_FIXTURES


@pytest.mark.parametrize("fixture", FIXTURES, ids=lambda fixture: fixture["fixture_id"])
def test_documented_contract_fixture_is_detected_without_real_promotion(fixture: dict) -> None:
    snapshot = fixture["synthetic_snapshot"]
    fingerprint = build_cluster_fingerprint(snapshot["nodes"]["items"], snapshot["pods"]["items"])
    assessment = evaluate_compatibility(fingerprint, CATALOG)

    expected = fixture["expected"]
    assert fingerprint["platform"]["family"] == expected["platform_family"]
    assert fingerprint["platform"]["variant"] == expected["platform_variant"]
    assert fingerprint["architectures"] == expected["architectures"]
    if "operating_systems" in expected:
        assert fingerprint["operating_systems"] == expected["operating_systems"]
    assert fingerprint["runtime_families"] == expected["runtime_families"]
    assert fingerprint["cni"]["families"] == expected["cni_families"]
    assert assessment["status"] == "contract_fixture_only"
    assert assessment["validation_level"] == "contract_fixture"
    assert assessment["status"] != "verified_real"


def test_eks_contracts_capture_auto_mode_and_fargate_boundaries() -> None:
    by_id = {fixture["fixture_id"]: fixture for fixture in EKS_FIXTURES}
    auto_mode = by_id["eks-auto-mode-amd64"]["agent_deployment"]
    fargate = by_id["eks-fargate-amd64"]["agent_deployment"]

    assert auto_mode == {
        "daemonset_supported": True,
        "recommended_mode": "safe",
        "host_evidence": "restricted_unverified",
        "real_canary_required": True,
        "action_execution": "disabled",
    }
    assert fargate == {
        "daemonset_supported": False,
        "recommended_mode": "unsupported",
        "host_evidence": "unavailable",
        "real_canary_required": True,
        "action_execution": "disabled",
    }


def test_eks_mixed_compute_fingerprint_preserves_each_normalized_variant() -> None:
    nodes = [fixture["synthetic_snapshot"]["nodes"]["items"][0] for fixture in EKS_FIXTURES]

    fingerprint = build_cluster_fingerprint(nodes, [])

    assert fingerprint["platform"]["variant"] == "mixed"
    assert fingerprint["platform"]["variants"] == [
        "auto_mode",
        "fargate",
        "managed_node_group",
    ]


def test_aks_contracts_capture_safe_and_unsupported_boundaries() -> None:
    by_id = {fixture["fixture_id"]: fixture for fixture in AKS_FIXTURES}

    nap_deployment = by_id["aks-node-auto-provisioning-amd64-cilium"]["agent_deployment"]

    assert nap_deployment["recommended_mode"] == "safe"
    assert by_id["aks-virtual-node-linux"]["agent_deployment"]["daemonset_supported"] is False
    assert by_id["aks-user-pool-windows-amd64"]["agent_deployment"]["daemonset_supported"] is False


def test_aks_mixed_node_pool_fingerprint_preserves_normalized_variants() -> None:
    nodes = [fixture["synthetic_snapshot"]["nodes"]["items"][0] for fixture in AKS_FIXTURES]

    fingerprint = build_cluster_fingerprint(nodes, [])

    assert fingerprint["platform"]["variant"] == "mixed"
    assert fingerprint["platform"]["variants"] == [
        "node_auto_provisioning",
        "system_node_pool",
        "user_node_pool",
        "virtual_node",
        "windows_node_pool",
    ]


def test_managed_platform_contract_cli_passes_all_fixture_catalogs() -> None:
    completed = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "managed-platform-contract-check.py")],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode == 0, completed.stderr or completed.stdout
    report = json.loads(completed.stdout)
    assert report["status"] == "passed"
    assert report["catalog_count"] == 2
    assert report["platforms"] == ["aks", "eks"]
    assert report["fixture_count"] == 9
    assert report["passed_fixture_count"] == 9
    assert report["failures"] == []


def test_managed_platform_contract_rejects_stale_official_review(tmp_path: Path) -> None:
    payload = json.loads(EKS_FIXTURE_PATH.read_text(encoding="utf-8"))
    payload["fixtures"][0]["reviewed_on"] = "2020-01-01"
    stale = tmp_path / "stale-eks-contracts.json"
    stale.write_text(json.dumps(payload), encoding="utf-8")

    completed = subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "managed-platform-contract-check.py"),
            "--fixtures",
            str(stale),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode == 1
    report = json.loads(completed.stdout)
    assert report["status"] == "failed"
    assert any("official sources must be reviewed" in failure for failure in report["failures"])


def test_managed_platform_contract_rejects_catalog_platform_mismatch(tmp_path: Path) -> None:
    payload = json.loads(EKS_FIXTURE_PATH.read_text(encoding="utf-8"))
    payload["platform"] = "aks"
    mismatched = tmp_path / "mismatched-contracts.json"
    mismatched.write_text(json.dumps(payload), encoding="utf-8")

    completed = subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "managed-platform-contract-check.py"),
            "--fixtures",
            str(mismatched),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode == 1
    report = json.loads(completed.stdout)
    assert any("does not match catalog platform" in failure for failure in report["failures"])
