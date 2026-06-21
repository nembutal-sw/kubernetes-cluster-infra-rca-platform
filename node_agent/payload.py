from __future__ import annotations

import json
from typing import Any


def bounded_collectors_payload(
    collectors: dict[str, Any],
    max_bytes: int,
) -> dict[str, Any]:
    result = dict(collectors)
    encoded_size = _size(result)
    if encoded_size <= max_bytes:
        return result

    truncated: list[dict[str, Any]] = []
    candidates = sorted(
        ((_size(value), name) for name, value in result.items()),
        reverse=True,
    )
    for original_size, name in candidates:
        result[name] = {
            "status": "truncated",
            "error": "collector output exceeded the evidence response size budget",
            "original_size_bytes": original_size,
        }
        truncated.append({"collector": name, "original_size_bytes": original_size})
        if _size(result) <= max_bytes:
            break

    result["_agent_payload"] = {
        "status": "truncated",
        "max_bytes": max_bytes,
        "original_size_bytes": encoded_size,
        "truncated_collectors": truncated,
    }
    return result


def _size(value: Any) -> int:
    return len(json.dumps(value, ensure_ascii=False, default=str).encode("utf-8"))
