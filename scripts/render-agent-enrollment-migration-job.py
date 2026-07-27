#!/usr/bin/env python3
"""Render a one-shot Kubernetes Job for Agent enrollment audit or migration."""

from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Any


CONFIRMATION = "APPLY_AGENT_ENROLLMENT_AUDIENCE_MIGRATION"
DEFAULT_TARGET_AUDIENCE = "cluster-infra-rca-agent-enrollment"
DNS_LABEL_PREFIX = re.compile(r"^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?-$")
DNS_LABEL = re.compile(r"^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$")
CLUSTER_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SECRET_KEY = re.compile(r"^[A-Za-z0-9._-]{1,253}$")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description=(
            "Render an audit or apply migration Job. Pipe the JSON to "
            "'kubectl create -f -'; do not store it in Helm release values."
        )
    )
    result.add_argument("--image", required=True)
    result.add_argument("--namespace", default="rca-system")
    result.add_argument(
        "--helm-instance",
        required=True,
        help="Helm release name used by the existing database NetworkPolicy.",
    )
    result.add_argument("--job-prefix", default="rca-agent-enrollment-migration-")
    result.add_argument("--database-secret", required=True)
    result.add_argument("--jdbc-url-key", default="RCA_JDBC_URL")
    result.add_argument("--database-username-key", default="RCA_DB_USERNAME")
    result.add_argument("--database-password-key", default="RCA_DB_PASSWORD")
    result.add_argument("--mode", choices=("audit", "apply"), default="apply")
    result.add_argument("--cluster", action="append", default=[])
    result.add_argument("--target-audience", default=DEFAULT_TARGET_AUDIENCE)
    result.add_argument("--kubernetes-api-audience", action="append", required=True)
    result.add_argument("--confirm", default="")
    result.add_argument(
        "--image-pull-policy",
        choices=("Always", "IfNotPresent", "Never"),
        default="IfNotPresent",
    )
    result.add_argument("--image-pull-secret", action="append", default=[])
    result.add_argument("--backoff-limit", type=int, default=0)
    result.add_argument("--ttl-seconds-after-finished", type=int, default=3600)
    return result


def validate(arguments: argparse.Namespace) -> None:
    if arguments.mode == "apply" and arguments.confirm != CONFIRMATION:
        raise ValueError(f"apply mode --confirm must be exactly {CONFIRMATION}")
    if arguments.mode == "audit" and (arguments.confirm or arguments.cluster):
        raise ValueError("audit mode must not include --confirm or --cluster")
    if not DNS_LABEL_PREFIX.fullmatch(arguments.job_prefix) or len(arguments.job_prefix) > 52:
        raise ValueError("--job-prefix must be a lowercase DNS label prefix ending in '-'")
    if (
        len(arguments.namespace) > 63
        or not DNS_LABEL.fullmatch(arguments.namespace)
    ):
        raise ValueError("--namespace must be a valid Kubernetes DNS label")
    if (
        len(arguments.helm_instance) > 63
        or not DNS_LABEL.fullmatch(arguments.helm_instance)
    ):
        raise ValueError("--helm-instance must be a valid Kubernetes DNS label")
    if not arguments.image.strip():
        raise ValueError("--image must not be empty")
    clusters = set(arguments.cluster)
    if len(clusters) != len(arguments.cluster):
        raise ValueError("--cluster values must be unique")
    if arguments.mode == "apply" and (
        not clusters
        or len(clusters) > 100
        or any(not CLUSTER_ID.fullmatch(value) for value in clusters)
    ):
        raise ValueError("apply mode --cluster must contain 1-100 valid cluster IDs")
    api_audiences = set(arguments.kubernetes_api_audience)
    if len(api_audiences) != len(arguments.kubernetes_api_audience):
        raise ValueError("--kubernetes-api-audience values must be unique")
    if arguments.target_audience in api_audiences:
        raise ValueError("--target-audience must not match a Kubernetes API audience")
    if (
        not arguments.target_audience.strip()
        or arguments.target_audience != arguments.target_audience.strip()
        or len(arguments.target_audience) > 255
    ):
        raise ValueError("--target-audience must contain 1-255 characters")
    if arguments.backoff_limit < 0:
        raise ValueError("--backoff-limit must be zero or greater")
    if not 60 <= arguments.ttl_seconds_after_finished <= 86400:
        raise ValueError("--ttl-seconds-after-finished must be between 60 and 86400")
    for value in (
        arguments.database_secret,
        arguments.jdbc_url_key,
        arguments.database_username_key,
        arguments.database_password_key,
        *arguments.image_pull_secret,
    ):
        if not SECRET_KEY.fullmatch(value):
            raise ValueError("Secret names and keys must use Kubernetes-safe characters")


