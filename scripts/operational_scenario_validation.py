#!/usr/bin/env python3
"""Run operational RCA scenario validation against a live platform.

The script uses the public API only:
  login -> list demo scenarios -> run selected scenarios -> wait for analysis
  -> fetch report and timeline -> validate the report quality gate.

Required environment for non-interactive use:
  RCA_ADMIN_PASSWORD or RCA_PASSWORD
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_SIGNALS: dict[str, set[str]] = {
    "node-not-ready": {"node_not_ready"},
    "disk-pressure": {"disk_usage_critical", "disk_io_latency_high"},
    "inode-exhaustion": {"inode_usage_critical"},
    "memory-pressure": {"memory_pressure_critical", "kernel_oom_detected"},
    "pid-pressure": {"pid_usage_high"},
    "kubelet-failure": {"kubelet_unit_unhealthy", "systemd_failed_units"},
    "runtime-failure": {"containerd_unit_unhealthy", "container_runtime_unit_unhealthy"},
    "coredns-latency": {"dns_latency_high"},
    "cni-mtu-mismatch": {"cni_mtu_values_inconsistent"},
    "conntrack-exhaustion": {"conntrack_near_limit"},
    "etcd-latency": {"etcd_latency_high", "disk_io_latency_high"},
    "api-server-latency": {"api_server_latency_high"},
    "kernel-io-error": {"kernel_io_error", "disk_io_latency_high"},
    "network-link-flap": {"nic_link_flap"},
    "systemd-restart-loop": {"systemd_failed_units", "kubelet_unit_unhealthy"},
}

UNSAFE_ACTION_KEYS = {
    "restart_kubelet",
    "restart_containerd",
    "restart_container_runtime",
    "cleanup_disk",
    "cordon_node",
    "reboot_node",
    "open_gitops_pr",
}


class ApiError(RuntimeError):
    def __init__(self, method: str, url: str, status: int, body: str) -> None:
        self.method = method
        self.url = url
        self.status = status
        self.body = body
        super().__init__(f"{method} {url} failed with HTTP {status}: {body[:500]}")


class Client:
    def __init__(self, base_url: str, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        token_required: bool = True,
    ) -> Any:
        url = self.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token_required and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise ApiError(method, url, exc.code, exc.read().decode("utf-8", "replace")) from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {url} failed: {exc}") from exc
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))

    def request_bytes(self, method: str, path: str) -> bytes:
        url = self.base_url + path
        headers = {"Accept": "application/octet-stream"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(url, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            raise ApiError(method, url, exc.code, exc.read().decode("utf-8", "replace")) from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {url} failed: {exc}") from exc

    def login(self, username: str, password: str) -> dict[str, Any]:
        response = self.request(
            "POST",
            "/api/auth/login",
            {"username": username, "password": password},
            token_required=False,
        )
        token = response.get("access_token")
        if not isinstance(token, str) or not token:
            raise RuntimeError("login response did not include access_token")
        self.token = token
        return response


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate RCA demo scenarios against a running platform API."
    )
    parser.add_argument("--base-url", default=os.getenv("RCA_BASE_URL", "http://127.0.0.1:18080"))
    parser.add_argument("--username", default=os.getenv("RCA_ADMIN_USERNAME", os.getenv("RCA_USERNAME", "admin")))
    parser.add_argument("--password", default=os.getenv("RCA_ADMIN_PASSWORD", os.getenv("RCA_PASSWORD", "")))
    parser.add_argument("--cluster-id", default=os.getenv("RCA_VALIDATION_CLUSTER_ID", ""))
    parser.add_argument(
        "--cluster-prefix",
        default=os.getenv("RCA_VALIDATION_CLUSTER_PREFIX", "Operational Validation"),
        help="Name prefix for isolated validation clusters when --cluster-id is not set.",
    )
    parser.add_argument("--node-name", default=os.getenv("RCA_VALIDATION_NODE_NAME", "validation-node-01"))
    parser.add_argument(
        "--scenarios",
        default=os.getenv("RCA_SCENARIOS", "all"),
        help="Comma-separated scenario keys or 'all'.",
    )
    parser.add_argument(
        "--output-dir",
        default=os.getenv("RCA_OUTPUT_DIR", "validation-results/operational-scenarios"),
    )
    parser.add_argument(
        "--task-timeout-seconds",
        type=int,
        default=int(os.getenv("RCA_TASK_TIMEOUT_SECONDS", "180")),
    )
    parser.add_argument(
        "--poll-seconds",
        type=float,
        default=float(os.getenv("RCA_POLL_SECONDS", "2")),
    )
    parser.add_argument(
        "--http-timeout-seconds",
        type=int,
        default=int(os.getenv("RCA_HTTP_TIMEOUT_SECONDS", "20")),
    )
    parser.add_argument(
        "--min-confidence-score",
        type=int,
        default=int(os.getenv("RCA_MIN_CONFIDENCE_SCORE", "50")),
    )
    parser.add_argument(
        "--skip-audit-check",
        action="store_true",
        default=os.getenv("RCA_SKIP_AUDIT_CHECK", "").lower() in {"1", "true", "yes"},
        help="Skip audit event verification for non-admin validation accounts.",
    )
    parser.add_argument(
        "--save-bundles",
        action="store_true",
        default=os.getenv("RCA_SAVE_BUNDLES", "").lower() in {"1", "true", "yes"},
        help="Persist downloaded evidence bundle ZIP files in the output directory.",
    )
    parser.add_argument(
        "--bundle-signature-secret",
        default=os.getenv("RCA_BUNDLE_SIGNATURE_SECRET", os.getenv("RCA_EXPORT_SIGNATURE_SECRET", "")),
        help="Optional HMAC secret used to verify signed evidence bundle manifests.",
    )
    parser.add_argument(
        "--bundle-signature-key-id",
        default=os.getenv("RCA_BUNDLE_SIGNATURE_KEY_ID", ""),
        help="Optional expected manifest signature key_id.",
    )
    return parser.parse_args()


def choose_scenarios(requested: str, available: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_key = {scenario.get("key"): scenario for scenario in available}
    if requested.strip().lower() == "all":
        return available
    selected = []
    for key in [item.strip() for item in requested.split(",") if item.strip()]:
        scenario = by_key.get(key)
        if scenario is None:
            raise RuntimeError(f"unknown scenario '{key}'. Available: {', '.join(sorted(by_key))}")
        selected.append(scenario)
    if not selected:
        raise RuntimeError("no scenarios were selected")
    return selected


def wait_for_task(client: Client, task_id: str, timeout_seconds: int, poll_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_task: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        task = client.request("GET", f"/api/rca/analysis-tasks/{urllib.parse.quote(task_id)}")
        last_task = task
        if task.get("status") in {"completed", "skipped", "dead_letter"}:
            return task
        time.sleep(poll_seconds)
    status = last_task.get("status") if last_task else "unknown"
    raise RuntimeError(f"analysis task {task_id} did not finish within {timeout_seconds}s; last status={status}")


def create_validation_cluster(client: Client, prefix: str, scenario_key: str, run_id: str) -> dict[str, Any]:
    safe_key = scenario_key.replace("_", "-")
    return client.request(
        "POST",
        "/api/clusters",
        {
            "name": f"{prefix} {safe_key} {run_id}",
            "environment": "validation",
            "description": "Created by operational_scenario_validation.py for isolated RCA validation.",
        },
    )


def derived_signals(report: dict[str, Any]) -> list[dict[str, Any]]:
    for section in report.get("evidence", []):
        if section.get("type") == "derived_signals":
            signals = section.get("signals", [])
            return signals if isinstance(signals, list) else []
    return []


def validate_report(
    scenario_key: str,
    report: dict[str, Any],
    timeline: dict[str, Any] | None,
    min_confidence_score: int,
) -> list[str]:
    errors: list[str] = []
    candidates = report.get("root_cause_candidates") or []
    actions = report.get("recommended_actions") or []
    signals = derived_signals(report)
    signal_names = {str(signal.get("signal")) for signal in signals}
    expected = EXPECTED_SIGNALS.get(scenario_key, set())

    if not candidates:
        errors.append("root_cause_candidates is empty")
    else:
        score = int(candidates[0].get("confidence_score") or 0)
        if score < min_confidence_score:
            errors.append(f"top confidence_score {score} is below {min_confidence_score}")
        if not candidates[0].get("evidence_paths"):
            errors.append("top root cause candidate has no evidence_paths")

    if not actions:
        errors.append("recommended_actions is empty")

    if not signals:
        errors.append("derived_signals is empty")
    elif expected and not signal_names.intersection(expected):
        errors.append(
            "expected signal missing; expected one of "
            + ",".join(sorted(expected))
            + f", actual={','.join(sorted(signal_names))}"
        )

    for action in actions:
        action_key = str(action.get("action_key") or "")
        source = str(action.get("source") or "")
        automation_allowed = bool(action.get("automation_allowed"))
        execution_plan = action.get("execution_plan") or {}
        executable = bool(execution_plan.get("executable"))
        if source == "llm" and automation_allowed:
            errors.append(f"LLM action {action_key} has automation_allowed=true")
        if action_key in UNSAFE_ACTION_KEYS and (automation_allowed or executable):
            errors.append(
                f"unsafe action {action_key} is executable or automation_allowed "
                f"(automation_allowed={automation_allowed}, executable={executable})"
            )

    if timeline is None:
        errors.append("incident timeline is missing")
    elif not timeline.get("nodes"):
        errors.append("incident timeline has no nodes")

    return errors


def validate_bundle(
    bundle_bytes: bytes,
    signature_secret: str = "",
    signature_key_id: str = "",
) -> tuple[dict[str, Any], list[str], bool]:
    errors: list[str] = []
    signature_verified = False
    required_entries = {"summary.json", "signals.json", "timeline.json", "rca-report.md", "manifest.json"}
    try:
        with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
            names = set(bundle.namelist())
            missing = sorted(required_entries - names)
            if missing:
                errors.append("bundle missing entries: " + ",".join(missing))
            if "manifest.json" not in names:
                return {}, errors, signature_verified
            if not any(name.startswith("evidence/") and name.endswith(".json") for name in names):
                errors.append("bundle has no evidence/*.json entry")
            manifest = json.loads(bundle.read("manifest.json").decode("utf-8"))
            if manifest.get("hash_algorithm") != "SHA-256":
                errors.append("manifest hash_algorithm is not SHA-256")
            entries = manifest.get("entries")
            if not isinstance(entries, list) or not entries:
                errors.append("manifest entries is empty")
                return manifest, errors, signature_verified
            manifest_paths = {str(entry.get("path")) for entry in entries if isinstance(entry, dict)}
            if "manifest.json" in manifest_paths:
                errors.append("manifest should not hash itself")
            for required in required_entries - {"manifest.json"}:
                if required not in manifest_paths:
                    errors.append(f"manifest missing hash for {required}")
            for entry in entries:
                if not isinstance(entry, dict):
                    errors.append("manifest entry is not an object")
                    continue
                path = str(entry.get("path") or "")
                expected_hash = str(entry.get("sha256") or "")
                if not path or not expected_hash:
                    errors.append(f"manifest entry has blank path or sha256: {entry}")
                    continue
                if path not in names:
                    errors.append(f"manifest hashes missing ZIP entry {path}")
                    continue
                actual_hash = hashlib.sha256(bundle.read(path)).hexdigest()
                if actual_hash != expected_hash:
                    errors.append(f"sha256 mismatch for {path}")
            signature_verified = validate_manifest_signature(
                manifest,
                signature_secret,
                signature_key_id,
                errors,
            )
            return manifest, errors, signature_verified
    except (zipfile.BadZipFile, json.JSONDecodeError, KeyError) as exc:
        return {}, [f"bundle validation failed: {exc}"], signature_verified


def validate_manifest_signature(
    manifest: dict[str, Any],
    signature_secret: str,
    signature_key_id: str,
    errors: list[str],
) -> bool:
    if not signature_secret:
        return False
    signature = manifest.get("signature")
    if not isinstance(signature, dict):
        errors.append("manifest signature is missing")
        return False
    if signature.get("enabled") is not True:
        errors.append("manifest signature is not enabled")
        return False
    if signature.get("algorithm") != "HMAC-SHA256":
        errors.append("manifest signature algorithm is not HMAC-SHA256")
        return False
    if signature.get("canonicalization") != "bundle-manifest-v1":
        errors.append("manifest signature canonicalization is not bundle-manifest-v1")
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


def canonical_manifest(manifest: dict[str, Any]) -> str:
    canonical = [
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
        canonical.append(f"entry:{entry.get('path', '')}={entry.get('sha256', '')}")
    return "\n".join(canonical) + "\n"


def audit_event_count(client: Client, report_id: str) -> int:
    query = urllib.parse.urlencode({
        "event_type": "evidence.bundle_exported",
        "resource_id": report_id,
        "outcome": "success",
        "limit": 20,
    })
    events = client.request("GET", f"/api/audit/events?{query}")
    if not isinstance(events, list):
        raise RuntimeError("audit events response is not a list")
    return len(events)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if not args.password:
        print("RCA_ADMIN_PASSWORD or RCA_PASSWORD is required", file=sys.stderr)
        return 2
    if args.task_timeout_seconds < 1:
        print("--task-timeout-seconds must be positive", file=sys.stderr)
        return 2

    started_at = datetime.now(timezone.utc)
    output_dir = Path(args.output_dir)
    run_id = started_at.strftime("%Y%m%d-%H%M%S")
    run_dir = output_dir / run_id
    client = Client(args.base_url, args.http_timeout_seconds)

    print(f"Logging in to {args.base_url} as {args.username}")
    client.login(args.username, args.password)
    scenario_response = client.request("GET", "/api/demo/scenarios")
    if not scenario_response.get("enabled"):
        raise RuntimeError("demo scenario mode is disabled on the target platform")

    selected = choose_scenarios(args.scenarios, scenario_response.get("scenarios", []))
    print("Selected scenarios: " + ", ".join(str(scenario["key"]) for scenario in selected))

    results: list[dict[str, Any]] = []
    for scenario in selected:
        key = str(scenario["key"])
        scenario_cluster = (
            {"cluster_id": args.cluster_id}
            if args.cluster_id
            else create_validation_cluster(client, args.cluster_prefix, key, run_id)
        )
        scenario_cluster_id = str(scenario_cluster["cluster_id"])
        print(f"Running scenario: {key}")
        run_response = client.request(
            "POST",
            f"/api/demo/scenarios/{urllib.parse.quote(key)}/run",
            {
                "confirmed": True,
                "cluster_id": scenario_cluster_id,
                "node_name": args.node_name,
            },
        )
        task_id = run_response["analysis_task"]["task_id"]
        task = wait_for_task(client, task_id, args.task_timeout_seconds, args.poll_seconds)
        report_id = task.get("report_id")
        report = None
        timeline = None
        errors: list[str] = []
        if task.get("status") != "completed":
            errors.append(f"analysis task ended with status={task.get('status')}")
        elif not report_id:
            errors.append("completed task has no report_id")
        else:
            report = client.request("GET", f"/api/rca/reports/{urllib.parse.quote(str(report_id))}")
            incident_id = report.get("incident_id")
            if incident_id:
                timeline = client.request(
                    "GET",
                    f"/api/rca/incidents/{urllib.parse.quote(str(incident_id))}/timeline",
                )
            errors.extend(validate_report(key, report, timeline, args.min_confidence_score))

        bundle_manifest: dict[str, Any] | None = None
        bundle_entry_count = 0
        bundle_signature_verified = False
        audit_export_count = 0
        if report_id:
            bundle_bytes = client.request_bytes(
                "GET",
                f"/api/rca/reports/{urllib.parse.quote(str(report_id))}/bundle",
            )
            if args.save_bundles:
                bundle_path = run_dir / "bundles" / f"{key}.zip"
                bundle_path.parent.mkdir(parents=True, exist_ok=True)
                bundle_path.write_bytes(bundle_bytes)
            bundle_manifest, bundle_errors, bundle_signature_verified = validate_bundle(
                bundle_bytes,
                args.bundle_signature_secret,
                args.bundle_signature_key_id,
            )
            bundle_entry_count = len(bundle_manifest.get("entries") or []) if bundle_manifest else 0
            errors.extend(bundle_errors)
            write_json(run_dir / "bundle-manifests" / f"{key}.json", bundle_manifest)
            if not args.skip_audit_check:
                audit_export_count = audit_event_count(client, str(report_id))
                if audit_export_count < 1:
                    errors.append("bundle export audit event was not recorded")

        signal_names = [signal.get("signal") for signal in derived_signals(report or {})]
        result = {
            "scenario_key": key,
            "scenario_name": scenario.get("name"),
            "cluster_id": scenario_cluster_id,
            "task_id": task_id,
            "task_status": task.get("status"),
            "report_id": report_id,
            "incident_id": None if report is None else report.get("incident_id"),
            "signal_names": signal_names,
            "root_cause_count": len((report or {}).get("root_cause_candidates") or []),
            "recommended_action_count": len((report or {}).get("recommended_actions") or []),
            "timeline_node_count": 0 if timeline is None else len(timeline.get("nodes") or []),
            "bundle_manifest_entry_count": bundle_entry_count,
            "bundle_signature_verified": bundle_signature_verified,
            "bundle_export_audit_event_count": audit_export_count,
            "passed": not errors,
            "errors": errors,
        }
        results.append(result)
        write_json(run_dir / "reports" / f"{key}.json", report or {"error": errors})
        if timeline is not None:
            write_json(run_dir / "timelines" / f"{key}.json", timeline)
        print(("PASS" if result["passed"] else "FAIL") + f" {key}: " + "; ".join(errors or ["ok"]))

    summary = {
        "schema_version": "1.0",
        "started_at": started_at.isoformat(),
        "completed_at": datetime.now(timezone.utc).isoformat(),
        "base_url": args.base_url,
        "node_name": args.node_name,
        "cluster_id": args.cluster_id,
        "cluster_isolation": "provided_cluster_id" if args.cluster_id else "cluster_per_scenario",
        "scenario_count": len(results),
        "passed_count": sum(1 for result in results if result["passed"]),
        "failed_count": sum(1 for result in results if not result["passed"]),
        "results": results,
    }
    write_json(run_dir / "summary.json", summary)
    print(f"Validation summary written to {run_dir / 'summary.json'}")
    return 0 if summary["failed_count"] == 0 else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        raise SystemExit(130)
    except Exception as exc:
        print(f"Validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
