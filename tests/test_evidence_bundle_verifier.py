import hashlib
import hmac
import json
import zipfile
from pathlib import Path

from scripts.verify_evidence_bundle import canonical_manifest, verify_bundle


SECRET = "test-signature-secret"
KEY_ID = "test-key"


def test_verifies_signed_bundle(tmp_path: Path):
    bundle = write_bundle(tmp_path / "signed.zip", signed=True)

    result = verify_bundle(
        bundle,
        signature_secret=SECRET,
        signature_key_id=KEY_ID,
        require_signature=True,
    )

    assert result["passed"] is True
    assert result["signature_present"] is True
    assert result["signature_verified"] is True
    assert result["entry_count"] == 5
    assert result["errors"] == []


def test_detects_hash_mismatch(tmp_path: Path):
    bundle = write_bundle(tmp_path / "tampered.zip", signed=True, tamper_summary=True)

    result = verify_bundle(bundle, signature_secret=SECRET, signature_key_id=KEY_ID)

    assert result["passed"] is False
    assert result["signature_verified"] is True
    assert "sha256 mismatch for summary.json" in result["errors"]


def test_requires_signature_when_requested(tmp_path: Path):
    bundle = write_bundle(tmp_path / "unsigned.zip", signed=False)

    result = verify_bundle(bundle, require_signature=True)

    assert result["passed"] is False
    assert result["signature_present"] is False
    assert "manifest signature is required but not enabled" in result["errors"]


def write_bundle(path: Path, *, signed: bool, tamper_summary: bool = False) -> Path:
    contents = {
        "summary.json": b'{"summary":"ok"}',
        "signals.json": b'{"signals":[]}',
        "timeline.json": b'{"nodes":[]}',
        "rca-report.md": b"# RCA Report\n",
        "evidence/evidence-1.json": b'{"collector":"disk"}',
    }
    manifest = {
        "schema_version": "1.0",
        "generated_at": "2026-06-30T00:00:00Z",
        "report_id": "report-test",
        "incident_id": "incident-test",
        "cluster_id": "cluster-test",
        "node_name": "worker-1",
        "evidence_count": 1,
        "hash_algorithm": "SHA-256",
        "entries": [
            {"path": name, "sha256": hashlib.sha256(value).hexdigest()}
            for name, value in contents.items()
        ],
    }
    if signed:
        manifest["signature"] = {
            "enabled": True,
            "algorithm": "HMAC-SHA256",
            "key_id": KEY_ID,
            "canonicalization": "bundle-manifest-v1",
            "value": hmac.new(
                SECRET.encode("utf-8"),
                canonical_manifest(manifest).encode("utf-8"),
                hashlib.sha256,
            ).hexdigest(),
        }
    else:
        manifest["signature"] = {
            "enabled": False,
            "reason": "signature_secret_not_configured",
        }

    if tamper_summary:
        contents["summary.json"] = b'{"summary":"tampered"}'

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, value in contents.items():
            bundle.writestr(name, value)
        bundle.writestr("manifest.json", json.dumps(manifest).encode("utf-8"))
    return path
