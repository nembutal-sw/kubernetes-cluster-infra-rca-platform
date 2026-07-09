#!/usr/bin/env python3
"""Static API contract guard for controller routes and security boundaries."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER_DIR = ROOT / "web-console/src/main/java/io/clusterinfra/rca/webconsole/controller"
SECURITY_DIR = ROOT / "web-console/src/main/java/io/clusterinfra/rca/webconsole/security"
SECURITY_CONFIG = ROOT / "web-console/src/main/java/io/clusterinfra/rca/webconsole/config/SecurityConfig.java"

MAPPING_RE = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\((.*?)\))?")
STRING_RE = re.compile(r'"([^"]*)"')
PRE_AUTHORIZE_RE = re.compile(r"@PreAuthorize\((?P<expr>.*?)\)")
ROLE_RE = re.compile(r"'([A-Z_]+)'")

PUBLIC_API_PATHS = {
    "/api/auth/login",
}
SESSION_ONLY_PATHS = {
    "/api/auth/me",
    "/api/auth/logout",
    "/api/auth/change-password",
    "/api/auth/change-login-id",
}
EXPECTED_AGENT_PATHS = {
    "/api/agents/register",
    "/api/agents/heartbeat",
    "/api/agents/evidence-requests",
    "/api/agents/evidence-responses",
    "/api/agents/realtime-events",
    "/api/agents/action-executions",
    "/api/agents/action-results",
}
EXPECTED_WEBHOOK_PATHS = {
    "/api/webhooks/alertmanager",
}
VERSIONED_REQUIRED_PATHS = {
    "/api/v1/platform/info",
}


@dataclass(frozen=True)
class Endpoint:
    file: str
    line: int
    method: str
    path: str
    preauthorize: str | None

    def roles(self) -> set[str]:
        if not self.preauthorize:
            return set()
        return set(ROLE_RE.findall(self.preauthorize))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def extract_paths(annotation: str) -> list[str]:
    match = MAPPING_RE.search(annotation)
    if not match:
        return []
    body = match.group(2)
    if body is None or body.strip() == "":
        return [""]
    values = STRING_RE.findall(body)
    return values or [""]


def combine(base: str, path: str) -> str:
    if not base:
        return path or "/"
    if not path:
        return base
    if path.startswith("/"):
        return f"{base.rstrip('/')}{path}"
    return f"{base.rstrip('/')}/{path}"


def normalize_http_method(annotation_name: str) -> str:
    if annotation_name == "Request":
        return "ANY"
    return annotation_name.upper()


def annotation_text(lines: list[str], start: int) -> str:
    text = lines[start].strip()
    index = start
    while text.count("(") > text.count(")") and index + 1 < len(lines):
        index += 1
        text += " " + lines[index].strip()
    return text


def previous_annotations(lines: list[str], start: int) -> str:
    annotations: list[str] = []
    index = start - 1
    while index >= 0:
        stripped = lines[index].strip()
        if stripped.startswith("@"):
            annotations.append(stripped)
            index -= 1
            continue
        if not stripped:
            index -= 1
            continue
        break
    return " ".join(reversed(annotations))


def following_annotations(lines: list[str], start: int) -> str:
    annotations: list[str] = []
    index = start + 1
    while index < min(len(lines), start + 8):
        stripped = lines[index].strip()
        if stripped.startswith("@"):
            annotations.append(stripped)
            index += 1
            continue
        if not stripped:
            index += 1
            continue
        break
    return " ".join(annotations)


def preauthorize_near(lines: list[str], start: int) -> str | None:
    context = previous_annotations(lines, start) + " " + following_annotations(lines, start)
    match = PRE_AUTHORIZE_RE.search(context)
    return match.group("expr") if match else None


def class_base(lines: list[str]) -> str:
    for index, line in enumerate(lines):
        if " class " in f" {line} " or line.strip().startswith("class "):
            return ""
        if "@RequestMapping" not in line:
            continue
        return extract_paths(annotation_text(lines, index))[0]
    return ""


def parse_controller(path: Path) -> list[Endpoint]:
    lines = path.read_text(encoding="utf-8").splitlines()
    base = class_base(lines)
    endpoints: list[Endpoint] = []
    class_seen = False
    for index, line in enumerate(lines):
        if " class " in f" {line} " or line.strip().startswith("class "):
            class_seen = True
        if not class_seen or "Mapping" not in line:
            continue
        annotation = annotation_text(lines, index)
        match = MAPPING_RE.search(annotation)
        if not match:
            continue
        mapping_name = match.group(1)
        for path_value in extract_paths(annotation):
            full_path = combine(base, path_value)
            endpoints.append(Endpoint(
                file=str(path.relative_to(ROOT)),
                line=index + 1,
                method=normalize_http_method(mapping_name),
                path=full_path,
                preauthorize=preauthorize_near(lines, index),
            ))
    return endpoints


def parse_string_set(path: Path) -> set[str]:
    return set(STRING_RE.findall(read(path)))


def is_manifest_path(path: str) -> bool:
    return bool(re.fullmatch(r"/api/clusters/\{[^/]+}/agent-manifest", path))


def is_mutation(method: str) -> bool:
    return method in {"POST", "PUT", "PATCH", "DELETE"}


def endpoint_record(endpoint: Endpoint, status: str, reason: str) -> dict[str, object]:
    return {
        "status": status,
        "reason": reason,
        "method": endpoint.method,
        "path": endpoint.path,
        "roles": sorted(endpoint.roles()),
        "file": endpoint.file,
        "line": endpoint.line,
    }


def validate_endpoint(endpoint: Endpoint) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    path = endpoint.path
    roles = endpoint.roles()

    if not path.startswith("/api/"):
        return findings
    if path in PUBLIC_API_PATHS:
        return findings
    if path in SESSION_ONLY_PATHS:
        return findings
    if path in EXPECTED_AGENT_PATHS:
        return findings
    if path in EXPECTED_WEBHOOK_PATHS:
        return findings
    if path.startswith("/api/webhooks/"):
        return findings
    if is_manifest_path(path):
        return findings
    if not endpoint.preauthorize:
        findings.append(endpoint_record(endpoint, "failed", "api_endpoint_missing_pre_authorize"))
        return findings
    if is_mutation(endpoint.method) and "VIEWER" in roles:
        findings.append(endpoint_record(endpoint, "failed", "mutating_endpoint_allows_viewer"))
    if ("/export" in path or "/bundle" in path) and {"VIEWER", "APPROVER"} & roles:
        findings.append(endpoint_record(endpoint, "failed", "sensitive_export_allows_viewer_or_approver"))
    if path.endswith("/agent-token/rotate") and roles != {"ADMIN"}:
        findings.append(endpoint_record(endpoint, "failed", "agent_token_rotation_must_be_admin_only"))
    if endpoint.method == "DELETE" and re.fullmatch(r"/api/clusters/\{[^/]+}", path) and roles != {"ADMIN"}:
        findings.append(endpoint_record(endpoint, "failed", "cluster_delete_must_be_admin_only"))
    return findings


def security_filter_findings(endpoints: list[Endpoint]) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    mapped_agent_paths = {endpoint.path for endpoint in endpoints if endpoint.path.startswith("/api/agents/")}
    agent_filter_paths = parse_string_set(SECURITY_DIR / "AgentAuthenticationFilter.java")
    missing_agent_filter = sorted(mapped_agent_paths - agent_filter_paths)
    if missing_agent_filter:
        findings.append({
            "status": "failed",
            "reason": "agent_endpoint_not_covered_by_agent_filter",
            "paths": missing_agent_filter,
        })

    mapped_webhook_paths = {endpoint.path for endpoint in endpoints if endpoint.path.startswith("/api/webhooks/")}
    webhook_filter_paths = parse_string_set(SECURITY_DIR / "WebhookAuthenticationFilter.java")
    missing_webhook_filter = sorted(mapped_webhook_paths - webhook_filter_paths)
    if missing_webhook_filter:
        findings.append({
            "status": "failed",
            "reason": "webhook_endpoint_not_covered_by_webhook_filter",
            "paths": missing_webhook_filter,
        })

    mapped_manifest_paths = {endpoint.path for endpoint in endpoints if is_manifest_path(endpoint.path)}
    manifest_filter_text = read(SECURITY_DIR / "ManifestAccessFilter.java")
    if mapped_manifest_paths and "agent-manifest" not in manifest_filter_text:
        findings.append({
            "status": "failed",
            "reason": "manifest_endpoint_not_covered_by_manifest_filter",
            "paths": sorted(mapped_manifest_paths),
        })

    security_config_text = read(SECURITY_CONFIG)
    required_public_guards = PUBLIC_API_PATHS | EXPECTED_AGENT_PATHS | EXPECTED_WEBHOOK_PATHS | mapped_webhook_paths
    missing_permit_entries = sorted(path for path in required_public_guards if path not in security_config_text)
    if "/api/clusters/*/agent-manifest" not in security_config_text:
        missing_permit_entries.append("/api/clusters/*/agent-manifest")
    if missing_permit_entries:
        findings.append({
            "status": "failed",
            "reason": "security_config_missing_custom_guard_permit_entry",
            "paths": missing_permit_entries,
        })

    all_api_paths = {endpoint.path for endpoint in endpoints if endpoint.path.startswith("/api/")}
    missing_versioned = sorted(VERSIONED_REQUIRED_PATHS - all_api_paths)
    if missing_versioned:
        findings.append({
            "status": "failed",
            "reason": "required_versioned_api_missing",
            "paths": missing_versioned,
        })
    return findings


def main() -> int:
    endpoints = sorted(
        [endpoint for path in CONTROLLER_DIR.glob("*.java") for endpoint in parse_controller(path)],
        key=lambda endpoint: (endpoint.path, endpoint.method, endpoint.file, endpoint.line),
    )
    findings = [finding for endpoint in endpoints for finding in validate_endpoint(endpoint)]
    findings.extend(security_filter_findings(endpoints))

    print(json.dumps({
        "status": "failed" if findings else "passed",
        "api_endpoint_count": sum(1 for endpoint in endpoints if endpoint.path.startswith("/api/")),
        "findings": findings,
        "endpoints": [
            {
                "method": endpoint.method,
                "path": endpoint.path,
                "roles": sorted(endpoint.roles()),
                "file": endpoint.file,
                "line": endpoint.line,
            }
            for endpoint in endpoints
            if endpoint.path.startswith("/api/")
        ],
    }, indent=2))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
