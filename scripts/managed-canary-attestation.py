#!/usr/bin/env python3
"""Build a redacted, promotion-safe attestation for a managed cluster canary."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "managed-cluster-canary/v1"
SUPPORTED_PLATFORMS = {"eks", "aks", "gke", "openshift"}
ALLOWED_MUTATIONS = {
    "isolated namespace",
    "source ConfigMap",
    "single-node DaemonSet",
    "Platform test cluster",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create a redacted managed Kubernetes canary attestation.")
    parser.add_argument("--expected-platform", required=True, choices=sorted(SUPPORTED_PLATFORMS))
    parser.add_argument("--readiness", type=Path, required=True)
    parser.add_argument("--lifecycle", type=Path)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--platform-matrix", type=Path, required=True)
    parser.add_argument("--bundle-verification", type=Path)
    parser.add_argument("--applied", action="store_true")
    parser.add_argument("--readiness-only", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is unavailable or invalid: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return payload


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return sorted({str(item) for item in value if str(item).strip()})


def bundle_status(payload: dict[str, Any] | None) -> dict[str, Any]:
    if payload is None:
        return {"provided": False, "passed": False, "entry_count": 0, "signature_verified": False}
    results = payload.get("results")
    first = results[0] if isinstance(results, list) and results and isinstance(results[0], dict) else {}
    return {
        "provided": True,
        "passed": first.get("passed") is True,
        "entry_count": int(first.get("entry_count") or 0),
        "signature_present": first.get("signature_present") is True,
        "signature_verified": first.get("signature_verified") is True,
    }


def assess_readiness(
    expected_platform: str,
    readiness: dict[str, Any],
    platform_matrix: dict[str, Any],
) -> tuple[dict[str, Any], list[str]]:
    failures: list[str] = []
    compatibility_signal = object_value(object_value(readiness.get("signals")).get("cluster_compatibility"))
    fingerprint = object_value(compatibility_signal.get("fingerprint"))
    assessment = object_value(compatibility_signal.get("assessment"))
    platform = object_value(fingerprint.get("platform"))
    detected_platform = str(platform.get("family") or "unknown")
    confidence = str(platform.get("confidence") or "unknown")
    if readiness.get("status") == "failed" or readiness.get("failures"):
        failures.append("real-cluster readiness contains blocking failures")
    if detected_platform != expected_platform:
        failures.append(
            f"detected platform {detected_platform} does not match expected platform {expected_platform}"
        )
    if confidence != "high":
        failures.append("managed platform detection confidence must be high")
    if int(fingerprint.get("node_count") or 0) < 1:
        failures.append("cluster fingerprint does not contain a node")
    contract = object_value(platform_matrix.get("collector_contract"))
    if contract.get("action_execution") != "disabled":
        failures.append("platform matrix must keep action execution disabled")
    platform_entry = object_value(object_value(platform_matrix.get("platforms")).get(expected_platform))
    if not platform_entry:
        failures.append("expected platform is missing from the compatibility matrix")
    readiness_warnings = readiness.get("warnings") if isinstance(readiness.get("warnings"), list) else []
    readiness_failures = readiness.get("failures") if isinstance(readiness.get("failures"), list) else []
    return (
        {
            "detected_platform": detected_platform,
            "detection_confidence": confidence,
            "dimensions": {
                "node_count": int(fingerprint.get("node_count") or 0),
                "architectures": string_list(fingerprint.get("architectures")),
                "runtime_families": string_list(fingerprint.get("runtime_families")),
                "cni_families": string_list(object_value(fingerprint.get("cni")).get("families")),
                "operating_systems": string_list(fingerprint.get("operating_systems")),
                "kubelet_versions": string_list(fingerprint.get("kubelet_versions")),
                "provider_schemes": string_list(fingerprint.get("provider_schemes")),
            },
            "readiness": {
                "status": readiness.get("status", "unknown"),
                "warning_count": len(readiness_warnings),
                "failure_count": len(readiness_failures),
                "compatibility_status": assessment.get("status", "unknown"),
                "validation_level": assessment.get("validation_level", "unknown"),
            },
        },
        failures,
    )


def build_attestation(
    *,
    expected_platform: str,
    readiness: dict[str, Any],
    lifecycle: dict[str, Any],
    plan: dict[str, Any],
    platform_matrix: dict[str, Any],
    bundle_verification: dict[str, Any] | None,
    applied: bool,
    source_hashes: dict[str, str],
) -> dict[str, Any]:
    readiness_result, failures = assess_readiness(expected_platform, readiness, platform_matrix)

    if lifecycle.get("status") != "passed":
        failures.append("Agent lifecycle did not pass")
    if lifecycle.get("resources_kept") is True:
        failures.append("managed canary resources must not be kept")
    cleanup = object_value(lifecycle.get("cleanup"))
    expected_cleanup = "completed" if applied else "not_applicable"
    if cleanup.get("state") != expected_cleanup:
        failures.append(f"lifecycle cleanup state must be {expected_cleanup}")
    if applied and cleanup.get("namespace_state") != "completed":
        failures.append("canary namespace cleanup must be completed")
    if applied and cleanup.get("helm_state") != "completed":
        failures.append("Helm canary release cleanup must be completed")
    if applied and cleanup.get("platform_cluster_state") != "completed":
        failures.append("Platform test cluster cleanup must be completed")
    if cleanup.get("warning"):
        failures.append("lifecycle cleanup reported a warning")

    if plan.get("apply") is not applied:
        failures.append("lifecycle plan apply mode does not match the workflow mode")
    if plan.get("mode") not in {"safe", "node-diagnostics"}:
        failures.append("lifecycle plan mode is invalid")
    mutations = set(string_list(plan.get("mutations")))
    if mutations != ALLOWED_MUTATIONS:
        failures.append("lifecycle plan mutations exceed or omit the approved canary scope")

    bundle = bundle_status(bundle_verification)
    if applied and not bundle["provided"]:
        failures.append("applied canary is missing evidence bundle verification")
    if applied and not bundle["passed"]:
        failures.append("applied canary evidence bundle verification failed")

    eligible = applied and not failures
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": "passed" if not failures else "failed",
        "read_only": not applied,
        "node_mutation_allowed": False,
        "action_execution": "disabled",
        "expected_platform": expected_platform,
        "detected_platform": readiness_result["detected_platform"],
        "detection_confidence": readiness_result["detection_confidence"],
        "mode": "applied_canary" if applied else "preflight",
        "dimensions": readiness_result["dimensions"],
        "readiness": readiness_result["readiness"],
        "lifecycle": {
            "status": lifecycle.get("status", "unknown"),
            "cleanup_state": cleanup.get("state", "unknown"),
            "helm_cleanup_state": cleanup.get("helm_state", "unknown"),
            "namespace_cleanup_state": cleanup.get("namespace_state", "unknown"),
            "platform_cluster_cleanup_state": cleanup.get("platform_cluster_state", "unknown"),
            "resources_kept": lifecycle.get("resources_kept") is True,
            "evidence_bundle": bundle,
        },
        "promotion": {
            "eligible_for_manual_review": eligible,
            "automatic_matrix_update": False,
            "required_review": "platform-owner and security-owner",
        },
        "source_integrity": source_hashes,
        "failures": failures,
    }


def main() -> int:
    args = parse_args()
    source_paths = {
        "readiness_sha256": args.readiness,
        "platform_matrix_sha256": args.platform_matrix,
    }
    try:
        readiness = load_json(args.readiness, "readiness report")
        platform_matrix = load_json(args.platform_matrix, "platform matrix")
        if args.readiness_only:
            readiness_result, failures = assess_readiness(args.expected_platform, readiness, platform_matrix)
            attestation = {
                "schema_version": SCHEMA_VERSION,
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "status": "passed" if not failures else "failed",
                "read_only": True,
                "node_mutation_allowed": False,
                "action_execution": "disabled",
                "expected_platform": args.expected_platform,
                "detected_platform": readiness_result["detected_platform"],
                "detection_confidence": readiness_result["detection_confidence"],
                "mode": "readiness_gate",
                "dimensions": readiness_result["dimensions"],
                "readiness": readiness_result["readiness"],
                "promotion": {
                    "eligible_for_manual_review": False,
                    "automatic_matrix_update": False,
                },
                "source_integrity": {name: file_sha256(path) for name, path in source_paths.items()},
                "failures": failures,
            }
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(attestation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            print(json.dumps(attestation, ensure_ascii=False, indent=2))
            return 0 if attestation["status"] == "passed" else 1
        if args.lifecycle is None or args.plan is None:
            raise ValueError("lifecycle and plan are required unless --readiness-only is used")
        source_paths["lifecycle_sha256"] = args.lifecycle
        source_paths["plan_sha256"] = args.plan
        lifecycle = load_json(args.lifecycle, "lifecycle summary")
        plan = load_json(args.plan, "lifecycle plan")
        bundle = (
            load_json(args.bundle_verification, "bundle verification")
            if args.bundle_verification
            else None
        )
        if args.bundle_verification:
            source_paths["bundle_verification_sha256"] = args.bundle_verification
        attestation = build_attestation(
            expected_platform=args.expected_platform,
            readiness=readiness,
            lifecycle=lifecycle,
            plan=plan,
            platform_matrix=platform_matrix,
            bundle_verification=bundle,
            applied=args.applied,
            source_hashes={name: file_sha256(path) for name, path in source_paths.items()},
        )
    except ValueError as exc:
        attestation = {
            "schema_version": SCHEMA_VERSION,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "status": "failed",
            "read_only": not args.applied,
            "node_mutation_allowed": False,
            "action_execution": "disabled",
            "expected_platform": args.expected_platform,
            "mode": "applied_canary" if args.applied else "preflight",
            "promotion": {"eligible_for_manual_review": False, "automatic_matrix_update": False},
            "failures": [str(exc)],
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(attestation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(attestation, ensure_ascii=False, indent=2))
    return 0 if attestation["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
