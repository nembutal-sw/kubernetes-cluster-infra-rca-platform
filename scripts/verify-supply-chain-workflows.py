#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def contains_all(text: str, values: list[str]) -> bool:
    return all(value in text for value in values)


def main() -> int:
    errors: list[str] = []
    security_path = ROOT / ".github/workflows/security.yml"
    publish_path = ROOT / ".github/workflows/publish-images.yml"
    release_path = ROOT / ".github/workflows/release.yml"
    dependabot_path = ROOT / ".github/dependabot.yml"

    require(security_path.exists(), "security workflow is missing", errors)
    require(publish_path.exists(), "development image publish workflow is missing", errors)
    require(release_path.exists(), "release workflow is missing", errors)
    require(dependabot_path.exists(), "dependabot config is missing", errors)
    if errors:
        return fail(errors)

    security = read(".github/workflows/security.yml")
    publish = read(".github/workflows/publish-images.yml")
    release = read(".github/workflows/release.yml")
    dependabot = read(".github/dependabot.yml")

    require("workflow_dispatch:" in security, "security workflow must support manual dispatch", errors)
    require("security-events: write" in security, "security workflow must be able to upload SARIF", errors)
    require("contents: read" in security, "security workflow should use read-only repository contents permission", errors)

    required_security_jobs = [
        "dependency-review:",
        "secret-scan:",
        "filesystem-scan:",
        "sbom-and-grype:",
        "image-sbom-scan:",
        "codeql:",
    ]
    require(
        contains_all(security, required_security_jobs),
        "security workflow is missing one or more required jobs",
        errors,
    )

    required_security_actions = [
        "actions/dependency-review-action",
        "gitleaks/gitleaks-action",
        "aquasecurity/trivy-action",
        "anchore/sbom-action",
        "anchore/scan-action",
        "github/codeql-action/upload-sarif",
        "docker/build-push-action",
        "actions/upload-artifact",
    ]
    require(
        contains_all(security, required_security_actions),
        "security workflow is missing one or more required scan/upload actions",
        errors,
    )
    require("scan-type: fs" in security, "security workflow must include Trivy filesystem scanning", errors)
    require("scan-type: image" in security, "security workflow must include Trivy image scanning", errors)
    require(
        "SECURITY_REPORT_SEVERITIES" in security and "SECURITY_BLOCKING_SEVERITIES" in security,
        "security workflow must separate report severities from blocking severities",
        errors,
    )
    require(
        "Gate filesystem vulnerabilities" in security and "Gate ${{ matrix.component }} image vulnerabilities" in security,
        "security workflow must gate vulnerabilities separately from SARIF generation",
        errors,
    )
    require("fail-build: true" in security, "Grype repository scan must fail the build on blocking findings", errors)
    require(
        "Scan repository with Grype" in security and "path: ." in security,
        "Grype must scan the repository path so SARIF findings retain source locations",
        errors,
    )
    require(
        "github/codeql-action/upload-sarif@v4" in security
        and "github/codeql-action/init@v4" in security
        and "github/codeql-action/analyze@v4" in security
        and "github/codeql-action/upload-sarif@v3" not in security
        and "github/codeql-action/init@v3" not in security
        and "github/codeql-action/analyze@v3" not in security,
        "security workflow must use CodeQL Action v4",
        errors,
    )
    require("retention-days:" in security, "security artifacts must declare retention", errors)

    require("workflow_dispatch:" in publish, "development image workflow must support manual dispatch", errors)
    require("workflow_run:" in publish, "development image workflow must run after CI", errors)
    require("- CI" in publish, "development image workflow must follow the CI workflow", errors)
    require(
        "github.event.workflow_run.conclusion == 'success'" in publish,
        "development images must only publish after successful CI",
        errors,
    )
    require("packages: write" in publish, "development image workflow must be able to push images", errors)
    require("contents: read" in publish, "development image workflow should use read-only contents permission", errors)
    require(
        contains_all(
            publish,
            [
                "Dockerfile.web-console",
                "Dockerfile.agent",
                "cluster-infra-rca-platform",
                "cluster-infra-rca-agent",
            ],
        ),
        "development image workflow must publish both platform and agent images",
        errors,
    )
    require("docker/login-action" in publish, "development image workflow must log in to GHCR", errors)
    require("docker/build-push-action" in publish, "development image workflow must build and push images", errors)
    require("password: ${{ secrets.GITHUB_TOKEN }}" in publish, "development image workflow must use GITHUB_TOKEN", errors)
    require("type=raw,value=edge" in publish, "development image workflow must publish an edge tag", errors)
    require(
        "type=raw,value=sha-${{ steps.source.outputs.short_sha }}" in publish,
        "development image workflow must publish immutable commit tags",
        errors,
    )
    require(
        "ref: ${{ steps.source.outputs.sha }}" in publish,
        "development image workflow must build the exact tested revision",
        errors,
    )

    require("id-token: write" in release, "release workflow must allow keyless signing OIDC", errors)
    require("packages: write" in release, "release workflow must be able to push images", errors)
    require("cosign sign" in release, "release workflow must sign pushed images", errors)
    require("anchore/sbom-action" in release, "release workflow must generate image SBOMs", errors)
    require("Create released image Trivy SARIF" in release, "release workflow must create released image SARIF", errors)
    require("Gate released image vulnerabilities" in release, "release workflow must gate released image vulnerabilities", errors)
    require("gh release upload" in release, "release workflow must publish security artifacts", errors)

    required_ecosystems = ["maven", "pip", "npm", "docker", "github-actions"]
    for ecosystem in required_ecosystems:
        require(
            f"package-ecosystem: {ecosystem}" in dependabot,
            f"dependabot must cover {ecosystem}",
            errors,
        )

    return fail(errors) if errors else pass_ok()


def pass_ok() -> int:
    print("supply-chain workflow verification passed")
    return 0


def fail(errors: list[str]) -> int:
    print("supply-chain workflow verification failed:", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
