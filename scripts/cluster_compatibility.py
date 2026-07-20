#!/usr/bin/env python3
"""Detect Kubernetes platform characteristics and evaluate tested compatibility."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = "cluster-platform-compatibility/v1"
VALIDATION_LEVELS = {"real_e2e", "contract_fixture", "planned"}
REQUIRED_PLATFORMS = {"rke2", "k3s", "kubeadm", "eks", "aks", "gke", "openshift"}
REQUIRED_COLLECTORS = {
    "node",
    "kubernetes",
    "systemd",
    "runtime",
    "kubelet",
    "kernel",
    "network",
    "conntrack",
    "disk",
    "inode",
    "memory",
    "process",
    "cni",
    "dns",
}

PLATFORM_PRIORITY = (
    "openshift",
    "eks",
    "aks",
    "gke",
    "rke2",
    "k3s",
    "microk8s",
    "k0s",
    "kubeadm",
)

CNI_PATTERNS: dict[str, tuple[str, ...]] = {
    "cilium": ("cilium",),
    "calico": ("calico", "tigera"),
    "flannel": ("flannel",),
    "canal": ("canal",),
    "aws-vpc-cni": ("aws-node", "amazon-k8s-cni"),
    "azure-cni": ("azure-cni", "azure-vnet", "azure-ip-masq"),
    "gke-dataplane-v2": ("anetd", "gke-networking"),
    "ovn-kubernetes": ("ovnkube", "ovn-kubernetes", "ovnkubernetes"),
    "antrea": ("antrea",),
    "weave": ("weave-net", "weave-kube"),
    "kube-router": ("kube-router",),
}


def build_cluster_fingerprint(
    node_items: list[dict[str, Any]],
    pod_items: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Build a redacted fingerprint from Kubernetes Node and Pod objects."""
    pods = pod_items or []
    platform = detect_platform(node_items, pods)
    cni = detect_cni(node_items, pods)
    runtimes = sorted({runtime for runtime in map(node_runtime, node_items) if runtime})
    architectures = sorted(
        {
            str(node.get("status", {}).get("nodeInfo", {}).get("architecture") or "").strip()
            for node in node_items
        }
        - {""}
    )
    operating_systems = sorted(
        {
            str(node.get("status", {}).get("nodeInfo", {}).get("operatingSystem") or "").strip()
            for node in node_items
        }
        - {""}
    )
    os_images = sorted(
        {
            str(node.get("status", {}).get("nodeInfo", {}).get("osImage") or "").strip()
            for node in node_items
        }
        - {""}
    )
    kubelet_versions = sorted(
        {
            str(node.get("status", {}).get("nodeInfo", {}).get("kubeletVersion") or "").strip()
            for node in node_items
        }
        - {""}
    )
    kernel_versions = sorted(
        {
            str(node.get("status", {}).get("nodeInfo", {}).get("kernelVersion") or "").strip()
            for node in node_items
        }
        - {""}
    )
    provider_schemes = sorted(
        {
            provider_scheme(str(node.get("spec", {}).get("providerID") or ""))
            for node in node_items
        }
        - {""}
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "node_count": len(node_items),
        "platform": platform,
        "runtime_families": runtimes or ["unknown"],
        "cni": cni,
        "architectures": architectures or ["unknown"],
        "operating_systems": operating_systems or ["unknown"],
        "os_images": os_images,
        "kubelet_versions": kubelet_versions,
        "kernel_versions": kernel_versions,
        "provider_schemes": provider_schemes,
        "heterogeneous": {
            "architecture": len(architectures) > 1,
            "runtime": len(runtimes) > 1,
            "operating_system": len(operating_systems) > 1,
        },
    }


