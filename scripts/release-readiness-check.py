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
            "llm-staging-smoke",
            exists("scripts/llm-staging-smoke.py")
            and contains(
                "scripts/llm-staging-smoke.py",
                "validate_llm_configuration",
                "validate_llm_report",
                "automation_allowed",
            )
            and contains("docs/llm-analyzer.md", "LLM Staging Smoke", "llm-staging-smoke.py"),
            "LLM staging smoke validates provider configuration, report output, and automation guardrails.",
        ),
        check(
            "llm-helm-contract",
            contains(
                "charts/cluster-infra-rca-platform/templates/platform-deployment.yaml",
                "RCA_LLM_ENABLED",
                "RCA_LLM_PROVIDER",
                "RCA_SPRING_AI_CHAT_MODEL",
                "RCA_LLM_TIMEOUT_SECONDS",
            )
            and contains(
                "charts/cluster-infra-rca-platform/templates/platform-secret.yaml",
                "SPRING_AI_OPENAI_SDK_API_KEY",
                "SPRING_AI_OPENAI_SDK_BASE_URL",
                "SPRING_AI_ANTHROPIC_API_KEY",
                "SPRING_AI_GOOGLE_GENAI_API_KEY",
                "SPRING_AI_OLLAMA_BASE_URL",
            )
            and contains(
                "charts/cluster-infra-rca-platform/values.yaml",
                "llmEnabled",
                "llmProvider",
                "openaiBaseUrl",
                "ollamaBaseUrl",
            ),
            "Platform Helm chart exposes LLM configuration and provider secret wiring.",
        ),
        check(
            "llm-compose-contract",
            contains(
                "docker-compose.yml",
                "RCA_LLM_ENABLED",
                "SPRING_AI_OPENAI_SDK_API_KEY",
                "SPRING_AI_OPENAI_SDK_BASE_URL",
                "SPRING_AI_ANTHROPIC_API_KEY",
                "SPRING_AI_GOOGLE_GENAI_API_KEY",
                "SPRING_AI_OLLAMA_BASE_URL",
            )
            and contains(
                ".env.example",
                "OPENAI_API_KEY",
                "OPENAI_BASE_URL",
                "ANTHROPIC_API_KEY",
                "GEMINI_API_KEY",
                "OLLAMA_BASE_URL",
            ),
            "Docker Compose and .env.example expose LLM provider environment wiring.",
        ),
        check(
            "operational-smoke-llm-workflow",
            contains(
                ".github/workflows/operational-smoke.yml",
                "run_llm_smoke",
                "scripts/llm-staging-smoke.py",
                "validation-results/llm-staging-smoke",
            ),
            "Operational Smoke workflow can optionally run LLM staging validation.",
        ),
        check(
            "container-build-inputs",
            exists("Dockerfile.web-console")
            and exists("Dockerfile.agent")
            and exists("docker-compose.yml")
            and contains("Dockerfile.web-console", "@sha256:")
            and contains("Dockerfile.agent", "@sha256:")
            and exists("scripts/verify-container-pinning.py"),
            "Platform and agent container build inputs exist and base images are digest pinned.",
        ),
        check(
            "api-security-contract",
            exists("scripts/verify-api-contract.py")
            and contains(
                "scripts/verify-api-contract.py",
                "api_endpoint_missing_pre_authorize",
                "agent_endpoint_not_covered_by_agent_filter",
                "required_versioned_api_missing",
            ),
            "Static API contract guard validates route authorization and custom filter coverage.",
        ),
        check(
            "supply-chain-ci",
            contains(
                ".github/workflows/security.yml",
                "gitleaks/gitleaks-action",
                "aquasecurity/trivy-action",
                "anchore/sbom-action",
                "anchore/scan-action",
                "image-sbom-scan",
                "scan-type: image",
                "actions/upload-artifact",
            ),
            "CI includes Gitleaks, repository/image Trivy, Syft SBOM, Grype scanning, and retained scan artifacts.",
        ),
        check(
            "release-supply-chain-assets",
            contains(
                ".github/workflows/release.yml",
                "cosign sign",
                "anchore/sbom-action",
                "Scan released image",
                "actions/upload-artifact",
                "*-release-security",
                "gh release upload",
            ),
            "Release workflow signs images and publishes SBOM plus image scan reports as release assets.",
        ),
        check(
            "release-readiness-doc",
            exists("docs/release-readiness.md")
            and contains("docs/release-readiness.md", "Release Readiness", "Kubernetes"),
            "Release readiness documentation is present.",
        ),
        check(
            "api-security-contract-doc",
            exists("docs/api-security-contract.md")
            and contains(
                "docs/api-security-contract.md",
                "API Security Contract",
                "Custom Guarded Endpoints",
                "Sensitive Role Rules",
            ),
            "API security contract documentation is present.",
        ),
        check(
            "rbac-matrix",
            exists("docs/rbac-matrix.md")
            and contains(
                "docs/rbac-matrix.md",
                "RBAC Matrix",
                "Sensitive Operations",
                "APPROVER",
                "AUDITOR",
            )
            and contains(
                "web-console/src/test/java/io/clusterinfra/rca/webconsole/RbacHttpAuthorizationTests.java",
                "roleMatrixProtectsSensitiveOperationalApis",
                "/api/rca/reports/export",
                "/api/audit/events/export",
            ),
            "RBAC matrix is documented and covered by HTTP authorization tests.",
        ),
    ]

    failed = [item for item in checks if not item["ok"]]
    print(json.dumps({"status": "failed" if failed else "passed", "checks": checks}, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
