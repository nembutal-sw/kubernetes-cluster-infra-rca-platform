#!/usr/bin/env python3
"""Shared validation helpers for managed blind evaluation artifacts."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CASE_ID_PATTERN = re.compile(r"managed-[a-f0-9]{24}")
REVIEWER_ID_PATTERN = re.compile(r"reviewer_[a-f0-9]{8,32}")
CLASSIFICATIONS = {
    "negative",
    "boundary",
    "single_fault",
    "compound_fault",
    "degraded_evidence",
}
FORBIDDEN_EVIDENCE_FIELDS = {
    "action_plan",
    "alert_name",
    "allowed_signals",
    "analyzer_output",
    "expected_signals",
    "forbidden_signals",
    "labels",
    "report",
    "root_cause",
    "root_cause_candidates",
    "signals",
}
SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(authorization|api[_-]?key|access[_-]?token|agent[_-]?token|node[_-]?token|"
    r"password|private[_-]?key|cookie|token|secret)\b\s*[:=]\s*([^\s,;]+)"
)
BEARER_VALUE = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*")
IPV4 = re.compile(
    r"(?<![0-9])(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})"
    r"(?:\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}(?![0-9])"
)
IPV6 = re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])")
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
URL_AUTHORITY = re.compile(r"(?i)\b(https?|tcp)://(?:[^\s/@]+@)?[^\s/:]+")
UUID = re.compile(
    r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"
)
ISO_TIMESTAMP = re.compile(
    r"\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})\b"
)
HOME_PATH = re.compile(r"(?i)(?:/home/|/Users/|[A-Z]:\\Users\\)[^/\\\s]+")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is unavailable or invalid: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return value


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def require_empty_output_dir(path: Path) -> None:
    if path.exists() and any(path.iterdir()):
        raise ValueError(f"output directory must be empty: {path}")
    path.mkdir(parents=True, exist_ok=True)


def validate_case_id(case_id: str) -> None:
    if not CASE_ID_PATTERN.fullmatch(case_id):
        raise ValueError("case_id must match managed- followed by 24 lowercase hexadecimal characters")


def validate_evidence_candidate(candidate: dict[str, Any]) -> None:
    if candidate.get("schema_version") != "rca-managed-blind-evidence/v1":
        raise ValueError("unexpected managed blind evidence schema")
    validate_case_id(str(candidate.get("case_id", "")))
    provenance = candidate.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("evidence provenance must be an object")
    if provenance.get("contains_raw_customer_data") is not False:
        raise ValueError("evidence must declare contains_raw_customer_data=false")
    if provenance.get("analyzer_output_included") is not False:
        raise ValueError("evidence must declare analyzer_output_included=false")
    collectors = candidate.get("collectors")
    if not isinstance(collectors, dict) or not collectors:
        raise ValueError("evidence collectors must be a non-empty object")
    forbidden: list[str] = []

    def visit(value: Any, path: str) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key.lower() in FORBIDDEN_EVIDENCE_FIELDS:
                    forbidden.append(f"{path}.{key}")
                visit(child, f"{path}.{key}")
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{path}[{index}]")

    visit(candidate, "$")
    if forbidden:
        raise ValueError(f"evidence contains analyzer or label fields: {', '.join(forbidden)}")


def validate_rfc3339(value: str, label: str) -> None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{label} must be an RFC 3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{label} must include a timezone")


def sanitize_string(value: str, sensitive_values: set[str]) -> str:
    sanitized = value
    for sensitive in sorted(sensitive_values, key=len, reverse=True):
        if len(sensitive) >= 3:
            sanitized = sanitized.replace(sensitive, "[redacted-identifier]")
    sanitized = SECRET_ASSIGNMENT.sub(lambda match: f"{match.group(1)}=[redacted]", sanitized)
    sanitized = BEARER_VALUE.sub("Bearer [redacted]", sanitized)
    sanitized = EMAIL.sub("[redacted-email]", sanitized)
    sanitized = URL_AUTHORITY.sub(lambda match: f"{match.group(1)}://redacted.invalid", sanitized)
    sanitized = IPV4.sub("[redacted-ip]", sanitized)
    sanitized = IPV6.sub("[redacted-ip]", sanitized)
    sanitized = UUID.sub("[redacted-uuid]", sanitized)
    sanitized = ISO_TIMESTAMP.sub("[redacted-timestamp]", sanitized)
    sanitized = HOME_PATH.sub("/home/[redacted-user]", sanitized)
    return sanitized
