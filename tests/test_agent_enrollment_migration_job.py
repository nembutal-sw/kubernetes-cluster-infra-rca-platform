from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "render-agent-enrollment-migration-job.py"
CONFIRMATION = "APPLY_AGENT_ENROLLMENT_AUDIENCE_MIGRATION"


def command(*extra: str) -> list[str]:
    return [
        sys.executable,
        str(SCRIPT),
        "--image",
        "registry.example/rca-platform@sha256:" + "a" * 64,
        "--database-secret",
        "rca-platform-database",
        "--helm-instance",
        "rca",
        "--cluster",
        "cluster-a",
        "--kubernetes-api-audience",
        "https://kubernetes.default.svc",
        "--confirm",
        CONFIRMATION,
        *extra,
    ]


def test_renders_apply_only_job_with_database_credentials_only() -> None:
    result = subprocess.run(command(), check=True, capture_output=True, text=True)
    job = json.loads(result.stdout)

    assert job["metadata"]["generateName"] == "rca-agent-enrollment-migration-"
    labels = job["spec"]["template"]["metadata"]["labels"]
    assert labels["app.kubernetes.io/instance"] == "rca"
    assert labels["app.kubernetes.io/component"] == "platform"
    assert labels["rca.clusterinfra.io/job-role"] == "agent-enrollment-migration"
    assert labels["rca.clusterinfra.io/database-client"] == "true"
    pod_spec = job["spec"]["template"]["spec"]
    assert pod_spec["automountServiceAccountToken"] is False
    container = pod_spec["containers"][0]
    assert "envFrom" not in container

    env = {item["name"]: item for item in container["env"]}
    assert env["RCA_AGENT_ENROLLMENT_MIGRATION_MODE"]["value"] == "apply"
    assert env["RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS"]["value"] == "cluster-a"
    secret_env = [item for item in container["env"] if "valueFrom" in item]
    assert [item["name"] for item in secret_env] == [
        "RCA_JDBC_URL",
        "RCA_DB_USERNAME",
        "RCA_DB_PASSWORD",
    ]
    assert {
        item["valueFrom"]["secretKeyRef"]["name"] for item in secret_env
    } == {"rca-platform-database"}


def test_rejects_missing_exact_confirmation() -> None:
    args = command()
    args[args.index(CONFIRMATION)] = "wrong"

    result = subprocess.run(args, check=False, capture_output=True, text=True)

    assert result.returncode == 2
    assert "apply mode --confirm must be exactly" in result.stderr


def test_rejects_target_audience_overlap() -> None:
    result = subprocess.run(
        command(
            "--target-audience",
            "https://kubernetes.default.svc",
        ),
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "must not match a Kubernetes API audience" in result.stderr


def test_rejects_invalid_namespace() -> None:
    result = subprocess.run(
        command("--namespace", "RCA_SYSTEM"),
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "valid Kubernetes DNS label" in result.stderr


def test_renders_audit_job_without_apply_controls() -> None:
    args = command()
    cluster_index = args.index("--cluster")
    del args[cluster_index:cluster_index + 2]
    confirm_index = args.index("--confirm")
    del args[confirm_index:confirm_index + 2]
    args.extend(["--mode", "audit"])

    result = subprocess.run(args, check=True, capture_output=True, text=True)
    job = json.loads(result.stdout)
    env = {
        item["name"]: item
        for item in job["spec"]["template"]["spec"]["containers"][0]["env"]
    }

    assert env["RCA_AGENT_ENROLLMENT_MIGRATION_MODE"]["value"] == "audit"
    assert "RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS" not in env
    assert "RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM" not in env
