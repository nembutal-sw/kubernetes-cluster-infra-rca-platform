#!/usr/bin/env python3
"""Validate living documentation against repository contracts."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
DOC_INDEX = ROOT / "docs" / "README.md"
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*]\(\s*(<[^>]+>|[^)\s]+)")
REFERENCE_LINK = re.compile(r"^\s*\[[^\]]+]:\s*(<[^>]+>|\S+)", re.MULTILINE)
MIGRATION_VERSION = re.compile(r"^V(\d+)__")
EXTERNAL_TARGET = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)

HISTORICAL_DOCUMENTS = (
    "docs/code-review-action-plan-2026-07-10.md",
    "docs/enterprise-improvement-plan.md",
    "docs/phase1-structure-stabilization.md",
    "docs/phase3-testing-ci.md",
    "docs/stabilization-roadmap.md",
)


def markdown_files() -> list[Path]:
    paths = [ROOT / "README.md", ROOT / "SECURITY.md", ROOT / "web-console" / "README.md"]
    paths.extend(sorted((ROOT / "docs").glob("*.md")))
    return paths


def read_utf8(path: Path) -> str:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exception:
        raise ValueError(f"{path.relative_to(ROOT)} is not valid UTF-8: {exception}") from exception
    if "\ufffd" in text:
        raise ValueError(f"{path.relative_to(ROOT)} contains a Unicode replacement character")
    return text


def local_link_errors(paths: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in paths:
        text = read_utf8(path)
        targets = MARKDOWN_LINK.findall(text) + REFERENCE_LINK.findall(text)
        for raw_target in targets:
            target = raw_target.strip("<>")
            if not target or target.startswith(("#", "//")) or EXTERNAL_TARGET.match(target):
                continue
            decoded = unquote(target.split("#", 1)[0].split("?", 1)[0])
            if not decoded or decoded.startswith("/"):
                continue
            resolved = (path.parent / decoded).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                errors.append(f"{path.relative_to(ROOT)} links outside repository: {target}")
                continue
            if not resolved.exists():
                errors.append(f"{path.relative_to(ROOT)} has missing local link: {target}")
    return errors


def migration_versions() -> set[int]:
    paths = list((ROOT / "web-console" / "src" / "main" / "resources" / "db" / "migration").glob("V*__*.sql"))
    paths.extend((ROOT / "web-console" / "src" / "main" / "java" / "db" / "migration").glob("V*__*.java"))
    versions: set[int] = set()
    for path in paths:
        match = MIGRATION_VERSION.match(path.name)
        if match:
            versions.add(int(match.group(1)))
    return versions


def contract_errors(paths: list[Path]) -> list[str]:
    errors: list[str] = []
    contents = {path.relative_to(ROOT).as_posix(): read_utf8(path) for path in paths}
    current_state = contents["docs/current-state.md"]
    root_readme = contents["README.md"]
    platform_readme = contents["web-console/README.md"]
    index = contents["docs/README.md"]

    versions = migration_versions()
    latest = max(versions, default=0)
    if versions != set(range(1, latest + 1)):
        errors.append(f"Flyway versions are not contiguous: {sorted(versions)}")
    expected_schema = f"Flyway V{latest}"
    expected_count = f"{len(versions)} migrations"
    expected_korean_count = f"총 {len(versions)}개 migration"
    if expected_schema not in current_state or expected_korean_count not in current_state:
        errors.append(
            f"docs/current-state.md must declare {expected_schema} and {expected_korean_count}"
        )
    if expected_schema not in root_readme or expected_count not in root_readme:
        errors.append(f"README.md must declare {expected_schema} and {expected_count}")
    if expected_korean_count not in platform_readme:
        errors.append("web-console/README.md must declare the current migration count")

    stale_claims = ("23 migrations", "22개 migration")
    for name in ("README.md", "web-console/README.md", "docs/current-state.md"):
        for claim in stale_claims:
            if claim in contents[name]:
                errors.append(f"{name} contains stale migration claim: {claim}")

    collector_source = (
        ROOT / "node_agent" / "collectors" / "registry.py"
    ).read_text(encoding="utf-8")
    collectors = set(re.findall(r'^\s*"([a-z-]+)": _definition', collector_source, re.MULTILINE))
    for name in sorted(collectors):
        if name not in current_state:
            errors.append(f"docs/current-state.md is missing collector: {name}")
    if "| journal |" in contents["docs/agent-design.md"]:
        errors.append("docs/agent-design.md documents a nonexistent standalone journal collector")

    if "향후 Kubernetes ServiceAccount TokenReview" in contents["docs/threat-model.md"]:
        errors.append("docs/threat-model.md still describes implemented TokenReview as future work")

    for relative in HISTORICAL_DOCUMENTS:
        if "**역사 문서:**" not in contents[relative]:
            errors.append(f"{relative} must be marked as a historical document")

    for path in sorted((ROOT / "docs").glob("*.md")):
        if path.name == "README.md":
            continue
        if f"]({path.name})" not in index:
            errors.append(f"docs/README.md does not index {path.name}")

    return errors


def main() -> int:
    paths = markdown_files()
    errors: list[str] = []
    try:
        errors.extend(local_link_errors(paths))
        errors.extend(contract_errors(paths))
    except ValueError as exception:
        errors.append(str(exception))

    if errors:
        print("Documentation validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        "Documentation validation passed: "
        f"{len(paths)} UTF-8 Markdown files, local links, Flyway, Agent, and history contracts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