def secret_env(name: str, secret: str, key: str) -> dict[str, Any]:
    return {
        "name": name,
        "valueFrom": {
            "secretKeyRef": {
                "name": secret,
                "key": key,
            }
        },
    }


def build_job(arguments: argparse.Namespace) -> dict[str, Any]:
    validate(arguments)
    migration_env: list[dict[str, Any]] = [
        {
            "name": "RCA_AGENT_ENROLLMENT_MIGRATION_MODE",
            "value": arguments.mode,
        },
        {
            "name": "RCA_AGENT_ENROLLMENT_MIGRATION_TARGET_AUDIENCE",
            "value": arguments.target_audience,
        },
        {
            "name": "RCA_KUBERNETES_API_AUDIENCES",
            "value": ",".join(arguments.kubernetes_api_audience),
        },
    ]
    if arguments.mode == "apply":
        migration_env.extend([
            {
                "name": "RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS",
                "value": ",".join(arguments.cluster),
            },
            {
                "name": "RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM",
                "value": CONFIRMATION,
            },
        ])
    labels = {
        "app.kubernetes.io/name": "cluster-infra-rca-platform",
        "app.kubernetes.io/instance": arguments.helm_instance,
        "app.kubernetes.io/component": "platform",
        "rca.clusterinfra.io/job-role": "agent-enrollment-migration",
        "rca.clusterinfra.io/database-client": "true",
    }
    pod_spec: dict[str, Any] = {
        "restartPolicy": "Never",
        "automountServiceAccountToken": False,
        "containers": [
            {
                "name": "migration",
                "image": arguments.image,
                "imagePullPolicy": arguments.image_pull_policy,
                "command": ["java"],
                "args": [
                    "-Dloader.main=io.clusterinfra.rca.webconsole.maintenance.AgentEnrollmentMigrationCli",
                    "-cp",
                    "/app/platform.jar",
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                ],
                "env": [
                    secret_env(
                        "RCA_JDBC_URL",
                        arguments.database_secret,
                        arguments.jdbc_url_key,
                    ),
                    secret_env(
                        "RCA_DB_USERNAME",
                        arguments.database_secret,
                        arguments.database_username_key,
                    ),
                    secret_env(
                        "RCA_DB_PASSWORD",
                        arguments.database_secret,
                        arguments.database_password_key,
                    ),
                    *migration_env,
                ],
                "securityContext": {
                    "allowPrivilegeEscalation": False,
                    "readOnlyRootFilesystem": True,
                    "runAsNonRoot": True,
                    "capabilities": {"drop": ["ALL"]},
                },
                "volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}],
            }
        ],
        "volumes": [{"name": "tmp", "emptyDir": {}}],
    }
    if arguments.image_pull_secret:
        pod_spec["imagePullSecrets"] = [
            {"name": name} for name in arguments.image_pull_secret
        ]

    return {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "generateName": arguments.job_prefix,
            "namespace": arguments.namespace,
            "labels": labels,
        },
        "spec": {
            "backoffLimit": arguments.backoff_limit,
            "ttlSecondsAfterFinished": arguments.ttl_seconds_after_finished,
            "template": {
                "metadata": {"labels": labels},
                "spec": pod_spec,
            },
        },
    }


def main(argv: list[str] | None = None) -> int:
    argument_parser = parser()
    arguments = argument_parser.parse_args(argv)
    try:
        job = build_job(arguments)
    except ValueError as exception:
        argument_parser.error(str(exception))
    json.dump(job, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
