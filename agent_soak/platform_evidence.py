"""Platform-backed evidence collection for Agent fleet validation."""

from __future__ import annotations

import json
import math
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class PlatformEvidenceError(RuntimeError):
    """Raised when a fleet evidence campaign cannot be created safely."""


class PlatformEvidenceClient:
    __slots__ = (
        "base_url",
        "cluster_id",
        "_access_token",
        "request_timeout_seconds",
        "max_response_bytes",
        "_ssl_context",
    )

    def __init__(
        self,
        base_url: str,
        cluster_id: str,
        access_token: str,
        *,
        request_timeout_seconds: float = 15,
        max_response_bytes: int = 16 * 1024 * 1024,
        ca_bundle: str | None = None,
    ) -> None:
        self.base_url = validate_platform_url(base_url)
        self.cluster_id = validate_cluster_id(cluster_id)
        self._access_token = validate_access_token(access_token)
        if not math.isfinite(request_timeout_seconds) or request_timeout_seconds <= 0:
            raise ValueError("Platform request timeout must be positive")
        if max_response_bytes < 64 * 1024:
            raise ValueError("Platform maximum response bytes must be at least 65536")
        self.request_timeout_seconds = request_timeout_seconds
        self.max_response_bytes = max_response_bytes
        self._ssl_context = ssl.create_default_context(cafile=ca_bundle or None)

    def collect_fleet(
        self,
        targets: list[dict[str, str]],
        collectors: list[str],
        *,
        iteration: int,
        completion_timeout_seconds: float,
        poll_interval_seconds: float = 0.5,
    ) -> list[dict[str, Any]]:
        if not targets:
            return []
        if completion_timeout_seconds <= 0 or poll_interval_seconds <= 0:
            raise ValueError("Platform completion timeout and poll interval must be positive")
        node_to_target = {}
        for target in targets:
            node_name = target.get("node_name")
            target_id = target.get("target_id")
            if not node_name or not target_id or node_name in node_to_target:
                raise ValueError("fleet targets must have unique node names and target IDs")
            node_to_target[node_name] = target_id

        created_at = time.monotonic()
        response = self._request_json(
            "POST",
            f"/api/clusters/{urllib.parse.quote(self.cluster_id, safe='')}/collection-runs",
            {
                "confirmed": True,
                "alert_name": "AgentFleetBurnIn",
                "node_names": sorted(node_to_target),
                "requested_collectors": collectors,
                "reason": "Read-only Agent fleet evidence validation",
                "context": {
                    "source": "agent_fleet_burn_in",
                    "read_only": True,
                    "iteration": iteration,
                },
            },
        )
        if not isinstance(response, dict):
            raise PlatformEvidenceError("Platform collection response is not an object")
        skipped = response.get("skipped_nodes")
        created = response.get("created_evidence_requests")
        if skipped or not isinstance(created, list) or len(created) != len(targets):
            raise PlatformEvidenceError("Platform did not create one evidence request for every fleet target")

        pending = {}
        for item in created:
            if not isinstance(item, dict):
                raise PlatformEvidenceError("Platform returned an invalid evidence request")
            node_name = item.get("node_name")
            request_id = item.get("request_id")
            if node_name not in node_to_target or not isinstance(request_id, str) or not request_id:
                raise PlatformEvidenceError("Platform evidence request assignment is invalid")
            target_id = node_to_target[node_name]
            if target_id in pending:
                raise PlatformEvidenceError("Platform returned duplicate evidence request assignments")
            pending[target_id] = request_id

        deadline = time.monotonic() + completion_timeout_seconds
        results: dict[str, dict[str, Any]] = {}
        while pending and time.monotonic() < deadline:
            for target_id, request_id in list(pending.items()):
                status = self._request_json(
                    "GET",
                    f"/api/evidence/requests/{urllib.parse.quote(request_id, safe='')}",
                )
                if not isinstance(status, dict):
                    results[target_id] = failed_result(target_id, "Platform returned an invalid request status")
                    pending.pop(target_id)
                    continue
                state = status.get("status")
                if state == "failed":
                    results[target_id] = failed_result(target_id, "Agent evidence request failed")
                    pending.pop(target_id)
                    continue
                if state != "completed":
                    continue
                evidence_id = status.get("evidence_id")
                if not isinstance(evidence_id, str) or not evidence_id:
                    results[target_id] = failed_result(target_id, "Completed evidence request has no evidence bundle")
                    pending.pop(target_id)
                    continue
                evidence = self._request_json(
                    "GET",
                    f"/api/evidence/{urllib.parse.quote(evidence_id, safe='')}",
                )
                collectors_payload = evidence.get("collectors") if isinstance(evidence, dict) else None
                if not isinstance(collectors_payload, dict):
                    results[target_id] = failed_result(target_id, "Platform returned an invalid evidence bundle")
                else:
                    encoded = json.dumps(
                        collectors_payload,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        default=str,
                    ).encode("utf-8")
                    results[target_id] = {
                        "target_id": target_id,
                        "success": True,
                        "duration_seconds": round(time.monotonic() - created_at, 6),
                        "payload_bytes": len(encoded),
                        "collectors": collectors_payload,
                        "error": None,
                    }
                pending.pop(target_id)
            if pending:
                time.sleep(min(poll_interval_seconds, max(0.0, deadline - time.monotonic())))

        for target_id in pending:
            results[target_id] = failed_result(target_id, "Agent evidence request timed out")
        return [results[target["target_id"]] for target in sorted(targets, key=lambda item: item["target_id"])]

    def _request_json(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self._access_token}",
                "Content-Type": "application/json",
                "User-Agent": "cluster-rca-agent-soak/2",
            },
            method=method,
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=self.request_timeout_seconds,
                context=self._ssl_context,
            ) as response:
                encoded = response.read(self.max_response_bytes + 1)
        except urllib.error.HTTPError as exc:
            raise PlatformEvidenceError(f"Platform API returned HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise PlatformEvidenceError("Platform API request failed") from exc
        if len(encoded) > self.max_response_bytes:
            raise PlatformEvidenceError("Platform API response exceeded the configured limit")
        try:
            return json.loads(encoded.decode("utf-8")) if encoded else {}
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise PlatformEvidenceError("Platform API returned invalid JSON") from exc


def validate_platform_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("Platform URL must use http or https")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Platform URL must not contain credentials, query parameters, or fragments")
    if parsed.path not in {"", "/"}:
        raise ValueError("Platform URL must not include a path")
    return value.rstrip("/")


def validate_cluster_id(value: str) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > 255 or any(ord(character) < 32 for character in normalized):
        raise ValueError("Platform cluster ID is invalid")
    return normalized


def validate_access_token(value: str) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > 4096 or any(character.isspace() for character in normalized):
        raise ValueError("Platform access token is invalid")
    return normalized


def failed_result(target_id: str, error: str) -> dict[str, Any]:
    return {
        "target_id": target_id,
        "success": False,
        "duration_seconds": None,
        "payload_bytes": 0,
        "collectors": {},
        "error": error,
    }
