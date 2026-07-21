#!/usr/bin/env python3
"""Validate metadata for a GitHub Actions LLM burn-in history run."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
WORKFLOW_PATH = ".github/workflows/llm-burn-in.yml"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate an LLM Burn-in Actions run.")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--github-env", required=True)
    parser.add_argument("--api-url", default=os.getenv("GITHUB_API_URL", "https://api.github.com"))
    return parser.parse_args()


def fetch_run(api_url: str, repository: str, run_id: int, token: str) -> dict[str, Any]:
    url = f"{api_url.rstrip('/')}/repos/{repository}/actions/runs/{run_id}"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "cluster-infra-rca-llm-burn-in",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        raise ValueError(f"GitHub run metadata request failed with HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise ValueError(f"GitHub run metadata request failed: {exc.reason}") from exc
    if not isinstance(payload, dict):
        raise ValueError("GitHub run metadata response must be an object")
    return payload


def validate_run(payload: dict[str, Any]) -> str:
    if payload.get("path") != WORKFLOW_PATH:
        raise ValueError("history_run_id must reference the LLM Burn-in workflow")
    if payload.get("event") != "workflow_dispatch":
        raise ValueError("history_run_id must reference a manual LLM Burn-in run")
    if payload.get("status") != "completed":
        raise ValueError("history_run_id must reference a completed LLM Burn-in run")
    conclusion = str(payload.get("conclusion") or "")
    if conclusion not in {"success", "failure"}:
        raise ValueError("history_run_id must reference a successful or failed LLM Burn-in run")
    return conclusion


def main() -> int:
    args = parse_args()
    token = os.getenv("GH_TOKEN", "")
    if not token:
        print("GH_TOKEN is required", file=sys.stderr)
        return 2
    if args.run_id <= 0:
        print("run-id must be positive", file=sys.stderr)
        return 2
    if not REPOSITORY_PATTERN.fullmatch(args.repository):
        print("repository must use owner/name format", file=sys.stderr)
        return 2

    try:
        conclusion = validate_run(fetch_run(args.api_url, args.repository, args.run_id, token))
        github_env = Path(args.github_env)
        with github_env.open("a", encoding="utf-8") as stream:
            stream.write(f"BURN_IN_HISTORY_RUN_CONCLUSION={conclusion}\n")
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps({"status": "passed", "run_id": args.run_id, "conclusion": conclusion}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
