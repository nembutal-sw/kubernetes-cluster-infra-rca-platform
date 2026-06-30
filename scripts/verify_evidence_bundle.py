#!/usr/bin/env python3
"""Verify exported RCA evidence bundle integrity.

The verifier does not extract files to disk. It checks ZIP entries against the
bundle manifest, validates SHA-256 hashes, and optionally verifies the
HMAC-SHA256 manifest signature.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import sys
import zipfile
from pathlib import Path
from typing import Any


REQUIRED_ENTRIES = {"summary.json", "signals.json", "timeline.json", "rca-report.md", "manifest.json"}
SIGNATURE_ALGORITHM = "HMAC-SHA256"
CANONICALIZATION = "bundle-manifest-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify RCA evidence bundle ZIP exports.")
    parser.add_argument("bundles", nargs="+", type=Path, help="Evidence bundle ZIP file path.")
    parser.add_argument(
        "--signature-secret",
        default=os.getenv("RCA_BUNDLE_SIGNATURE_SECRET", os.getenv("RCA_EXPORT_SIGNATURE_SECRET", "")),
        help="Optional HMAC secret used to verify signed manifest bundles.",
    )
    parser.add_argument(
        "--signature-key-id",
        default=os.getenv("RCA_BUNDLE_SIGNATURE_KEY_ID", os.getenv("RCA_EXPORT_SIGNATURE_KEY_ID", "")),
        help="Optional expected signature key_id.",
    )
    parser.add_argument(
        "--require-signature",
        action="store_true",
        help="Fail if the manifest is unsigned or no signature secret is supplied.",
    )
    parser.add_argument(
        "--max-entry-bytes",
        type=int,
        default=int(os.getenv("RCA_BUNDLE_VERIFY_MAX_ENTRY_BYTES", str(100 * 1024 * 1024))),
        help="Reject ZIP entries larger than this value before reading them.",
    )
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON.")
    return parser.parse_args()


def verify_bundle(
    bundle_path: Path,
    *,
    signature_secret: str = "",
    signature_key_id: str = "",
    require_signature: bool = False,
    max_entry_bytes: int = 100 * 1024 * 1024,
) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    manifest: dict[str, Any] = {}
    entry_count = 0
    signature_present = False
    signature_verified = False

    if max_entry_bytes < 1:
        return result(bundle_path, False, manifest, entry_count, signature_present, signature_verified, [
            "max_entry_bytes must be positive"
        ], warnings)

    try:
        with zipfile.ZipFile(bundle_path) as bundle:
            names = set(bundle.namelist())
            for missing in sorted(REQUIRED_ENTRIES - names):
                errors.append(f"missing required entry: {missing}")
            if not any(name.startswith("evidence/") and name.endswith(".json") for name in names):
                errors.append("missing evidence/*.json entry")
            if "manifest.json" not in names:
                return result(bundle_path, False, manifest, entry_count, signature_present, signature_verified, errors, warnings)

            manifest = json.loads(read_entry(bundle, "manifest.json", max_entry_bytes).decode("utf-8"))
            if manifest.get("hash_algorithm") != "SHA-256":
                errors.append("manifest hash_algorithm is not SHA-256")
            entries = manifest.get("entries")
            if not isinstance(entries, list) or not entries:
                errors.append("manifest entries is empty")
            else:
                entry_count = len(entries)
                validate_entry_hashes(bundle, names, entries, max_entry_bytes, errors)

            signature_present = isinstance(manifest.get("signature"), dict) and bool(
                manifest.get("signature", {}).get("enabled")
            )
            signature_verified = validate_signature(
                manifest,
                signature_secret,
                signature_key_id,
                require_signature,
                errors,
                warnings,
            )
    except FileNotFoundError:
        errors.append("bundle file not found")
    except zipfile.BadZipFile as exc:
        errors.append(f"invalid ZIP file: {exc}")
    except json.JSONDecodeError as exc:
        errors.append(f"manifest.json is not valid JSON: {exc}")
    except UnicodeDecodeError as exc:
        errors.append(f"manifest.json is not UTF-8: {exc}")
    except ValueError as exc:
        errors.append(str(exc))

    return result(
        bundle_path,
        not errors,
        manifest,
        entry_count,
        signature_present,
        signature_verified,
        errors,
        warnings,
    )


def validate_entry_hashes(
    bundle: zipfile.ZipFile,
    names: set[str],
    entries: list[Any],
    max_entry_bytes: int,
    errors: list[str],
) -> None:
    manifest_paths: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append("manifest entry is not an object")
            continue
        path = str(entry.get("path") or "")
        expected_hash = str(entry.get("sha256") or "")
        if not path or not expected_hash:
            errors.append(f"manifest entry has blank path or sha256: {entry}")
            continue
        if path == "manifest.json":
            errors.append("manifest should not hash itself")
            continue
        manifest_paths.add(path)
        if path not in names:
            errors.append(f"manifest hashes missing ZIP entry: {path}")
            continue
        actual_hash = hashlib.sha256(read_entry(bundle, path, max_entry_bytes)).hexdigest()
        if actual_hash != expected_hash:
            errors.append(f"sha256 mismatch for {path}")

    for required in sorted(REQUIRED_ENTRIES - {"manifest.json"}):
        if required not in manifest_paths:
            errors.append(f"manifest missing hash for {required}")


def validate_signature(
    manifest: dict[str, Any],
    signature_secret: str,
    signature_key_id: str,
    require_signature: bool,
    errors: list[str],
    warnings: list[str],
) -> bool:
    signature = manifest.get("signature")
    if not isinstance(signature, dict) or signature.get("enabled") is not True:
        if require_signature:
            errors.append("manifest signature is required but not enabled")
        return False

    if not signature_secret:
        message = "manifest is signed, but no signature secret was provided"
        if require_signature:
            errors.append(message)
        else:
            warnings.append(message)
        return False

    if signature.get("algorithm") != SIGNATURE_ALGORITHM:
        errors.append(f"manifest signature algorithm is not {SIGNATURE_ALGORITHM}")
        return False
    if signature.get("canonicalization") != CANONICALIZATION:
        errors.append(f"manifest signature canonicalization is not {CANONICALIZATION}")
        return False
    if signature_key_id and signature.get("key_id") != signature_key_id:
        errors.append(
            "manifest signature key_id mismatch: "
            f"expected={signature_key_id}, actual={signature.get('key_id')}"
        )
        return False

    expected = hmac.new(
        signature_secret.encode("utf-8"),
        canonical_manifest(manifest).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    actual = str(signature.get("value") or "")
    if not hmac.compare_digest(actual, expected):
        errors.append("manifest HMAC signature mismatch")
        return False
    return True


def read_entry(bundle: zipfile.ZipFile, path: str, max_entry_bytes: int) -> bytes:
    info = bundle.getinfo(path)
    if info.file_size > max_entry_bytes:
        raise ValueError(f"{path} exceeds max entry size: {info.file_size} > {max_entry_bytes}")
    return bundle.read(path)


def canonical_manifest(manifest: dict[str, Any]) -> str:
    lines = [
        f"schema_version={manifest.get('schema_version', '')}",
        f"generated_at={manifest.get('generated_at', '')}",
        f"report_id={manifest.get('report_id', '')}",
        f"incident_id={manifest.get('incident_id', '')}",
        f"cluster_id={manifest.get('cluster_id', '')}",
        f"node_name={manifest.get('node_name', '')}",
        f"evidence_count={manifest.get('evidence_count', '')}",
        f"hash_algorithm={manifest.get('hash_algorithm', '')}",
    ]
    entries = [entry for entry in manifest.get("entries", []) if isinstance(entry, dict)]
    for entry in sorted(entries, key=lambda item: str(item.get("path") or "")):
        lines.append(f"entry:{entry.get('path', '')}={entry.get('sha256', '')}")
    return "\n".join(lines) + "\n"


def result(
    bundle_path: Path,
    passed: bool,
    manifest: dict[str, Any],
    entry_count: int,
    signature_present: bool,
    signature_verified: bool,
    errors: list[str],
    warnings: list[str],
) -> dict[str, Any]:
    return {
        "bundle": str(bundle_path),
        "passed": passed,
        "report_id": manifest.get("report_id"),
        "incident_id": manifest.get("incident_id"),
        "entry_count": entry_count,
        "signature_present": signature_present,
        "signature_verified": signature_verified,
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    args = parse_args()
    results = [
        verify_bundle(
            bundle,
            signature_secret=args.signature_secret,
            signature_key_id=args.signature_key_id,
            require_signature=args.require_signature,
            max_entry_bytes=args.max_entry_bytes,
        )
        for bundle in args.bundles
    ]
    if args.json:
        print(json.dumps({"results": results}, indent=2, ensure_ascii=False))
    else:
        for item in results:
            status = "PASS" if item["passed"] else "FAIL"
            signature = "signature=verified" if item["signature_verified"] else "signature=not-verified"
            print(f"{status} {item['bundle']} entries={item['entry_count']} {signature}")
            for warning in item["warnings"]:
                print(f"  warning: {warning}")
            for error in item["errors"]:
                print(f"  error: {error}")
    return 0 if all(item["passed"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
