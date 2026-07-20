#!/usr/bin/env python3
"""Render and unit-test the Helm-managed LLM Prometheus rules."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "charts" / "cluster-infra-rca-platform"
TEMPLATE = "templates/platform-prometheusrule.yaml"
TEST_FILE = ROOT / "tests" / "prometheus" / "llm-rules.test.yml"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render the LLM PrometheusRule and run promtool checks."
    )
    parser.add_argument("--helm", default="helm")
    parser.add_argument("--promtool", default="promtool")
    return parser.parse_args()


def command_path(value: str) -> str:
    candidate = Path(value)
    if candidate.exists():
        return str(candidate.resolve())
    resolved = shutil.which(value)
    if resolved:
        return resolved
    raise FileNotFoundError(f"required command not found: {value}")


def extract_rule_groups(rendered: str) -> str:
    lines = rendered.splitlines()
    start = next(
        (index for index, line in enumerate(lines) if line.rstrip() == "  groups:"),
        None,
    )
    if start is None:
        raise ValueError("rendered PrometheusRule does not contain spec.groups")
    groups = []
    for line in lines[start:]:
        if line == "---":
            break
        groups.append(line[2:] if line.startswith("  ") else line)
    content = "\n".join(groups).rstrip() + "\n"
    required = (
        "groups:",
        "ClusterRcaLlmHighLatency",
        "ClusterRcaLlmHighErrorRate",
        "ClusterRcaLlmUsageMetadataMissing",
        "ClusterRcaLlmCircuitBreakerOpen",
        "ClusterRcaLlmEstimatedCostBudgetExceeded",
    )
    missing = [marker for marker in required if marker not in content]
    if missing:
        raise ValueError(f"rendered Prometheus rules are incomplete: {', '.join(missing)}")
    return content


def render_rules(helm: str, extra_args: list[str] | None = None) -> str:
    command = [
        helm,
        "template",
        "rca",
        str(CHART),
        "--show-only",
        TEMPLATE,
        "--set",
        "platform.prometheusRule.enabled=true",
        "--set",
        "platform.prometheusRule.costBudget.enabled=true",
    ]
    command.extend(extra_args or [])
    result = subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return extract_rule_groups(result.stdout)


def run() -> None:
    args = parse_args()
    helm = command_path(args.helm)
    promtool = command_path(args.promtool)
    rules = render_rules(helm)
    with tempfile.TemporaryDirectory(prefix="rca-llm-prometheus-") as directory:
        workspace = Path(directory)
        rule_file = workspace / "llm-rules.yml"
        test_file = workspace / TEST_FILE.name
        rule_file.write_text(rules, encoding="utf-8", newline="\n")
        shutil.copyfile(TEST_FILE, test_file)
        subprocess.run([promtool, "check", "rules", str(rule_file)], check=True)
        subprocess.run([promtool, "test", "rules", str(test_file)], check=True)


if __name__ == "__main__":
    run()