def detect_platform(
    node_items: list[dict[str, Any]],
    pod_items: list[dict[str, Any]],
) -> dict[str, Any]:
    matches: dict[str, list[str]] = {name: [] for name in PLATFORM_PRIORITY}
    for node in node_items:
        metadata = node.get("metadata", {})
        labels = object_value(metadata.get("labels"))
        annotations = object_value(metadata.get("annotations"))
        node_info = node.get("status", {}).get("nodeInfo", {})
        version = str(node_info.get("kubeletVersion") or "").lower()
        provider = str(node.get("spec", {}).get("providerID") or "").lower()
        label_keys = {str(key).lower() for key in labels}
        annotation_keys = {str(key).lower() for key in annotations}

        if "rke2" in version or provider.startswith("rke2://"):
            add_evidence(matches, "rke2", "kubelet/provider signal")
        if "k3s" in version or provider.startswith("k3s://"):
            add_evidence(matches, "k3s", "kubelet/provider signal")
        if "microk8s" in version or any(key.startswith("microk8s.io/") for key in label_keys):
            add_evidence(matches, "microk8s", "kubelet/label signal")
        if "k0s" in version or any(key.startswith("k0sproject.io/") for key in label_keys):
            add_evidence(matches, "k0s", "kubelet/label signal")
        if provider.startswith("aws://") or any(key.startswith("eks.amazonaws.com/") for key in label_keys):
            add_evidence(matches, "eks", "AWS provider/EKS label signal")
        if provider.startswith("azure://") or any(key.startswith("kubernetes.azure.com/") for key in label_keys):
            add_evidence(matches, "aks", "Azure provider/AKS label signal")
        if provider.startswith("gce://") or "cloud.google.com/gke-nodepool" in label_keys:
            add_evidence(matches, "gke", "GCE provider/GKE label signal")
        if "node.openshift.io/os_id" in label_keys or any(key.startswith("machine.openshift.io/") for key in label_keys):
            add_evidence(matches, "openshift", "OpenShift node label signal")
        if "kubeadm.alpha.kubernetes.io/cri-socket" in annotation_keys:
            add_evidence(matches, "kubeadm", "kubeadm CRI annotation signal")

    for pod in pod_items:
        metadata = pod.get("metadata", {})
        namespace = str(metadata.get("namespace") or "").lower()
        name = str(metadata.get("name") or "").lower()
        if namespace.startswith("openshift-") or "ovnkube" in name:
            add_evidence(matches, "openshift", "OpenShift namespace/network pod signal")

    for family in PLATFORM_PRIORITY:
        if matches[family]:
            confidence = "high" if family in {"rke2", "k3s", "eks", "aks", "gke", "openshift"} else "medium"
            return {
                "family": family,
                "confidence": confidence,
                "evidence": matches[family],
            }
    return {
        "family": "unknown",
        "confidence": "low",
        "evidence": ["No distribution-specific Kubernetes signal was detected."],
    }


def detect_cni(
    node_items: list[dict[str, Any]],
    pod_items: list[dict[str, Any]],
) -> dict[str, Any]:
    matches: dict[str, list[str]] = {name: [] for name in CNI_PATTERNS}
    for node in node_items:
        annotations = object_value(node.get("metadata", {}).get("annotations"))
        annotation_keys = {str(key).lower() for key in annotations}
        if any(key.startswith("flannel.alpha.coreos.com/") for key in annotation_keys):
            add_evidence(matches, "flannel", "node flannel annotation")
    for pod in pod_items:
        metadata = pod.get("metadata", {})
        namespace = str(metadata.get("namespace") or "").lower()
        if namespace != "kube-system" and not namespace.startswith("openshift-"):
            continue
        name = str(metadata.get("name") or "").lower()
        labels = object_value(metadata.get("labels"))
        label_text = " ".join(f"{key}={value}" for key, value in labels.items()).lower()
        containers = pod.get("spec", {}).get("containers", [])
        container_text = " ".join(
            f"{container.get('name', '')} {container.get('image', '')}"
            for container in containers
            if isinstance(container, dict)
        ).lower()
        searchable = f"{namespace} {name} {label_text} {container_text}"
        for family, patterns in CNI_PATTERNS.items():
            if any(pattern in searchable for pattern in patterns):
                add_evidence(matches, family, f"{namespace}/{name}")

    families = [family for family, evidence in matches.items() if evidence]
    evidence = [item for family in families for item in matches[family]]
    return {
        "families": families or ["unknown"],
        "confidence": "high" if families else "low",
        "evidence": evidence[:20] or ["No known CNI workload signal was detected."],
    }


def evaluate_compatibility(
    fingerprint: dict[str, Any],
    catalog: dict[str, Any],
) -> dict[str, Any]:
    errors = validate_catalog(catalog)
    if errors:
        return {
            "status": "invalid_catalog",
            "validation_level": "unknown",
            "catalog_errors": errors,
            "matched_profiles": [],
            "unverified_dimensions": [],
        }

    family = str(fingerprint.get("platform", {}).get("family") or "unknown")
    platform = object_value(object_value(catalog.get("platforms")).get(family))
    if not platform:
        return {
            "status": "unverified",
            "validation_level": "planned",
            "matched_profiles": [],
            "unverified_dimensions": [f"platform:{family}"],
            "notes": ["Run a read-only canary before enabling this platform in production."],
        }

    level = str(platform.get("validation_level") or "planned")
    profiles = [profile for profile in list_value(platform.get("profiles")) if isinstance(profile, dict)]
    matched = [profile for profile in profiles if profile_matches(profile, fingerprint)]
    unverified = profile_gaps(fingerprint, profiles)

    if level == "real_e2e" and matched and not unverified:
        status = "verified_real"
    elif level == "real_e2e" and matched:
        status = "partially_verified"
    elif level == "contract_fixture":
        status = "contract_fixture_only"
    else:
        status = "unverified"

    notes = [str(note) for note in list_value(platform.get("notes"))]
    if status != "verified_real":
        notes.append("Use a node-scoped canary and preserve APPROVED_ACTIONS_ENABLED=false.")
    return {
        "status": status,
        "validation_level": level,
        "matched_profiles": matched,
        "unverified_dimensions": unverified,
        "required_collectors": list_value(object_value(catalog.get("collector_contract")).get("required")),
        "optional_collectors": list_value(object_value(catalog.get("collector_contract")).get("optional")),
        "notes": unique(notes),
    }


