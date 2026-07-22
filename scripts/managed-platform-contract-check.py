#!/usr/bin/env python3
"""Validate document-backed managed platform fixtures without claiming real support."""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from cluster_compatibility import build_cluster_fingerprint, evaluate_compatibility, load_catalog


SCHEMA_VERSION = "managed-platform-contract-fixtures/v1"
FIXTURE_SCHEMA_VERSION = "managed-platform-contract-fixture/v1"
ALLOWED_SOURCE_HOSTS = {"docs.aws.amazon.com", "kubernetes.io"}
MAX_REVIEW_AGE_DAYS = 180


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return payload


def fixture_items(snapshot: dict[str, Any], key: str) -> list[dict[str, Any]]:
    payload = object_value(snapshot.get(key))
    return [item for item in list_value(payload.get("items")) if isinstance(item, dict)]


def validate_source(source: dict[str, Any], fixture_id: str) -> list[str]:
    failures: list[str] = []
    url = str(source.get("url") or "")
    parsed = urlsplit(url)
    if source.get("kind") != "official":
        failures.append(f"{fixture_id}: source kind must be official")
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_SOURCE_HOSTS:
        failures.append(f"{fixture_id}: source must use an approved official HTTPS host")
    if not str(source.get("title") or "").strip():
        failures.append(f"{fixture_id}: source title is required")
    if not list_value(source.get("supports")):
        failures.append(f"{fixture_id}: source supports list is required")
    return failures


def evaluate_fixture(fixture: dict[str, Any], catalog: dict[str, Any]) -> dict[str, Any]:
    fixture_id = str(fixture.get("fixture_id") or "missing-fixture-id")
    failures: list[str] = []
    if fixture.get("schema_version") != FIXTURE_SCHEMA_VERSION:
        failures.append(f"{fixture_id}: invalid fixture schema_version")
    reviewed_on = str(fixture.get("reviewed_on") or "")
    try:
        reviewed_date = date.fromisoformat(reviewed_on)
    except ValueError:
        failures.append(f"{fixture_id}: reviewed_on must be an ISO date")
    else:
        if reviewed_date > date.today():
            failures.append(f"{fixture_id}: reviewed_on cannot be in the future")
        elif (date.today() - reviewed_date).days > MAX_REVIEW_AGE_DAYS:
            failures.append(
                f"{fixture_id}: official sources must be reviewed at least every {MAX_REVIEW_AGE_DAYS} days"
            )
    sources = [source for source in list_value(fixture.get("sources")) if isinstance(source, dict)]
    if not sources:
        failures.append(f"{fixture_id}: at least one official source is required")
    for source in sources:
        failures.extend(validate_source(source, fixture_id))

    snapshot = object_value(fixture.get("synthetic_snapshot"))
    nodes = fixture_items(snapshot, "nodes")
    pods = fixture_items(snapshot, "pods")
    if not nodes:
        failures.append(f"{fixture_id}: synthetic snapshot must contain a node")
    fingerprint = build_cluster_fingerprint(nodes, pods)
    compatibility = evaluate_compatibility(fingerprint, catalog)
    expected = object_value(fixture.get("expected"))
    actual = {
        "platform_family": object_value(fingerprint.get("platform")).get("family"),
        "platform_variant": object_value(fingerprint.get("platform")).get("variant"),
        "architectures": fingerprint.get("architectures"),
        "runtime_families": fingerprint.get("runtime_families"),
        "cni_families": object_value(fingerprint.get("cni")).get("families"),
        "compatibility_status": compatibility.get("status"),
        "validation_level": compatibility.get("validation_level"),
    }
    for key, expected_value in expected.items():
        if actual.get(key) != expected_value:
            failures.append(f"{fixture_id}: {key} expected {expected_value!r}, got {actual.get(key)!r}")
    if actual["compatibility_status"] != "contract_fixture_only":
        failures.append(f"{fixture_id}: fixtures must never claim real compatibility")

    deployment = object_value(fixture.get("agent_deployment"))
    if deployment.get("action_execution") != "disabled":
        failures.append(f"{fixture_id}: action execution must be disabled")
    daemonset_supported = deployment.get("daemonset_supported")
    recommended_mode = deployment.get("recommended_mode")
    if daemonset_supported is False and recommended_mode != "unsupported":
        failures.append(f"{fixture_id}: unsupported DaemonSet requires recommended_mode=unsupported")
    if daemonset_supported is True and recommended_mode not in {"safe", "node-diagnostics"}:
        failures.append(f"{fixture_id}: supported DaemonSet requires a valid recommended mode")
    if deployment.get("real_canary_required") is not True:
        failures.append(f"{fixture_id}: a real canary must remain required")

    return {
        "fixture_id": fixture_id,
        "status": "passed" if not failures else "failed",
        "reviewed_on": reviewed_on,
        "official_source_count": len(sources),
        "detected": actual,
        "agent_deployment": {
            "daemonset_supported": daemonset_supported,
            "recommended_mode": recommended_mode,
            "host_evidence": deployment.get("host_evidence"),
            "real_canary_required": deployment.get("real_canary_required"),
        },
        "failures": failures,
    }


def build_report(fixtures: dict[str, Any], catalog: dict[str, Any]) -> dict[str, Any]:
    failures: list[str] = []
    if fixtures.get("schema_version") != SCHEMA_VERSION:
        failures.append(f"fixture catalog schema_version must be {SCHEMA_VERSION}")
    results = [
        evaluate_fixture(fixture, catalog)
        for fixture in list_value(fixtures.get("fixtures"))
        if isinstance(fixture, dict)
    ]
    if not results:
        failures.append("fixture catalog must contain at least one fixture")
    for result in results:
        failures.extend(result["failures"])
    return {
        "schema_version": "managed-platform-contract-report/v1",
        "status": "passed" if not failures else "failed",
        "fixture_count": len(results),
        "passed_fixture_count": sum(result["status"] == "passed" for result in results),
        "results": results,
        "failures": failures,
    }


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Validate managed platform contract fixtures.")
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=root / "tests" / "fixtures" / "managed-platforms" / "eks-contracts.json",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=root / "config" / "platform-compatibility-matrix.json",
    )
    parser.add_argument("--output", default="-", help="JSON output path, or '-' for stdout.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = build_report(load_json(args.fixtures), load_catalog(args.catalog))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        report = {
            "schema_version": "managed-platform-contract-report/v1",
            "status": "failed",
            "fixture_count": 0,
            "passed_fixture_count": 0,
            "results": [],
            "failures": [str(exc)],
        }
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output == "-":
        print(encoded)
    else:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded + "\n", encoding="utf-8")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
