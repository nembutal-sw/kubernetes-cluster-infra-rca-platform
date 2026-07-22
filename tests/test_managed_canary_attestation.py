import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "managed-canary-attestation.py"


def load_module():
    spec = importlib.util.spec_from_file_location("managed_canary_attestation", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def readiness(platform: str = "eks", variant: str = "managed_node_group") -> dict:
    return {
        "status": "warning",
        "kubectl_context": "sensitive-context",
        "warnings": ["cluster compatibility is not fully verified"],
        "failures": [],
        "signals": {
            "nodes": [{"name": "sensitive-node-name"}],
            "cluster_compatibility": {
                "fingerprint": {
                    "node_count": 3,
                    "platform": {
                        "family": platform,
                        "confidence": "high",
                        "variant": variant,
                        "variants": [variant],
                    },
                    "architectures": ["amd64"],
                    "runtime_families": ["containerd"],
                    "cni": {"families": ["aws-vpc-cni"]},
                    "operating_systems": ["linux"],
                    "kubelet_versions": ["v1.33.1"],
                    "provider_schemes": ["aws"],
                },
                "assessment": {
                    "status": "contract_fixture_only",
                    "validation_level": "contract_fixture",
                },
            },
        },
    }


def lifecycle(*, applied: bool, cleanup_state: str | None = None) -> dict:
    return {
        "status": "passed",
        "node": "sensitive-node-name",
        "namespace": "sensitive-canary-namespace",
        "resources_kept": False,
        "cleanup": {
            "state": cleanup_state or ("completed" if applied else "not_applicable"),
            "helm_state": "completed" if applied else "not_applicable",
            "namespace_state": "completed" if applied else "not_applicable",
            "platform_cluster_state": "completed" if applied else "not_applicable",
            "warning": None,
        },
    }


def plan(*, applied: bool) -> dict:
    return {
        "apply": applied,
        "mode": "node-diagnostics",
        "base_url": "https://sensitive.internal.example",
        "mutations": [
            "isolated namespace",
            "source ConfigMap",
            "single-node DaemonSet",
            "Platform test cluster",
        ],
    }


def bundle(*, passed: bool = True) -> dict:
    return {
        "results": [
            {
                "passed": passed,
                "entry_count": 8,
                "signature_present": True,
                "signature_verified": True,
            }
        ]
    }


def platform_matrix() -> dict:
    return json.loads((ROOT / "config" / "platform-compatibility-matrix.json").read_text(encoding="utf-8"))


def test_preflight_attestation_is_redacted_and_never_promotable() -> None:
    module = load_module()

    result = module.build_attestation(
        expected_platform="eks",
        readiness=readiness(),
        lifecycle=lifecycle(applied=False),
        plan=plan(applied=False),
        platform_matrix=platform_matrix(),
        bundle_verification=None,
        applied=False,
        source_hashes={"readiness_sha256": "a" * 64},
    )

    assert result["status"] == "passed"
    assert result["mode"] == "preflight"
    assert result["dimensions"]["node_count"] == 3
    assert result["promotion"]["eligible_for_manual_review"] is False
    assert result["promotion"]["automatic_matrix_update"] is False
    encoded = json.dumps(result)
    assert "sensitive-node-name" not in encoded
    assert "sensitive-canary-namespace" not in encoded
    assert "sensitive.internal.example" not in encoded


def test_readiness_rejects_unexpected_managed_platform() -> None:
    module = load_module()

    details, failures = module.assess_readiness("aks", readiness("eks"), platform_matrix())

    assert details["detected_platform"] == "eks"
    assert "detected platform eks does not match expected platform aks" in failures


def test_readiness_rejects_eks_fargate_only_cluster() -> None:
    module = load_module()

    details, failures = module.assess_readiness(
        "eks",
        readiness("eks", "fargate"),
        platform_matrix(),
    )

    assert details["dimensions"]["platform_variant"] == "fargate"
    assert "EKS Fargate does not support the Agent DaemonSet" in failures


def test_readiness_allows_mixed_eks_cluster_with_daemonset_eligible_nodes() -> None:
    module = load_module()
    payload = readiness("eks", "mixed")
    platform = payload["signals"]["cluster_compatibility"]["fingerprint"]["platform"]
    platform["variants"] = ["fargate", "managed_node_group"]

    details, failures = module.assess_readiness("eks", payload, platform_matrix())

    assert details["dimensions"]["platform_variants"] == ["fargate", "managed_node_group"]
    assert "EKS Fargate does not support the Agent DaemonSet" not in failures


def test_applied_canary_requires_cleanup_and_verified_bundle() -> None:
    module = load_module()

    result = module.build_attestation(
        expected_platform="eks",
        readiness=readiness(),
        lifecycle=lifecycle(applied=True),
        plan=plan(applied=True),
        platform_matrix=platform_matrix(),
        bundle_verification=bundle(),
        applied=True,
        source_hashes={"readiness_sha256": "a" * 64},
    )

    assert result["status"] == "passed"
    assert result["lifecycle"]["cleanup_state"] == "completed"
    assert result["lifecycle"]["helm_cleanup_state"] == "completed"
    assert result["lifecycle"]["evidence_bundle"]["passed"] is True
    assert result["promotion"]["eligible_for_manual_review"] is True
    assert result["promotion"]["automatic_matrix_update"] is False
    assert result["read_only"] is False
    assert result["node_mutation_allowed"] is False
    assert result["action_execution"] == "disabled"


def test_applied_canary_fails_when_cleanup_or_bundle_is_incomplete() -> None:
    module = load_module()

    result = module.build_attestation(
        expected_platform="eks",
        readiness=readiness(),
        lifecycle=lifecycle(applied=True, cleanup_state="pending"),
        plan=plan(applied=True),
        platform_matrix=platform_matrix(),
        bundle_verification=bundle(passed=False),
        applied=True,
        source_hashes={"readiness_sha256": "a" * 64},
    )

    assert result["status"] == "failed"
    assert "lifecycle cleanup state must be completed" in result["failures"]
    assert "applied canary evidence bundle verification failed" in result["failures"]
    assert result["promotion"]["eligible_for_manual_review"] is False
