#!/usr/bin/env python3
"""Validate Helm Agent manifests against the Web/Helm deployment contract."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path


def render(helm: str, repository: Path, mode: str) -> str:
    command = [
        helm,
        "template",
        "rca-agent",
        str(repository / "charts" / "cluster-infra-rca-agent"),
        "--set-string",
        "fullnameOverride=cluster-infra-rca-agent",
        "--set-string",
        "backendUrl=https://rca.example.com",
        "--set",
        "secret.create=true",
        "--set-string",
        "secret.clusterId=cluster-parity",
        "--set-string",
        "secret.agentToken=not-a-real-token",
        "--set-string",
        "clusterId=cluster-parity",
        "--set-string",
        "enrollment.mode=kubernetes-token-review",
        "--set-string",
        "enrollment.audience=cluster-infra-rca-agent-enrollment",
        "--set-string",
        f"mode={mode}",
    ]
    if mode == "ebpf":
        command.extend(["--set", "ebpf.enabled=true"])
    return subprocess.run(
        command,
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--helm", default="helm")
    parser.add_argument("--repo", default=Path(__file__).resolve().parents[1], type=Path)
    arguments = parser.parse_args()
    repository = arguments.repo.resolve()
    contract = json.loads(
        (repository / "config" / "agent-manifest-contract.json").read_text(encoding="utf-8")
    )
    rendered = {
        mode: render(arguments.helm, repository, mode)
        for mode in ("safe", "node-diagnostics", "ebpf")
    }
    failures: list[str] = []

    for mode, manifest in rendered.items():
        require(
            f"name: {contract['daemonSetName']}" in manifest,
            f"{mode}: canonical DaemonSet name is missing",
            failures,
        )
        require(
            f"name: {contract['containerName']}" in manifest,
            f"{mode}: canonical Agent container is missing",
            failures,
        )
        for label in contract["identityLabels"]:
            require(
                manifest.count(label + ":") >= 2,
                f"{mode}: identity label {label} is not bound to selector and Pod",
                failures,
            )
        for resource in contract["agentRbac"]["requiredResources"]:
            require(
                f'resources: ["{resource}"]' in manifest,
                f"{mode}: required Agent RBAC resource {resource} is missing",
                failures,
            )
        for resource in contract["agentRbac"]["forbiddenResources"]:
            require(
                resource not in manifest,
                f"{mode}: forbidden Agent RBAC resource {resource} is present",
                failures,
            )
        require(
            re.search(r'drop:\s*(?:-\s*ALL|\[\s*["\']?ALL["\']?\s*\])', manifest) is not None,
            f"{mode}: capabilities.drop=ALL is missing",
            failures,
        )
        for capability in contract["capabilities"][mode]:
            require(
                re.search(rf"-\s*{re.escape(capability)}(?:\s|$)", manifest) is not None,
                f"{mode}: required capability {capability} is missing",
                failures,
            )

    result = {
        "contractVersion": contract["version"],
        "modes": list(rendered),
        "outcome": "pass" if not failures else "fail",
        "failures": failures,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
