#!/usr/bin/env python3
"""Static release gate for operationally important platform contracts."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def exists(path: str) -> bool:
    return (ROOT / path).exists()


def contains(path: str, *needles: str) -> bool:
    text = read(path)
    return all(needle in text for needle in needles)


def check(name: str, ok: bool, detail: str) -> dict[str, object]:
    return {"name": name, "ok": ok, "detail": detail}


def main() -> int:
    checks = [
        check(
            "platform-chart",
            exists("charts/cluster-infra-rca-platform/Chart.yaml")
            and exists("charts/cluster-infra-rca-platform/templates/platform-deployment.yaml")
            and exists("charts/cluster-infra-rca-platform/templates/platform-secret.yaml"),
            "Platform Helm chart includes deployment and secret templates.",
        ),
        check(
            "agent-chart",
            exists("charts/cluster-infra-rca-agent/Chart.yaml")
            and exists("charts/cluster-infra-rca-agent/templates/daemonset.yaml")
            and exists("charts/cluster-infra-rca-agent/templates/secret.yaml"),
            "Agent Helm chart includes DaemonSet and token Secret templates.",
        ),
        check(
            "agent-manifest-secret",
            contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/AgentManifestService.java",
                "kind\", \"Secret",
                "agent-token",
                "stringData",
            ),
            "/api/agent-manifest renders a cluster token Secret for direct kubectl apply flows.",
        ),
        check(
            "readiness-health",
            contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/controller/HealthController.java",
                "/ready",
                "bootstrap",
                "database",
            ),
            "/health/ready exposes bootstrap and database readiness signals.",
        ),
        check(
            "backend-monitoring",
            contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/ScheduledCollectionService.java",
                "AgentCollectorDegraded",
                "ScheduledNodeHealth",
                "health_status",
            ),
            "Backend scheduled monitoring preserves agent health context in evidence requests.",
        ),
        check(
            "kind-smoke-evidence",
            contains(
                "scripts/kind-smoke.sh",
                "agent_count",
                "/api/evidence/requests",
                "report_count",
            ),
            "Kubernetes smoke test waits for agents, evidence collection, and RCA report generation.",
        ),
        check(
            "operational-daemonset-check",
            exists("scripts/daemonset_operational_check.py")
            and contains("scripts/daemonset_operational_check.py", "hostPath", "readOnly"),
            "DaemonSet operational manifest check validates host mounts and read-only posture.",
        ),
        check(
            "container-build-inputs",
            exists("Dockerfile.web-console")
            and exists("Dockerfile.agent")
            and exists("docker-compose.yml"),
            "Platform and agent container build inputs exist.",
        ),
        check(
            "release-readiness-doc",
            exists("docs/release-readiness.md")
            and contains("docs/release-readiness.md", "Release Readiness", "Kubernetes"),
            "Release readiness documentation is present.",
        ),
    ]

    failed = [item for item in checks if not item["ok"]]
    print(json.dumps({"status": "failed" if failed else "passed", "checks": checks}, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
