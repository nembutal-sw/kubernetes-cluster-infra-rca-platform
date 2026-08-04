#!/usr/bin/env python3
from __future__ import annotations

import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def as_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def load_yaml(root: Path, relative_path: str, errors: list[str]) -> Mapping[str, Any]:
    path = root / relative_path
    if not path.is_file():
        errors.append(f"{relative_path} is missing")
        return {}
    try:
        parsed = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    except (OSError, UnicodeError, yaml.YAMLError) as exception:
        errors.append(f"{relative_path} is not valid UTF-8 YAML: {exception}")
        return {}
    if not isinstance(parsed, Mapping):
        errors.append(f"{relative_path} must contain a YAML mapping")
        return {}
    return parsed


def jobs(workflow: Mapping[str, Any]) -> Mapping[str, Any]:
    return as_mapping(workflow.get("jobs"))


def steps(job: Any) -> list[Mapping[str, Any]]:
    return [step for step in as_list(as_mapping(job).get("steps")) if isinstance(step, Mapping)]


def action_steps(workflow: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    return [step for job in jobs(workflow).values() for step in steps(job) if "uses" in step]


def find_step_by_name(job: Any, name: str) -> Mapping[str, Any]:
    return next((step for step in steps(job) if step.get("name") == name), {})


def has_action(workflow: Mapping[str, Any], action: str) -> bool:
    return any(str(step.get("uses", "")).startswith(f"{action}@") for step in action_steps(workflow))


def validate_security(workflow: Mapping[str, Any], errors: list[str]) -> None:
    triggers = as_mapping(workflow.get("on"))
    permissions = as_mapping(workflow.get("permissions"))
    security_jobs = jobs(workflow)

    require("workflow_dispatch" in triggers, "security workflow must support manual dispatch", errors)
    require(permissions.get("contents") == "read", "security workflow must use read-only contents permission", errors)
    require(permissions.get("security-events") == "write", "security workflow must upload SARIF", errors)

    required_jobs = {
        "dependency-review",
        "secret-scan",
        "filesystem-scan",
        "sbom-and-grype",
        "image-sbom-scan",
        "codeql",
    }
    require(required_jobs <= set(security_jobs), "security workflow is missing required jobs", errors)

    required_actions = {
        "actions/dependency-review-action",
        "gitleaks/gitleaks-action",
        "aquasecurity/trivy-action",
        "anchore/sbom-action",
        "anchore/scan-action",
        "github/codeql-action/upload-sarif",
        "docker/build-push-action",
        "actions/upload-artifact",
    }
    missing_actions = sorted(action for action in required_actions if not has_action(workflow, action))
    require(not missing_actions, f"security workflow is missing actions: {', '.join(missing_actions)}", errors)

    trivy_scan_types = {
        as_mapping(step.get("with")).get("scan-type")
        for step in action_steps(workflow)
        if str(step.get("uses", "")).startswith("aquasecurity/trivy-action@")
    }
    require({"fs", "image"} <= trivy_scan_types, "security workflow must scan files and images with Trivy", errors)

    workflow_env = as_mapping(workflow.get("env"))
    require(
        {"SECURITY_REPORT_SEVERITIES", "SECURITY_BLOCKING_SEVERITIES"} <= set(workflow_env),
        "security workflow must separate report and blocking severities",
        errors,
    )

    step_names = {str(step.get("name", "")) for job in security_jobs.values() for step in steps(job)}
    require("Gate filesystem vulnerabilities" in step_names, "filesystem vulnerabilities must have a blocking gate", errors)
    require(
        "Gate ${{ matrix.component }} image vulnerabilities" in step_names,
        "image vulnerabilities must have a blocking gate",
        errors,
    )

    grype_step = next(
        (
            step
            for step in action_steps(workflow)
            if str(step.get("uses", "")).startswith("anchore/scan-action@")
        ),
        {},
    )
    grype_inputs = as_mapping(grype_step.get("with"))
    require(grype_inputs.get("path") == ".", "Grype must scan the repository path", errors)
    require(grype_inputs.get("fail-build") == "true", "Grype must fail on blocking findings", errors)

    codeql_uses = [
        str(step.get("uses", ""))
        for step in action_steps(workflow)
        if str(step.get("uses", "")).startswith("github/codeql-action/")
    ]
    require(codeql_uses and all(value.endswith("@v4") for value in codeql_uses), "all CodeQL actions must use v4", errors)

    upload_steps = [
        step
        for step in action_steps(workflow)
        if str(step.get("uses", "")).startswith("actions/upload-artifact@")
    ]
    require(
        upload_steps and all("retention-days" in as_mapping(step.get("with")) for step in upload_steps),
        "security artifacts must declare retention",
        errors,
    )


def validate_release(workflow: Mapping[str, Any], errors: list[str]) -> None:
    triggers = as_mapping(workflow.get("on"))
    push = as_mapping(triggers.get("push"))
    permissions = as_mapping(workflow.get("permissions"))
    release_jobs = jobs(workflow)
    image_job = as_mapping(release_jobs.get("image"))

    require("v*" in as_list(push.get("tags")), "release workflow must run for v* tags", errors)
    require(permissions.get("contents") == "write", "release workflow must create release assets", errors)
    require(permissions.get("packages") == "write", "release workflow must push images", errors)
    require(permissions.get("id-token") == "write", "release workflow must allow keyless signing", errors)

    matrix_entries = as_list(as_mapping(as_mapping(image_job.get("strategy")).get("matrix")).get("include"))
    components = {as_mapping(entry).get("component") for entry in matrix_entries}
    require(components == {"platform", "agent"}, "release workflow must build platform and agent images", errors)

    required_actions = {
        "docker/login-action",
        "docker/metadata-action",
        "docker/build-push-action",
        "sigstore/cosign-installer",
        "anchore/sbom-action",
        "aquasecurity/trivy-action",
        "actions/upload-artifact",
    }
    missing_actions = sorted(action for action in required_actions if not has_action(workflow, action))
    require(not missing_actions, f"release workflow is missing actions: {', '.join(missing_actions)}", errors)

    image_steps = steps(image_job)
    build_step = next(
        (step for step in image_steps if str(step.get("uses", "")).startswith("docker/build-push-action@")),
        {},
    )
    build_inputs = as_mapping(build_step.get("with"))
    require(build_inputs.get("context") == ".", "release image build context must be repository root", errors)
    require(build_inputs.get("push") == "true", "release workflow must push images", errors)
    require(
        build_inputs.get("platforms") == "linux/amd64,linux/arm64",
        "release workflow must publish amd64 and arm64 images",
        errors,
    )

    runs = "\n".join(str(step.get("run", "")) for job in release_jobs.values() for step in steps(job))
    require("cosign sign" in runs, "release workflow must sign pushed images", errors)
    require("gh release upload" in runs, "release workflow must upload security assets", errors)
    step_names = {str(step.get("name", "")) for job in release_jobs.values() for step in steps(job)}
    require("Create released image Trivy SARIF" in step_names, "release workflow must produce Trivy SARIF", errors)
    require("Gate released image vulnerabilities" in step_names, "release workflow must gate vulnerabilities", errors)


def validate_publish(root: Path, workflow: Mapping[str, Any], errors: list[str]) -> None:
    triggers = as_mapping(workflow.get("on"))
    workflow_run = as_mapping(triggers.get("workflow_run"))
    publish_job = as_mapping(jobs(workflow).get("publish"))
    condition = str(publish_job.get("if", ""))
    permissions = as_mapping(publish_job.get("permissions"))

    require("workflow_dispatch" in triggers, "edge image workflow must support manual republishing", errors)
    require(as_list(workflow_run.get("workflows")) == ["CI"], "edge images must follow the CI workflow", errors)
    require(as_list(workflow_run.get("types")) == ["completed"], "edge workflow must wait for CI completion", errors)
    require(as_list(workflow_run.get("branches")) == ["main"], "edge workflow must only follow main CI", errors)
    require("pull_request_target" not in triggers, "edge workflow must not use pull_request_target", errors)

    required_condition_parts = [
        "github.ref == 'refs/heads/main'",
        "github.event.workflow_run.event == 'push'",
        "github.event.workflow_run.conclusion == 'success'",
        "github.event.workflow_run.head_branch == 'main'",
        "github.event.workflow_run.head_repository.full_name == github.repository",
    ]
    require(
        all(part in condition for part in required_condition_parts),
        "edge publish condition must restrict manual runs and successful same-repository main push CI",
        errors,
    )
    require(
        permissions == {"actions": "read", "contents": "read", "packages": "write"},
        "edge publish job must use only actions:read, contents:read, and packages:write",
        errors,
    )

    matrix_entries = as_list(as_mapping(as_mapping(publish_job.get("strategy")).get("matrix")).get("include"))
    expected_entries = {
        ("platform", "Dockerfile.web-console", "cluster-infra-rca-platform"),
        ("agent", "Dockerfile.agent", "cluster-infra-rca-agent"),
    }
    actual_entries = {
        (
            str(as_mapping(entry).get("component", "")),
            str(as_mapping(entry).get("dockerfile", "")),
            str(as_mapping(entry).get("image", "")),
        )
        for entry in matrix_entries
    }
    require(actual_entries == expected_entries, "edge workflow matrix must contain platform and agent images", errors)
    for _, dockerfile, _ in expected_entries:
        require((root / dockerfile).is_file(), f"edge workflow Dockerfile is missing: {dockerfile}", errors)

    manual_check = find_step_by_name(publish_job, "Verify manually selected revision passed CI")
    manual_run = str(manual_check.get("run", ""))
    require(
        manual_check.get("if") == "github.event_name == 'workflow_dispatch'"
        and "actions/workflows/ci.yml/runs" in manual_run
        and "status=success" in manual_run,
        "manual edge publishing must verify a successful CI push run for the source SHA",
        errors,
    )

    checkout = next(
        (step for step in steps(publish_job) if str(step.get("uses", "")).startswith("actions/checkout@")),
        {},
    )
    checkout_inputs = as_mapping(checkout.get("with"))
    require(checkout_inputs.get("ref") == "${{ steps.source.outputs.sha }}", "edge workflow must checkout the tested SHA", errors)
    require(checkout_inputs.get("persist-credentials") == "false", "edge checkout must not persist Git credentials", errors)

    login = next(
        (step for step in steps(publish_job) if str(step.get("uses", "")).startswith("docker/login-action@")),
        {},
    )
    login_inputs = as_mapping(login.get("with"))
    require(login_inputs.get("registry") == "ghcr.io", "edge workflow must log in to GHCR", errors)
    require(login_inputs.get("password") == "${{ secrets.GITHUB_TOKEN }}", "edge workflow must use GITHUB_TOKEN", errors)

    metadata = next(
        (step for step in steps(publish_job) if str(step.get("uses", "")).startswith("docker/metadata-action@")),
        {},
    )
    tags = str(as_mapping(metadata.get("with")).get("tags", ""))
    require("type=raw,value=edge" in tags, "edge workflow must publish the moving edge tag", errors)
    require(
        "type=raw,value=sha-${{ steps.source.outputs.short_sha }}" in tags,
        "edge workflow must publish an immutable commit tag",
        errors,
    )

    build = next(
        (step for step in steps(publish_job) if str(step.get("uses", "")).startswith("docker/build-push-action@")),
        {},
    )
    build_inputs = as_mapping(build.get("with"))
    require(build_inputs.get("context") == ".", "edge image build context must be repository root", errors)
    require(build_inputs.get("file") == "${{ matrix.dockerfile }}", "edge image build must use the matrix Dockerfile", errors)
    require(build_inputs.get("push") == "true", "edge workflow must push images", errors)
    require(build_inputs.get("platforms") == "linux/amd64", "edge workflow platform must be explicit", errors)
    require(build_inputs.get("provenance") == "mode=max", "edge images must include provenance", errors)
    require(build_inputs.get("sbom") == "true", "edge images must include an SBOM attestation", errors)


def validate_dependabot(config: Mapping[str, Any], errors: list[str]) -> None:
    updates = [as_mapping(update) for update in as_list(config.get("updates"))]
    ecosystems = {str(update.get("package-ecosystem", "")) for update in updates}
    required = {"maven", "pip", "npm", "docker", "github-actions"}
    require(required <= ecosystems, "dependabot must cover Maven, pip, npm, Docker, and GitHub Actions", errors)


def verify(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    security = load_yaml(root, ".github/workflows/security.yml", errors)
    publish = load_yaml(root, ".github/workflows/publish-images.yml", errors)
    release = load_yaml(root, ".github/workflows/release.yml", errors)
    dependabot = load_yaml(root, ".github/dependabot.yml", errors)
    if errors:
        return errors

    validate_security(security, errors)
    validate_publish(root, publish, errors)
    validate_release(release, errors)
    validate_dependabot(dependabot, errors)
    return errors


def main() -> int:
    errors = verify()
    if errors:
        print("supply-chain workflow verification failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1
    print("supply-chain workflow verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
