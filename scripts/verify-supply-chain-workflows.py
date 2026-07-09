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
    release_path = ROOT / ".github/workflows/release.yml"
    dependabot_path = ROOT / ".github/dependabot.yml"

    require(security_path.exists(), "security workflow is missing", errors)
    require(release_path.exists(), "release workflow is missing", errors)
    require(dependabot_path.exists(), "dependabot config is missing", errors)
    if errors:
        return fail(errors)

    security = read(".github/workflows/security.yml")
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
    require("fail-build: true" in security, "Grype SBOM scan must fail the build on blocking findings", errors)
    require("retention-days:" in security, "security artifacts must declare retention", errors)

    require("id-token: write" in release, "release workflow must allow keyless signing OIDC", errors)
    require("packages: write" in release, "release workflow must be able to push images", errors)
    require("cosign sign" in release, "release workflow must sign pushed images", errors)
    require("anchore/sbom-action" in release, "release workflow must generate image SBOMs", errors)
    require("Scan released image" in release, "release workflow must scan released images", errors)
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
