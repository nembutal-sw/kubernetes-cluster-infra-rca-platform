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
            "database-compatibility-ci",
            exists("scripts/verify_database_compatibility_report.py")
            and contains(
                ".github/workflows/ci.yml",
                "Require PostgreSQL and MariaDB compatibility tests",
                "python3 scripts/verify_database_compatibility_report.py",
            )
            and contains(
                "web-console/src/test/java/io/clusterinfra/rca/webconsole/DatabaseCompatibilityTests.java",
                "postgresqlSupportsFreshSchemaAndRepositoryWorkflow",
                "mariadbSupportsFreshSchemaAndRepositoryWorkflow",
                "postgresqlBaselinesExistingAlembicSchemaWithoutLosingData",
                "mariadbBaselinesExistingAlembicSchemaWithoutLosingData",
            ),
            "CI fails when PostgreSQL or MariaDB compatibility tests are missing or skipped.",
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
            "real-cluster-agent-lifecycle",
            exists("scripts/real-cluster-agent-e2e.sh")
            and contains(
                "scripts/real-cluster-agent-e2e.sh",
                "--apply",
                "namespace_owned_by_run",
                "statePersistence.enabled=false",
                "developmentSourceBundle.enabled=true",
                "/api/evidence/requests",
                "verify_evidence_bundle.py",
            )
            and contains(
                "charts/cluster-infra-rca-agent/values.yaml",
                "developmentSourceBundle:",
                "enabled: false",
                "statePersistence:",
                "enabled: true",
            ),
            "Real-cluster Agent E2E is apply-gated, ownership-scoped, registry-independent, and bundle-verified.",
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
                "validate_llm_connectivity",
                "validate_llm_report",
                "max_llm_latency_ms",
                "provider_call_budget",
                "automation_allowed",
            )
            and contains("docs/llm-analyzer.md", "LLM Staging Smoke", "llm-staging-smoke.py"),
            "LLM staging smoke validates provider configuration, report output, and automation guardrails.",
        ),
        check(
            "llm-burn-in-report",
            exists("scripts/llm-burn-in-report.py")
            and contains(
                "scripts/llm-burn-in-report.py",
                "llm-burn-in/v2",
                "minimum-samples",
                "minimum-scenarios",
                "minimum-time-buckets",
                "scenario_statistics",
                "retain_current_threshold",
                "unsafe_llm_action_count",
            )
            and contains("docs/llm-analyzer.md", "LLM Burn-in Aggregation", "llm-burn-in-report.py"),
            "LLM burn-in aggregation gates SLO changes on sample coverage and action safety.",
        ),
        check(
            "llm-burn-in-campaign",
            exists("scripts/llm-burn-in-campaign.py")
            and contains(
                "scripts/llm-burn-in-campaign.py",
                "MAX_PROVIDER_CALL_BUDGET",
                "provider-call-budget",
                "skip-connectivity-test",
                "validate_history_inputs",
                "build_plan",
                "target-time-buckets",
                "waiting_for_time_bucket",
            )
            and contains("docs/llm-analyzer.md", "Quota-Aware Campaign", "llm-burn-in-campaign.py"),
            "LLM burn-in campaigns are quota-bounded, history-aware, and fail-fast.",
        ),
        check(
            "llm-burn-in-workflow",
            exists("scripts/llm-burn-in-history.py")
            and exists(".github/workflows/llm-burn-in.yml")
            and contains(
                "scripts/llm-burn-in-history.py",
                "llm-burn-in-history/v1",
                "result_sha256",
                "validation_errors",
                "allow-empty",
            )
            and contains(
                ".github/workflows/llm-burn-in.yml",
                "workflow_dispatch",
                "confirm_live_calls",
                "change_reference",
                "history_run_id",
                "environment: llm-burn-in",
                "llm-burn-in-results",
            )
            and contains("docs/llm-analyzer.md", "Manual Burn-in Workflow", "history_run_id"),
            "Manual LLM burn-in is approval-gated, quota-bounded, and backed by portable cumulative history.",
        ),
        check(
            "llm-slo-prometheus-rule",
            exists("charts/cluster-infra-rca-platform/templates/platform-prometheusrule.yaml")
            and contains(
                "charts/cluster-infra-rca-platform/templates/platform-prometheusrule.yaml",
                "kind: PrometheusRule",
                "ClusterRcaLlmHighLatency",
                "ClusterRcaLlmHighErrorRate",
                "ClusterRcaLlmUsageMetadataMissing",
                "ClusterRcaLlmCircuitBreakerOpen",
                "ClusterRcaLlmEstimatedCostBudgetExceeded",
            )
            and contains(
                "charts/cluster-infra-rca-platform/values.yaml",
                "prometheusRule:",
                "evaluationWindow:",
                "costBudget:",
            ),
            "Platform chart exposes opt-in LLM SLO recording and alert rules with configurable thresholds.",
        ),
        check(
            "llm-slo-promtool-tests",
            exists("scripts/llm_prometheus_rule_test.py")
            and exists("tests/prometheus/llm-rules.test.yml")
            and contains(
                ".github/workflows/ci.yml",
                "PROMETHEUS_VERSION",
                "PROMETHEUS_LINUX_AMD64_SHA256",
                "llm_prometheus_rule_test.py",
            ),
            "Rendered LLM Prometheus rules are evaluated against healthy and firing promtool scenarios.",
        ),
        check(
            "alertmanager-webhook-delivery",
            exists("charts/cluster-infra-rca-platform/templates/platform-alertmanagerconfig.yaml")
            and exists("scripts/alertmanager_delivery_test.py")
            and exists("tests/test_alertmanager_delivery_test.py")
            and contains(
                "charts/cluster-infra-rca-platform/templates/platform-alertmanagerconfig.yaml",
                "kind: AlertmanagerConfig",
                "/api/webhooks/alertmanager",
                "platform.alertmanagerConfig.clusterId is required",
                "authorization:",
                "tokenSecretKey",
                "sendResolved:",
            )
            and contains(
                "charts/cluster-infra-rca-platform/values.yaml",
                "alertmanagerConfig:",
                "tokenSecretKey: RCA_WEBHOOK_TOKEN",
            )
            and contains(
                ".github/workflows/ci.yml",
                "ALERTMANAGER_VERSION",
                "ALERTMANAGER_LINUX_AMD64_SHA256",
                "alertmanager_delivery_test.py",
            ),
            "Prometheus and Alertmanager deliver firing and resolved alerts to the authenticated platform webhook.",
        ),
        check(
            "prometheus-operator-delivery-e2e",
            exists("scripts/prometheus-operator-delivery-e2e.sh")
            and exists("scripts/operator_webhook_sink.py")
            and exists("tests/test_prometheus_operator_delivery_e2e.py")
            and contains(
                "scripts/prometheus-operator-delivery-e2e.sh",
                "--confirm-context is required with --apply",
                "namespace_owned_by_run",
                "prometheusrules.monitoring.coreos.com",
                "alertmanagerconfigs.monitoring.coreos.com",
                "wait_for_status firing",
                "wait_for_status resolved",
            )
            and contains(
                ".github/workflows/ci.yml",
                "prometheus-operator-delivery-e2e:",
                "KIND_LINUX_AMD64_SHA256",
                "KIND_NODE_IMAGE",
                "KUBECTL_LINUX_AMD64_SHA256",
                "KUBE_PROMETHEUS_STACK_VERSION",
                "prometheus-operator-delivery-e2e.sh",
            ),
            "A Kind canary validates Prometheus Operator selection, reconciliation, and authenticated notification delivery.",
        ),
        check(
            "llm-helm-contract",
            contains(
                "charts/cluster-infra-rca-platform/templates/platform-deployment.yaml",
                "RCA_LLM_ENABLED",
                "RCA_LLM_PROVIDER",
                "RCA_SPRING_AI_CHAT_MODEL",
                "RCA_LLM_TIMEOUT_SECONDS",
                "RCA_SPRING_AI_RETRY_MAX_ATTEMPTS",
                "RCA_LLM_INPUT_COST_PER_MILLION_TOKENS",
                "RCA_LLM_OUTPUT_COST_PER_MILLION_TOKENS",
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
                "springAiRetryMaxAttempts",
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
                "RCA_SPRING_AI_RETRY_MAX_ATTEMPTS",
                "RCA_LLM_INPUT_COST_PER_MILLION_TOKENS",
                "SPRING_AI_OPENAI_SDK_API_KEY",
                "SPRING_AI_OPENAI_SDK_BASE_URL",
                "SPRING_AI_ANTHROPIC_API_KEY",
                "SPRING_AI_GOOGLE_GENAI_API_KEY",
                "SPRING_AI_OLLAMA_BASE_URL",
            )
            and contains(
                ".env.example",
                "OPENAI_API_KEY",
                "RCA_SPRING_AI_RETRY_MAX_ATTEMPTS",
                "RCA_LLM_INPUT_COST_PER_MILLION_TOKENS",
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
                "RCA_LLM_SMOKE_REQUIRE_USAGE_METADATA",
                "RCA_LLM_SMOKE_MAX_LATENCY_MS",
                "RCA_LLM_SMOKE_MAX_ESTIMATED_COST_USD",
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
                "webhook_endpoint_not_covered_by_webhook_filter",
                "manifest_endpoint_not_covered_by_manifest_filter",
                "required_versioned_api_missing",
            ),
            "Static API contract guard validates route authorization and custom filter coverage.",
        ),
        check(
            "ci-direct-static-guards",
            contains(
                ".github/workflows/ci.yml",
                "python scripts/verify-api-contract.py",
                "python scripts/verify-container-pinning.py",
                "python scripts/verify-operational-catalog.py",
                "python scripts/verify-supply-chain-workflows.py",
            ),
            "CI runs API, container, catalog, and supply-chain static guards as explicit steps.",
        ),
        check(
            "auth-boundary-regression-tests",
            exists("web-console/src/test/java/io/clusterinfra/rca/webconsole/SecurityBoundaryRegressionTests.java")
            and contains(
                "web-console/src/test/java/io/clusterinfra/rca/webconsole/SecurityBoundaryRegressionTests.java",
                "webhookRequiresConfiguredTokenAndRecordsAuthFailuresWithRequestContext",
                "everyAgentEndpointRequiresAgentCredentialsBeforeControllerLogic",
                "agentRegisterAndHeartbeatRejectTamperedTokensButAcceptValidIdentity",
                "manifestAccessRejectsAgentTokenAndAllowsOnlyUserOrOneTimeManifestToken",
                "query_values_redacted",
            ),
            "Agent, webhook, and manifest authentication boundaries are covered by integration regression tests.",
        ),
        check(
            "operational-catalog-static-guard",
            exists("scripts/verify-operational-catalog.py")
            and contains(
                "scripts/verify-operational-catalog.py",
                "REQUIRED_COLLECTORS",
                "REQUIRED_ALERT_SELECTIONS",
                "REQUIRED_ACTIONS",
                "REQUIRED_RULES",
                "plan.executable must be false",
            )
            and contains(
                "web-console/src/main/resources/catalog/operational-catalog.json",
                "\"schema_version\": \"rca-catalog/v1\"",
                "\"collectors\"",
                "\"collector_selection\"",
                "\"actions\"",
                "\"rules\"",
            ),
            "Static operational catalog guard validates collector selection, actions, rules, and non-executable plans.",
        ),
        check(
            "typed-evidence-quality-gate",
            exists("web-console/src/main/resources/evidence/collector-evidence-schemas.json")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/analysis/CollectorEvidenceAdapter.java")
            and exists("web-console/src/test/java/io/clusterinfra/rca/webconsole/RuleAnalysisQualityTests.java")
            and contains(
                ".github/workflows/ci.yml",
                "rule-analysis-quality",
                "analysis-quality-report.json",
            ),
            "Collector evidence is normalized through a versioned typed contract and golden scenarios enforce quality metrics.",
        ),
        check(
            "gitops-change-tracking",
            exists("web-console/src/main/resources/db/migration/V18__gitops_change_tracking.sql")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/gitops/GitHubGitOpsProvider.java")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GitHubWebhookService.java")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/gitops/GitLabGitOpsProvider.java")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GitLabWebhookService.java")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/gitops/GiteaGitOpsProvider.java")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GiteaWebhookService.java")
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GitOpsChangeService.java",
                "CatalogOverrideStatus.approved",
                "createPending",
                "deduplicated",
                "deployment outcome can only be recorded after PR merge",
            )
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GitHubWebhookService.java",
                "HmacSHA256",
                "MessageDigest.isEqual",
                "claimWebhookDelivery",
            )
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GitLabWebhookService.java",
                "MessageDigest.isEqual",
                "claimWebhookDelivery",
                "Merge Request Hook",
            )
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/service/GiteaWebhookService.java",
                "HmacSHA256",
                "MessageDigest.isEqual",
                "claimWebhookDelivery",
            )
            and contains(
                "charts/cluster-infra-rca-platform/templates/platform-secret.yaml",
                "RCA_GITOPS_TOKEN",
                "RCA_GITOPS_WEBHOOK_SECRET",
            ),
            "GitHub/GitLab/Gitea GitOps integration requires approval, deduplicates PR creation, verifies provider webhooks, and tracks deployment outcomes.",
        ),
        check(
            "operations-cursor-pagination",
            exists("web-console/src/main/resources/db/migration/V19__cursor_pagination_indexes.sql")
            and exists("web-console/src/main/java/io/clusterinfra/rca/webconsole/persistence/CursorPageSupport.java")
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/persistence/ReportRepository.java",
                "pageReports",
                "created_at DESC, report_id DESC",
            )
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/persistence/IncidentRepository.java",
                "last_seen_at DESC, incident_id DESC",
            )
            and contains(
                "web-console/src/main/java/io/clusterinfra/rca/webconsole/persistence/AnalysisTaskRepository.java",
                "created_at DESC, task_id DESC",
            )
            and exists("web-console/frontend/src/hooks/useCursorPage.ts"),
            "Report, incident, and task lists use indexed keyset cursors with versioned APIs and shared UI navigation.",
        ),
        check(
            "supply-chain-ci",
            contains(
                ".github/workflows/security.yml",
                "workflow_dispatch:",
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
            "supply-chain-static-guard",
            exists("scripts/verify-supply-chain-workflows.py")
            and contains(
                "scripts/verify-supply-chain-workflows.py",
                "workflow_dispatch:",
                "dependency-review:",
                "secret-scan:",
                "filesystem-scan:",
                "sbom-and-grype:",
                "image-sbom-scan:",
                "codeql:",
            ),
            "Static supply-chain guard validates the required security workflow shape.",
        ),
        check(
            "release-supply-chain-assets",
            contains(
                ".github/workflows/release.yml",
                "cosign sign",
                "anchore/sbom-action",
                "Create released image Trivy SARIF",
                "Gate released image vulnerabilities",
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
