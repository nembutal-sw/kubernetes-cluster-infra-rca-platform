import json
from pathlib import Path

import pytest

from scripts.cluster_compatibility import (
    build_cluster_fingerprint,
    evaluate_compatibility,
    load_catalog,
    validate_catalog,
)


ROOT = Path(__file__).resolve().parents[1]
CATALOG = load_catalog(ROOT / "config" / "platform-compatibility-matrix.json")


def node(
    *,
    version: str = "v1.31.0",
    runtime: str = "containerd://1.7.20",
    architecture: str = "amd64",
    provider_id: str = "",
    labels: dict[str, str] | None = None,
    annotations: dict[str, str] | None = None,
) -> dict:
    return {
        "metadata": {
            "name": "fixture-node",
            "labels": labels or {},
            "annotations": annotations or {},
        },
        "spec": {"providerID": provider_id},
        "status": {
            "nodeInfo": {
                "architecture": architecture,
                "operatingSystem": "linux",
                "osImage": "Fixture Linux",
                "kernelVersion": "6.8.0-fixture",
                "kubeletVersion": version,
                "containerRuntimeVersion": runtime,
            }
        },
    }


def pod(name: str, image: str, namespace: str = "kube-system") -> dict:
    return {
        "metadata": {"name": name, "namespace": namespace, "labels": {"k8s-app": name}},
        "spec": {"containers": [{"name": name, "image": image}]},
    }


@pytest.mark.parametrize(
    ("node_fixture", "pod_fixture", "platform", "cni", "runtime"),
    (
        (
            node(version="v1.31.8+rke2r1", architecture="arm64", provider_id="rke2://fixture-node"),
            pod("cilium-agent", "quay.io/cilium/cilium:v1.17"),
            "rke2",
            "cilium",
            "containerd",
        ),
        (
            node(version="v1.31.8+k3s1", provider_id="k3s://fixture-node"),
            pod("kube-flannel-ds", "docker.io/flannel/flannel:v0.26"),
            "k3s",
            "flannel",
            "containerd",
        ),
        (
            node(annotations={"kubeadm.alpha.kubernetes.io/cri-socket": "unix:///run/containerd/containerd.sock"}),
            pod("calico-node", "docker.io/calico/node:v3.29"),
            "kubeadm",
            "calico",
            "containerd",
        ),
        (
            node(provider_id="aws:///ap-northeast-2a/i-fixture", labels={"eks.amazonaws.com/nodegroup": "workers"}),
            pod("aws-node", "602401143452.dkr.ecr.ap-northeast-2.amazonaws.com/amazon-k8s-cni:v1.19"),
            "eks",
            "aws-vpc-cni",
            "containerd",
        ),
        (
            node(provider_id="azure:///subscriptions/redacted", labels={"kubernetes.azure.com/cluster": "fixture"}),
            pod("azure-cni-networkmonitor", "mcr.microsoft.com/containernetworking/azure-cni:v1.6"),
            "aks",
            "azure-cni",
            "containerd",
        ),
        (
            node(provider_id="gce://redacted/zone/fixture", labels={"cloud.google.com/gke-nodepool": "default"}),
            pod("anetd", "gke.gcr.io/anetd:v1"),
            "gke",
            "gke-dataplane-v2",
            "containerd",
        ),
        (
            node(
                runtime="cri-o://1.31.2",
                provider_id="aws:///redacted/fixture",
                labels={"node.openshift.io/os_id": "rhcos"},
            ),
            pod("ovnkube-node", "quay.io/openshift-release-dev/ocp-v4.0-art-dev:latest", "openshift-ovn-kubernetes"),
            "openshift",
            "ovn-kubernetes",
            "crio",
        ),
    ),
)
def test_detects_platform_runtime_and_cni_contract_fixtures(
    node_fixture: dict,
    pod_fixture: dict,
    platform: str,
    cni: str,
    runtime: str,
) -> None:
    fingerprint = build_cluster_fingerprint([node_fixture], [pod_fixture])

    assert fingerprint["platform"]["family"] == platform
    assert fingerprint["runtime_families"] == [runtime]
    assert cni in fingerprint["cni"]["families"]


def test_real_e2e_profile_requires_matching_dimensions() -> None:
    verified = build_cluster_fingerprint(
        [node(version="v1.33.5+rke2r1", architecture="arm64", provider_id="rke2://fixture-node")],
        [pod("cilium-agent", "quay.io/cilium/cilium:v1.17")],
    )
    unverified_arch = build_cluster_fingerprint(
        [node(version="v1.33.5+rke2r1", architecture="amd64", provider_id="rke2://fixture-node")],
        [pod("cilium-agent", "quay.io/cilium/cilium:v1.17")],
    )

    assert evaluate_compatibility(verified, CATALOG)["status"] == "verified_real"
    unverified = evaluate_compatibility(unverified_arch, CATALOG)
    assert unverified["status"] == "unverified"
    assert "architecture:amd64" in unverified["unverified_dimensions"]


def test_detects_embedded_flannel_from_node_annotation() -> None:
    fingerprint = build_cluster_fingerprint(
        [
            node(
                version="v1.35.5+k3s1",
                provider_id="k3s://fixture-node",
                annotations={"flannel.alpha.coreos.com/backend-type": "vxlan"},
            )
        ],
        [],
    )

    assert fingerprint["cni"]["families"] == ["flannel"]
    assert evaluate_compatibility(fingerprint, CATALOG)["status"] == "verified_real"


def test_contract_fixture_never_claims_real_support() -> None:
    fingerprint = build_cluster_fingerprint(
        [node(provider_id="aws:///redacted/fixture", labels={"eks.amazonaws.com/nodegroup": "workers"})],
        [pod("aws-node", "example.invalid/amazon-k8s-cni:fixture")],
    )

    assessment = evaluate_compatibility(fingerprint, CATALOG)

    assert assessment["status"] == "contract_fixture_only"
    assert assessment["validation_level"] == "contract_fixture"


def test_fingerprint_does_not_export_raw_labels_or_provider_ids() -> None:
    provider_id = "aws:///sensitive-zone/sensitive-instance"
    fingerprint = build_cluster_fingerprint(
        [node(provider_id=provider_id, labels={"eks.amazonaws.com/nodegroup": "sensitive-pool"})],
        [],
    )
    encoded = json.dumps(fingerprint)

    assert provider_id not in encoded
    assert "sensitive-pool" not in encoded
    assert fingerprint["provider_schemes"] == ["aws"]


def test_catalog_and_readiness_contract_are_valid() -> None:
    readiness = (ROOT / "scripts" / "real-cluster-readiness-check.py").read_text(encoding="utf-8")

    assert validate_catalog(CATALOG) == []
    assert '"taints": spec.get("taints", [])' in readiness
    assert 'report["signals"]["cluster_compatibility"]' in readiness
