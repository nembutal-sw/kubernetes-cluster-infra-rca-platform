#!/usr/bin/env python3
"""Build a portable, deduplicated history bundle from LLM smoke results."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_NAME = "llm-staging-smoke-result.json"
SCHEMA_VERSION = "llm-burn-in-history/v1"
REPORT_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge LLM staging smoke results into a portable history bundle."
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        help=f"Existing {RESULT_NAME} file or directory.",
    )
    parser.add_argument("--output-dir", required=True)
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="Write an empty manifest when no smoke results exist.",
    )
    return parser.parse_args()


def discover_results(inputs: list[str]) -> list[Path]:
    discovered: dict[str, Path] = {}
    for raw in inputs:
        path = Path(raw)
        candidates = [path] if path.is_file() else path.rglob(RESULT_NAME) if path.is_dir() else []
        for candidate in candidates:
            if candidate.name != RESULT_NAME:
                continue
            resolved = candidate.resolve()
            discovered[str(resolved)] = resolved
    return [discovered[key] for key in sorted(discovered)]


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("JSON root must be an object")
    return payload


def parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sibling_report(result_path: Path, result: dict[str, Any]) -> tuple[Path | None, str | None]:
    report_id = str(result.get("report_id") or "").strip()
    if not report_id:
        return None, "passed smoke result has no report_id"
    if not REPORT_ID_PATTERN.fullmatch(report_id):
        return None, "report_id contains unsupported characters"
    candidate = result_path.parent / f"report-{report_id}.json"
    if not candidate.is_file():
        return None, "passed smoke result is missing its sibling RCA report"
    return candidate, None


def ensure_empty_output(output_dir: Path) -> None:
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("output directory must be empty")
    output_dir.mkdir(parents=True, exist_ok=True)


def copy_sample(
    result_path: Path,
    result: dict[str, Any],
    output_dir: Path,
) -> tuple[dict[str, Any], list[str]]:
    errors: list[str] = []
    status = str(result.get("status") or "unknown")
    started_at = result.get("started_at")
    if status == "passed" and parse_timestamp(started_at) is None:
        errors.append("passed smoke result has no valid started_at timestamp")

    report_path: Path | None = None
    report_error: str | None = None
    if status == "passed":
        report_path, report_error = sibling_report(result_path, result)
        if report_error:
            errors.append(report_error)

    result_content = result_path.read_bytes()
    result_hash = sha256_bytes(result_content)
    sample_id = result_hash[:24]
    sample_dir = output_dir / "samples" / sample_id
    sample_dir.mkdir(parents=True, exist_ok=True)
    bundled_result = sample_dir / RESULT_NAME
    if bundled_result.exists() and bundled_result.read_bytes() != result_content:
        raise ValueError(f"sample hash collision: {sample_id}")
    if not bundled_result.exists():
        shutil.copyfile(result_path, bundled_result)

    bundled_report: Path | None = None
    report_hash: str | None = None
    if report_path is not None:
        report_content = report_path.read_bytes()
        report_hash = sha256_bytes(report_content)
        bundled_report = sample_dir / report_path.name
        if bundled_report.exists() and bundled_report.read_bytes() != report_content:
            raise ValueError(f"report content conflict: {sample_id}")
        if not bundled_report.exists():
            shutil.copyfile(report_path, bundled_report)

    llm = result.get("llm") if isinstance(result.get("llm"), dict) else {}
    manifest_entry = {
        "sample_id": sample_id,
        "status": status,
        "started_at": started_at,
        "scenario": str(result.get("scenario") or "unknown"),
        "provider": str(llm.get("provider") or "unknown"),
        "model": str(llm.get("model") or "unknown"),
        "result": bundled_result.relative_to(output_dir).as_posix(),
        "result_sha256": result_hash,
        "report": bundled_report.relative_to(output_dir).as_posix() if bundled_report else None,
        "report_sha256": report_hash,
        "validation_errors": errors,
    }
    return manifest_entry, errors


def build_history(result_paths: list[Path], output_dir: Path) -> dict[str, Any]:
    entries_by_id: dict[str, dict[str, Any]] = {}
    validation_errors: list[str] = []

    for result_path in result_paths:
        try:
            result = load_json(result_path)
            entry, errors = copy_sample(result_path, result, output_dir)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            validation_errors.append(f"invalid smoke result: {result_path.name}: {exc}")
            continue
        sample_id = entry["sample_id"]
        entries_by_id[sample_id] = entry
        validation_errors.extend(f"sample {sample_id}: {error}" for error in errors)

    entries = sorted(
        entries_by_id.values(),
        key=lambda item: (str(item.get("started_at") or ""), item["sample_id"]),
    )
    passed_count = sum(
        1
        for entry in entries
        if entry["status"] == "passed" and not entry["validation_errors"]
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": "failed" if validation_errors else "passed",
        "sample_count": len(entries),
        "passed_sample_count": passed_count,
        "failed_sample_count": len(entries) - passed_count,
        "validation_errors": validation_errors,
        "samples": entries,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    missing = [raw for raw in args.inputs if not Path(raw).exists()]
    if missing:
        print("history input does not exist: " + ", ".join(missing), file=sys.stderr)
        return 2

    result_paths = discover_results(args.inputs)
    if not result_paths and not args.allow_empty:
        print(f"history inputs contain no {RESULT_NAME} files", file=sys.stderr)
        return 2

    output_dir = Path(args.output_dir)
    try:
        ensure_empty_output(output_dir)
        manifest = build_history(result_paths, output_dir)
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2

    write_json(output_dir / "history-manifest.json", manifest)
    print(json.dumps(manifest, indent=2, ensure_ascii=False))
    return 1 if manifest["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