def validate_catalog(catalog: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if catalog.get("schema_version") != SCHEMA_VERSION:
        errors.append(f"schema_version must be {SCHEMA_VERSION}")
    platforms = object_value(catalog.get("platforms"))
    for family in sorted(REQUIRED_PLATFORMS - set(platforms)):
        errors.append(f"required platform is missing: {family}")
    contract = object_value(catalog.get("collector_contract"))
    required = set(list_value(contract.get("required")))
    for collector in sorted(REQUIRED_COLLECTORS - required):
        errors.append(f"required collector is missing: {collector}")
    for family, value in platforms.items():
        platform = object_value(value)
        level = platform.get("validation_level")
        if level not in VALIDATION_LEVELS:
            errors.append(f"platforms.{family}.validation_level is invalid")
        for index, profile in enumerate(list_value(platform.get("profiles"))):
            if not isinstance(profile, dict):
                errors.append(f"platforms.{family}.profiles[{index}] must be an object")
                continue
            for key in ("architecture", "runtime", "result"):
                if not str(profile.get(key) or "").strip():
                    errors.append(f"platforms.{family}.profiles[{index}].{key} is required")
            if profile.get("result") != "passed":
                errors.append(f"platforms.{family}.profiles[{index}].result must be passed")
    return errors


def profile_matches(profile: dict[str, Any], fingerprint: dict[str, Any]) -> bool:
    architectures = set(list_value(fingerprint.get("architectures")))
    runtimes = set(list_value(fingerprint.get("runtime_families")))
    cnis = set(list_value(fingerprint.get("cni", {}).get("families")))
    architecture = str(profile.get("architecture") or "")
    runtime = str(profile.get("runtime") or "")
    cni = str(profile.get("cni") or "")
    return (
        architecture in architectures
        and runtime in runtimes
        and (not cni or cni == "any" or cni in cnis or "unknown" in cnis)
    )


def profile_gaps(fingerprint: dict[str, Any], profiles: list[dict[str, Any]]) -> list[str]:
    covered_architectures = {str(profile.get("architecture") or "") for profile in profiles}
    covered_runtimes = {str(profile.get("runtime") or "") for profile in profiles}
    covered_cnis = {str(profile.get("cni") or "") for profile in profiles if profile.get("cni")}
    gaps = []
    for architecture in list_value(fingerprint.get("architectures")):
        if architecture not in covered_architectures:
            gaps.append(f"architecture:{architecture}")
    for runtime in list_value(fingerprint.get("runtime_families")):
        if runtime not in covered_runtimes:
            gaps.append(f"runtime:{runtime}")
    for cni in list_value(fingerprint.get("cni", {}).get("families")):
        if cni != "unknown" and "any" not in covered_cnis and cni not in covered_cnis:
            gaps.append(f"cni:{cni}")
    return unique(gaps)


def node_runtime(node: dict[str, Any]) -> str:
    value = str(node.get("status", {}).get("nodeInfo", {}).get("containerRuntimeVersion") or "").lower()
    family = value.split("://", 1)[0].strip()
    aliases = {
        "cri-o": "crio",
        "cri-o-containerd": "crio",
        "cri-dockerd": "cri-dockerd",
        "docker": "docker",
        "containerd": "containerd",
    }
    return aliases.get(family, family or "")


def provider_scheme(provider_id: str) -> str:
    if "://" not in provider_id:
        return ""
    return provider_id.split("://", 1)[0].lower()


def add_evidence(matches: dict[str, list[str]], family: str, value: str) -> None:
    if value not in matches[family]:
        matches[family].append(value)


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def load_catalog(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_items(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict):
        items = payload.get("items", [])
    else:
        items = payload
    return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Generate a redacted Kubernetes compatibility report.")
    parser.add_argument("--nodes-json", type=Path, help="kubectl get nodes -o json output.")
    parser.add_argument("--pods-json", type=Path, help="kubectl get pods -A -o json output.")
    parser.add_argument(
        "--catalog",
        type=Path,
        default=root / "config" / "platform-compatibility-matrix.json",
        help="Compatibility catalog JSON path.",
    )
    parser.add_argument("--output", default="-", help="JSON output path, or '-' for stdout.")
    parser.add_argument("--validate-catalog", action="store_true", help="Validate only the compatibility catalog.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    catalog = load_catalog(args.catalog)
    errors = validate_catalog(catalog)
    if args.validate_catalog:
        result: dict[str, Any] = {
            "status": "passed" if not errors else "failed",
            "schema_version": catalog.get("schema_version"),
            "errors": errors,
        }
    else:
        if args.nodes_json is None:
            raise SystemExit("--nodes-json is required unless --validate-catalog is used")
        fingerprint = build_cluster_fingerprint(load_items(args.nodes_json), load_items(args.pods_json))
        result = {
            "status": "passed" if not errors else "failed",
            "fingerprint": fingerprint,
            "compatibility": evaluate_compatibility(fingerprint, catalog),
        }
    encoded = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output == "-":
        print(encoded)
    else:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded + "\n", encoding="utf-8")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
