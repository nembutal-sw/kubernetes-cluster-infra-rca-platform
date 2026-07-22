import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INTAKE = ROOT / "scripts" / "managed-blind-intake.py"
FINALIZE = ROOT / "scripts" / "managed-blind-finalize.py"
CASE_ID = "managed-0123456789abcdef01234567"


def canonical_sha256(value: dict) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def valid_attestation() -> dict:
    return {
        "schema_version": "managed-cluster-canary/v1",
        "status": "passed",
        "mode": "applied_canary",
        "action_execution": "disabled",
        "detected_platform": "eks",
        "dimensions": {
            "platform_variant": "managed_node_group",
            "architectures": ["amd64"],
            "runtime_families": ["containerd"],
            "cni_families": ["aws-vpc-cni"],
            "operating_systems": ["linux"],
        },
        "lifecycle": {
            "cleanup_state": "completed",
            "resources_kept": False,
            "evidence_bundle": {"passed": True, "signature_verified": True},
        },
        "promotion": {
            "eligible_for_manual_review": True,
            "automatic_matrix_update": False,
        },
    }


def evidence_payload() -> dict:
    return {
        "cluster_id": "cluster-customer-prod",
        "node_name": "node-customer-01",
        "evidence_id": "72e773d4-d339-4d75-88e5-d9e4bb48c471",
        "collected_at": "2026-07-22T01:02:03Z",
        "alert_name": "AnalyzerMustNotCopyThis",
        "collectors": {
            "node": {
                "status": "ok",
                "host_name": "node-customer-01",
                "boot_id": "d3284ca4-d730-42a4-89dc-10177f86d261",
                "platform": "linux",
            },
            "dns": {
                "status": "warning",
                "nameservers": ["10.20.30.40"],
                "resolv_conf_excerpt": "nameserver 10.20.30.40 password=hunter2",
            },
            "kernel": {
                "status": "error",
                "dmesg": {
                    "stdout": "node-customer-01 bearer abc.def.ghi user@example.com",
                    "stderr": "token=secret-token-value",
                },
            },
            "kubernetes": {
                "status": "ok",
                "node_name": "node-customer-01",
                "node_uid": "e780110d-e7ec-4871-a98a-418653c19e40",
                "pods": {
                    "data": {
                        "apiVersion": "v1",
                        "kind": "PodList",
                        "items": [{"metadata": {"name": "private-workload"}}],
                    }
                },
            },
            "runtime": {
                "status": "ok",
                "runtime_socket_path": "/home/customer/.run/containerd.sock",
                "api_key": "private-api-key",
            },
        },
    }


def create_bundle(path: Path, *, traversal: bool = False, bad_hash: bool = False) -> None:
    entries = {
        "summary.json": json.dumps({"schema_version": "1.0"}).encode(),
        "evidence/evidence-1.json": json.dumps(evidence_payload()).encode(),
        "signals.json": json.dumps({"signals": ["KERNEL_IO_ERROR"]}).encode(),
        "timeline.json": json.dumps({"events": []}).encode(),
        "rca-report.md": b"root cause output must never be copied",
    }
    if traversal:
        entries["../private.txt"] = b"private"
    manifest_entries = []
    for name, payload in entries.items():
        digest = hashlib.sha256(payload).hexdigest()
        if bad_hash and name.startswith("evidence/"):
            digest = "0" * 64
        manifest_entries.append({"path": name, "sha256": digest})
    manifest = {
        "schema_version": "1.0",
        "hash_algorithm": "SHA-256",
        "entries": manifest_entries,
        "signature": "test-signature",
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)
        archive.writestr("manifest.json", json.dumps(manifest).encode())


