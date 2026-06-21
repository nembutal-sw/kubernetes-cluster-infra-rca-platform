from __future__ import annotations

import re
from typing import Any


REDACTED = "[redacted]"
SENSITIVE_KEYS = {
    "authorization",
    "cookie",
    "set_cookie",
    "token",
    "access_token",
    "refresh_token",
    "agent_token",
    "node_token",
    "password",
    "passwd",
    "secret",
    "api_key",
    "apikey",
    "client_certificate_data",
    "client_key_data",
    "certificate_authority_data",
}
ASSIGNMENT = re.compile(
    r"(?i)(api[_-]?key|authorization|token|password|passwd|secret|cookie)"
    r"(\s*[:=]\s*)[^\s,;]+"
)
BEARER = re.compile(r"(?i)bearer\s+[a-z0-9._~+/-]+")
KNOWN_TOKEN = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{20,}|"
    r"xox[baprs]-[A-Za-z0-9-]{10,}|(?:AKIA|ASIA)[A-Z0-9]{16})"
)
CREDENTIAL_URL = re.compile(
    r"(?i)([a-z][a-z0-9+.-]*://[^\s:/]+:)[^@\s]+(@)"
)


def redact_value(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): REDACTED if is_sensitive_key(str(key)) else redact_value(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_value(item) for item in value]
    if isinstance(value, tuple):
        return [redact_value(item) for item in value]
    if isinstance(value, str):
        redacted = ASSIGNMENT.sub(rf"\1\2{REDACTED}", value)
        redacted = BEARER.sub(f"Bearer {REDACTED}", redacted)
        redacted = KNOWN_TOKEN.sub(REDACTED, redacted)
        return CREDENTIAL_URL.sub(rf"\1{REDACTED}\2", redacted)
    return value


def is_sensitive_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_").strip()
    return (
        normalized in SENSITIVE_KEYS
        or normalized.endswith("_token")
        or normalized.endswith("_password")
        or normalized.endswith("_secret")
        or normalized.endswith("_api_key")
    )
