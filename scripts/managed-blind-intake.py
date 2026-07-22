#!/usr/bin/env python3
"""Create an anonymized blind-evaluation candidate from a managed canary bundle."""

from __future__ import annotations

import argparse
import json
import re
import secrets
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from managed_blind_common import (
    atomic_write_json,
    canonical_json_bytes,
    require_empty_output_dir,
    sanitize_string,
    sha256_bytes,
    sha256_file,
    utc_now,
    validate_case_id,
    validate_evidence_candidate,
)


MAX_ARCHIVE_ENTRIES = 100
MAX_UNCOMPRESSED_BYTES = 25 * 1024 * 1024
SENSITIVE_KEYS = {
    "access_token",
    "agent_token",
    "api_key",
    "authorization",
    "client_secret",
    "cookie",
    "node_token",
    "password",
    "private_key",
    "secret",
    "token",
}
IDENTITY_KEYS = {
    "address",
    "agent_node_name",
    "boot_id",
    "cluster_id",
    "evidence_id",
    "host_name",
    "incident_id",
    "machine_id",
    "node_name",
    "node_uid",
    "provider_id",
    "report_id",
    "resourceversion",
    "selflink",
    "system_uuid",
    "uid",
}
RAW_KUBERNETES_KEYS = {"annotations", "managedfields", "ownerreferences"}
SAFE_COLLECTOR_NAME = re.compile(r"[a-z][a-z0-9_-]{0,63}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-bundle", type=Path, required=True)
    parser.add_argument("--attestation", type=Path, required=True)
    parser.add_argument("--source-run-id", required=True)
    parser.add_argument("--evaluation-reference-sha256", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--case-id", help="Test-only deterministic opaque case ID.")
    return parser.parse_args()


def load_json_bytes(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not valid UTF-8 JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return value


def validate_attestation(path: Path) -> dict[str, Any]:
    attestation = load_json_bytes(path.read_bytes(), "managed canary attestation")
    lifecycle = attestation.get("lifecycle") if isinstance(attestation.get("lifecycle"), dict) else {}
    bundle = lifecycle.get("evidence_bundle") if isinstance(lifecycle.get("evidence_bundle"), dict) else {}
    promotion = attestation.get("promotion") if isinstance(attestation.get("promotion"), dict) else {}
    requirements = {
        "schema": attestation.get("schema_version") == "managed-cluster-canary/v1",
        "status": attestation.get("status") == "passed",
        "mode": attestation.get("mode") == "applied_canary",
        "action_execution": attestation.get("action_execution") == "disabled",
        "resources_removed": lifecycle.get("resources_kept") is False,
        "cleanup": lifecycle.get("cleanup_state") == "completed",
        "bundle": bundle.get("passed") is True and bundle.get("signature_verified") is True,
        "manual_review": promotion.get("eligible_for_manual_review") is True,
        "no_automatic_promotion": promotion.get("automatic_matrix_update") is False,
    }
    failures = [name for name, passed in requirements.items() if not passed]
    if failures:
        raise ValueError(f"managed canary attestation is not intake-eligible: {', '.join(failures)}")
    return attestation


def validate_zip_member(info: zipfile.ZipInfo) -> None:
    path = PurePosixPath(info.filename)
    if path.is_absolute() or ".." in path.parts or "\\" in info.filename:
        raise ValueError(f"unsafe ZIP entry path: {info.filename}")
    mode = info.external_attr >> 16
    if mode and stat.S_ISLNK(mode):
        raise ValueError(f"ZIP symlink entries are not allowed: {info.filename}")


def read_verified_evidence(bundle_path: Path) -> tuple[dict[str, Any], str]:
    if not bundle_path.is_file():
        raise ValueError("evidence bundle does not exist")
    bundle_hash = sha256_file(bundle_path)
    try:
        archive = zipfile.ZipFile(bundle_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValueError(f"evidence bundle is not a valid ZIP: {exc}") from exc
    with archive:
        infos = archive.infolist()
        if not infos or len(infos) > MAX_ARCHIVE_ENTRIES:
            raise ValueError("evidence bundle entry count is outside the allowed range")
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError("evidence bundle contains duplicate ZIP entries")
        for info in infos:
            validate_zip_member(info)
        if sum(info.file_size for info in infos) > MAX_UNCOMPRESSED_BYTES:
            raise ValueError("evidence bundle exceeds the uncompressed size limit")
        if "manifest.json" not in names:
            raise ValueError("evidence bundle is missing manifest.json")
        manifest = load_json_bytes(archive.read("manifest.json"), "evidence manifest")
        if str(manifest.get("hash_algorithm", "")).upper() != "SHA-256":
            raise ValueError("evidence manifest must use SHA-256")
        entries = manifest.get("entries")
        if not isinstance(entries, list) or not entries:
            raise ValueError("evidence manifest entries must be a non-empty array")
        registered: set[str] = set()
        for item in entries:
            if not isinstance(item, dict):
                raise ValueError("evidence manifest entry must be an object")
            name = str(item.get("path", ""))
            expected = str(item.get("sha256", ""))
            if name in registered or name not in names or not re.fullmatch(r"[a-f0-9]{64}", expected):
                raise ValueError(f"invalid evidence manifest entry: {name}")
            registered.add(name)
            if sha256_bytes(archive.read(name)) != expected:
                raise ValueError(f"evidence bundle hash mismatch: {name}")
        if registered != set(names) - {"manifest.json"}:
            raise ValueError("evidence manifest does not cover every non-manifest entry")
        evidence_names = sorted(
            name for name in registered if name.startswith("evidence/") and name.endswith(".json")
        )
        if len(evidence_names) != 1:
            raise ValueError("managed blind intake requires exactly one node evidence document")
        evidence = load_json_bytes(archive.read(evidence_names[0]), "node evidence")
        collectors = evidence.get("collectors")
        if not isinstance(collectors, dict) or not collectors:
            raise ValueError("node evidence collectors must be a non-empty object")
        return evidence, bundle_hash


def collect_sensitive_values(value: Any, parent_key: str = "") -> set[str]:
    values: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = key.lower().replace("-", "_")
            if normalized in IDENTITY_KEYS and isinstance(child, str) and child:
                values.add(child)
            if parent_key.lower() == "metadata" and normalized in {"name", "namespace", "uid"}:
                if isinstance(child, str) and child:
                    values.add(child)
            values.update(collect_sensitive_values(child, key))
    elif isinstance(value, list):
        for child in value:
            values.update(collect_sensitive_values(child, parent_key))
    return values


def sanitize_value(value: Any, sensitive_values: set[str], parent_key: str = "") -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, child in value.items():
            normalized = key.lower().replace("-", "_")
            compact = normalized.replace("_", "")
            if normalized in SENSITIVE_KEYS or compact in {item.replace("_", "") for item in SENSITIVE_KEYS}:
                result[key] = "[redacted]"
            elif normalized in IDENTITY_KEYS:
                result[key] = "[redacted-identifier]"
            elif normalized in RAW_KUBERNETES_KEYS:
                result[key] = "[redacted-kubernetes-metadata]"
            elif normalized == "data" and isinstance(child, dict) and child.get("apiVersion"):
                result[key] = {"redacted": True}
            else:
                result[key] = sanitize_value(child, sensitive_values, key)
        return result
    if isinstance(value, list):
        return [sanitize_value(child, sensitive_values, parent_key) for child in value]
    if isinstance(value, str):
        return sanitize_string(value, sensitive_values)
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return "[redacted-unsupported-value]"


def build_candidate(
    *, case_id: str, evidence: dict[str, Any], attestation: dict[str, Any]
) -> dict[str, Any]:
    collectors = evidence["collectors"]
    invalid_names = [name for name in collectors if not SAFE_COLLECTOR_NAME.fullmatch(str(name))]
    if invalid_names:
        raise ValueError(f"collector names are invalid: {', '.join(sorted(invalid_names))}")
    sensitive_values = collect_sensitive_values(evidence)
    dimensions = attestation.get("dimensions") if isinstance(attestation.get("dimensions"), dict) else {}
    platform_shape = {
        "family": str(attestation.get("detected_platform") or "unknown"),
        "variant": str(dimensions.get("platform_variant") or "unknown"),
        "architectures": sorted(str(value) for value in dimensions.get("architectures", [])),
        "runtime_families": sorted(str(value) for value in dimensions.get("runtime_families", [])),
        "cni_families": sorted(str(value) for value in dimensions.get("cni_families", [])),
        "operating_systems": sorted(str(value) for value in dimensions.get("operating_systems", [])),
    }
    candidate = {
        "schema_version": "rca-managed-blind-evidence/v1",
        "case_id": case_id,
        "provenance": {
            "source": "managed_canary_sanitized",
            "contains_raw_customer_data": False,
            "analyzer_output_included": False,
            "automatic_corpus_update": False,
        },
        "platform_shape": sanitize_value(platform_shape, sensitive_values),
        "collectors": sanitize_value(collectors, sensitive_values),
    }
    validate_evidence_candidate(candidate)
    return candidate


def build_adjudication_template(case_id: str) -> dict[str, Any]:
    return {
        "schema_version": "rca-managed-blind-adjudication/v1",
        "case_id": case_id,
        "review_status": "pending",
        "classification": None,
        "expected_signals": None,
        "allowed_signals": None,
        "forbidden_signals": None,
        "root_cause_summary": None,
        "consensus": False,
        "reviewers": [],
        "notes": None,
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9]{1,20}", args.source_run_id):
        raise ValueError("source_run_id must be a GitHub Actions numeric run ID")
    if not re.fullmatch(r"[a-f0-9]{64}", args.evaluation_reference_sha256):
        raise ValueError("evaluation_reference_sha256 must be a lowercase SHA-256 digest")
    attestation = validate_attestation(args.attestation)
    evidence, bundle_hash = read_verified_evidence(args.evidence_bundle)
    case_id = args.case_id or f"managed-{secrets.token_hex(12)}"
    validate_case_id(case_id)
    candidate = build_candidate(case_id=case_id, evidence=evidence, attestation=attestation)
    template = build_adjudication_template(case_id)
    require_empty_output_dir(args.output_dir)
    evidence_path = args.output_dir / "evidence.json"
    template_path = args.output_dir / "adjudication-template.json"
    atomic_write_json(evidence_path, candidate)
    atomic_write_json(template_path, template)
    manifest = {
        "schema_version": "rca-managed-blind-intake/v1",
        "generated_at": utc_now(),
        "case_id": case_id,
        "source_run_id": args.source_run_id,
        "evaluation_reference_sha256": args.evaluation_reference_sha256,
        "source_bundle_sha256": bundle_hash,
        "source_attestation_sha256": sha256_file(args.attestation),
        "evidence_sha256": sha256_bytes(canonical_json_bytes(candidate)),
        "adjudication_template_sha256": sha256_bytes(canonical_json_bytes(template)),
        "contains_raw_bundle": False,
        "contains_analyzer_output": False,
        "requires_independent_adjudication": True,
        "automatic_corpus_update": False,
    }
    atomic_write_json(args.output_dir / "manifest.json", manifest)
    return manifest


def main() -> int:
    args = parse_args()
    try:
        result = run(args)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps({"status": "passed", **result}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