def run_intake(tmp_path: Path, *, attestation: dict | None = None, **bundle_options):
    tmp_path.mkdir(parents=True, exist_ok=True)
    bundle = tmp_path / "evidence-bundle.zip"
    attestation_path = tmp_path / "attestation.json"
    output = tmp_path / "intake"
    create_bundle(bundle, **bundle_options)
    write_json(attestation_path, attestation or valid_attestation())
    result = subprocess.run(
        [
            sys.executable,
            str(INTAKE),
            "--evidence-bundle",
            str(bundle),
            "--attestation",
            str(attestation_path),
            "--source-run-id",
            "123456789",
            "--evaluation-reference-sha256",
            "a" * 64,
            "--case-id",
            CASE_ID,
            "--output-dir",
            str(output),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result, output


def test_intake_redacts_identifiers_and_excludes_analyzer_output(tmp_path: Path) -> None:
    result, output = run_intake(tmp_path)

    assert result.returncode == 0, result.stderr
    evidence = json.loads((output / "evidence.json").read_text(encoding="utf-8"))
    template = json.loads((output / "adjudication-template.json").read_text(encoding="utf-8"))
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    encoded = json.dumps(evidence)
    for private_value in (
        "cluster-customer-prod",
        "node-customer-01",
        "10.20.30.40",
        "hunter2",
        "secret-token-value",
        "private-api-key",
        "user@example.com",
        "private-workload",
    ):
        assert private_value not in encoded
    for forbidden_key in (
        "alert_name",
        "signals",
        "root_cause",
        "report",
        "expected_signals",
    ):
        assert f'"{forbidden_key}"' not in encoded
    assert evidence["collectors"]["kubernetes"]["pods"]["data"] == {"redacted": True}
    assert evidence["provenance"]["contains_raw_customer_data"] is False
    assert evidence["provenance"]["analyzer_output_included"] is False
    assert template["review_status"] == "pending"
    assert template["reviewers"] == []
    assert manifest["contains_raw_bundle"] is False
    assert manifest["contains_analyzer_output"] is False
    assert manifest["evaluation_reference_sha256"] == "a" * 64
    assert manifest["evidence_sha256"] == canonical_sha256(evidence)


def test_intake_rejects_unsafe_zip_and_hash_mismatch(tmp_path: Path) -> None:
    traversal_result, _ = run_intake(tmp_path / "traversal", traversal=True)
    assert traversal_result.returncode == 1
    assert "unsafe ZIP entry path" in traversal_result.stderr

    hash_path = tmp_path / "hash"
    hash_path.mkdir()
    hash_result, _ = run_intake(hash_path, bad_hash=True)
    assert hash_result.returncode == 1
    assert "hash mismatch" in hash_result.stderr


def test_intake_requires_successful_applied_canary(tmp_path: Path) -> None:
    attestation = valid_attestation()
    attestation["mode"] = "preflight"
    attestation["promotion"]["eligible_for_manual_review"] = False

    result, _ = run_intake(tmp_path, attestation=attestation)

    assert result.returncode == 1
    assert "not intake-eligible" in result.stderr


def approved_adjudication() -> dict:
    return {
        "schema_version": "rca-managed-blind-adjudication/v1",
        "case_id": CASE_ID,
        "review_status": "approved",
        "classification": "single_fault",
        "expected_signals": ["KERNEL_IO_ERROR"],
        "allowed_signals": ["DISK_IO_PRESSURE"],
        "forbidden_signals": ["MEMORY_PRESSURE"],
        "root_cause_summary": "Kernel I/O errors preceded node degradation.",
        "consensus": True,
        "reviewers": [
            {
                "reviewer_id": "reviewer_9ad32ca1",
                "role": "primary",
                "decision": "approve",
                "reviewed_at": "2026-07-22T01:00:00Z",
            },
            {
                "reviewer_id": "reviewer_f183bc22",
                "role": "secondary",
                "decision": "approve",
                "reviewed_at": "2026-07-22T02:00:00Z",
            },
        ],
        "notes": "Reviewed without analyzer output.",
    }


def test_finalize_requires_two_reviewers_and_seals_hashes(tmp_path: Path) -> None:
    intake_result, intake = run_intake(tmp_path)
    assert intake_result.returncode == 0, intake_result.stderr
    adjudication = tmp_path / "adjudication.json"
    output = tmp_path / "final"
    write_json(adjudication, approved_adjudication())

    result = subprocess.run(
        [
            sys.executable,
            str(FINALIZE),
            "--evidence",
            str(intake / "evidence.json"),
            "--adjudication",
            str(adjudication),
            "--output-dir",
            str(output),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert result.returncode == 0, result.stderr
    evidence = json.loads((output / "evidence.json").read_text(encoding="utf-8"))
    labels = json.loads((output / "labels.json").read_text(encoding="utf-8"))
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["evidence_sha256"] == canonical_sha256(evidence)
    assert manifest["labels_sha256"] == canonical_sha256(labels)
    assert manifest["reviewer_count"] == 2
    assert manifest["promotion_requires_pull_request"] is True


def test_finalize_rejects_pending_or_non_independent_review(tmp_path: Path) -> None:
    intake_result, intake = run_intake(tmp_path)
    assert intake_result.returncode == 0, intake_result.stderr
    adjudication = approved_adjudication()
    adjudication["reviewers"][1]["reviewer_id"] = adjudication["reviewers"][0]["reviewer_id"]
    adjudication_path = tmp_path / "adjudication.json"
    write_json(adjudication_path, adjudication)

    result = subprocess.run(
        [
            sys.executable,
            str(FINALIZE),
            "--evidence",
            str(intake / "evidence.json"),
            "--adjudication",
            str(adjudication_path),
            "--output-dir",
            str(tmp_path / "final"),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert result.returncode == 1
    assert "distinct IDs" in result.stderr


def test_finalize_rejects_sensitive_label_content(tmp_path: Path) -> None:
    intake_result, intake = run_intake(tmp_path)
    assert intake_result.returncode == 0, intake_result.stderr
    adjudication = approved_adjudication()
    adjudication["root_cause_summary"] = "Node 10.20.30.40 failed with token=private-value."
    adjudication_path = tmp_path / "adjudication.json"
    write_json(adjudication_path, adjudication)

    result = subprocess.run(
        [
            sys.executable,
            str(FINALIZE),
            "--evidence",
            str(intake / "evidence.json"),
            "--adjudication",
            str(adjudication_path),
            "--output-dir",
            str(tmp_path / "final"),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert result.returncode == 1
    assert "sensitive identifier pattern" in result.stderr
