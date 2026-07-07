#!/usr/bin/env python3
"""Verify container build inputs pin every base image by digest."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILES = ("Dockerfile.web-console", "Dockerfile.agent")
FROM_RE = re.compile(r"^FROM\s+(?P<image>[^\s]+)", re.IGNORECASE)


def check_file(path: Path) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    if not path.exists():
        return [{
            "file": str(path.relative_to(ROOT)),
            "line": None,
            "image": None,
            "pinned": False,
            "reason": "missing_file",
        }]
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = FROM_RE.match(line.strip())
        if not match:
            continue
        image = match.group("image")
        findings.append({
            "file": str(path.relative_to(ROOT)),
            "line": line_number,
            "image": image,
            "pinned": "@sha256:" in image,
        })
    if not findings:
        findings.append({
            "file": str(path.relative_to(ROOT)),
            "line": None,
            "image": None,
            "pinned": False,
            "reason": "no_from_instruction",
        })
    return findings


def main() -> int:
    findings = [finding for dockerfile in DOCKERFILES for finding in check_file(ROOT / dockerfile)]
    unpinned = [finding for finding in findings if not finding["pinned"]]
    print(json.dumps({
        "status": "failed" if unpinned else "passed",
        "checked_files": list(DOCKERFILES),
        "findings": findings,
    }, indent=2))
    return 1 if unpinned else 0


if __name__ == "__main__":
    sys.exit(main())
