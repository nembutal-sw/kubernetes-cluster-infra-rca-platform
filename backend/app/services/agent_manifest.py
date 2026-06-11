from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlparse

from backend.app.models import Cluster


DEFAULT_AGENT_IMAGE = "ghcr.io/example/cluster-infra-rca-agent:latest"
DEFAULT_AGENT_NAMESPACE = "rca-system"
DEFAULT_POLL_INTERVAL_SECONDS = 15
DEFAULT_HTTP_TIMEOUT_SECONDS = 10
DEFAULT_COMMAND_TIMEOUT_SECONDS = 5

_K8S_NAME_PATTERN = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")


@dataclass(frozen=True)
class AgentManifestOptions:
    backend_url: str
    image: str = DEFAULT_AGENT_IMAGE
    namespace: str = DEFAULT_AGENT_NAMESPACE
    poll_interval_seconds: int = DEFAULT_POLL_INTERVAL_SECONDS
    http_timeout_seconds: int = DEFAULT_HTTP_TIMEOUT_SECONDS
    command_timeout_seconds: int = DEFAULT_COMMAND_TIMEOUT_SECONDS


def build_agent_manifest(cluster: Cluster, options: AgentManifestOptions) -> dict[str, object]:
    options = normalize_manifest_options(options)
    app_name = "cluster-infra-rca-agent"
    config_map_name = f"{app_name}-config"
    secret_name = app_name

    return {
        "apiVersion": "v1",
        "kind": "List",
        "items": [
            {
                "apiVersion": "v1",
                "kind": "Namespace",
                "metadata": {"name": options.namespace},
            },
            {
                "apiVersion": "v1",
                "kind": "ServiceAccount",
                "metadata": {"name": app_name, "namespace": options.namespace},
            },
            _agent_cluster_role(app_name),
            _agent_cluster_role_binding(app_name=app_name, namespace=options.namespace),
            {
                "apiVersion": "v1",
                "kind": "ConfigMap",
                "metadata": {
                    "name": config_map_name,
                    "namespace": options.namespace,
                    "annotations": {
                        "cluster-infra-rca.io/cluster-id": cluster.cluster_id,
                        "cluster-infra-rca.io/agent-secret-name": secret_name,
                    },
                },
                "data": {
                    "BACKEND_URL": options.backend_url.rstrip("/"),
                    "POLL_INTERVAL_SECONDS": str(options.poll_interval_seconds),
                    "HTTP_TIMEOUT_SECONDS": str(options.http_timeout_seconds),
                    "COMMAND_TIMEOUT_SECONDS": str(options.command_timeout_seconds),
                    "KUBERNETES_API_TIMEOUT_SECONDS": str(options.command_timeout_seconds),
                    "CONTROL_PLANE_PROBE_PORTS": "6443,9345",
                },
            },
            _agent_daemonset(
                app_name=app_name,
                namespace=options.namespace,
                config_map_name=config_map_name,
                secret_name=secret_name,
                image=options.image,
            ),
        ],
    }


def validate_backend_url(backend_url: str) -> str:
    normalized = backend_url.strip().rstrip("/")
    parsed = urlparse(normalized)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("backend_url must be an absolute http or https URL")
    return normalized


def validate_kubernetes_name(value: str, field_name: str) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > 63 or _K8S_NAME_PATTERN.fullmatch(normalized) is None:
        raise ValueError(f"{field_name} must be a valid Kubernetes DNS label")
    return normalized


def validate_image(value: str) -> str:
    image = value.strip()
    if not image or len(image) > 512 or any(character.isspace() for character in image):
        raise ValueError("image must be a non-empty container image reference without whitespace")
    return image


def validate_timeout(value: int, field_name: str, minimum: int = 1, maximum: int = 3600) -> int:
    if value < minimum or value > maximum:
        raise ValueError(f"{field_name} must be between {minimum} and {maximum}")
    return value


def normalize_manifest_options(options: AgentManifestOptions) -> AgentManifestOptions:
    backend_url = validate_backend_url(options.backend_url)
    image = validate_image(options.image)
    namespace = validate_kubernetes_name(options.namespace, "namespace")
    validate_timeout(options.poll_interval_seconds, "poll_interval_seconds", minimum=5)
    validate_timeout(options.http_timeout_seconds, "http_timeout_seconds")
    validate_timeout(options.command_timeout_seconds, "command_timeout_seconds")
    return AgentManifestOptions(
        backend_url=backend_url,
        image=image,
        namespace=namespace,
        poll_interval_seconds=options.poll_interval_seconds,
        http_timeout_seconds=options.http_timeout_seconds,
        command_timeout_seconds=options.command_timeout_seconds,
    )


def _agent_cluster_role(app_name: str) -> dict[str, object]:
    return {
        "apiVersion": "rbac.authorization.k8s.io/v1",
        "kind": "ClusterRole",
        "metadata": {"name": app_name},
        "rules": [
            {
                "apiGroups": [""],
                "resources": ["nodes", "pods", "events"],
                "verbs": ["get", "list"],
            },
            {
                "apiGroups": ["coordination.k8s.io"],
                "resources": ["leases"],
                "verbs": ["get", "list"],
            },
            {
                "apiGroups": ["metrics.k8s.io"],
                "resources": ["nodes", "pods"],
                "verbs": ["get", "list"],
            },
            {
                "nonResourceURLs": ["/readyz", "/readyz/*", "/livez", "/livez/*"],
                "verbs": ["get"],
            },
        ],
    }


def _agent_cluster_role_binding(app_name: str, namespace: str) -> dict[str, object]:
    return {
        "apiVersion": "rbac.authorization.k8s.io/v1",
        "kind": "ClusterRoleBinding",
        "metadata": {"name": app_name},
        "subjects": [
            {
                "kind": "ServiceAccount",
                "name": app_name,
                "namespace": namespace,
            }
        ],
        "roleRef": {
            "apiGroup": "rbac.authorization.k8s.io",
            "kind": "ClusterRole",
            "name": app_name,
        },
    }


def _agent_daemonset(
    app_name: str,
    namespace: str,
    config_map_name: str,
    secret_name: str,
    image: str,
) -> dict[str, object]:
    labels = {"app.kubernetes.io/name": app_name}
    return {
        "apiVersion": "apps/v1",
        "kind": "DaemonSet",
        "metadata": {"name": app_name, "namespace": namespace, "labels": labels},
        "spec": {
            "selector": {"matchLabels": labels},
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "serviceAccountName": app_name,
                    "hostNetwork": True,
                    "hostPID": True,
                    "tolerations": [{"operator": "Exists"}],
                    "containers": [
                        {
                            "name": "agent",
                            "image": image,
                            "imagePullPolicy": "IfNotPresent",
                            "command": ["python", "-m", "node_agent.main"],
                            "env": _agent_env(config_map_name, secret_name),
                            "securityContext": {
                                "readOnlyRootFilesystem": True,
                                "allowPrivilegeEscalation": False,
                            },
                            "volumeMounts": _agent_volume_mounts(),
                            "resources": {
                                "requests": {"cpu": "50m", "memory": "64Mi"},
                                "limits": {"cpu": "500m", "memory": "256Mi"},
                            },
                        }
                    ],
                    "volumes": _agent_volumes(),
                },
            },
        },
    }


def _agent_env(config_map_name: str, secret_name: str) -> list[dict[str, object]]:
    config_map_keys = [
        "BACKEND_URL",
        "POLL_INTERVAL_SECONDS",
        "HTTP_TIMEOUT_SECONDS",
        "COMMAND_TIMEOUT_SECONDS",
        "KUBERNETES_API_TIMEOUT_SECONDS",
        "CONTROL_PLANE_PROBE_PORTS",
    ]
    env = [
        {"name": "PYTHONDONTWRITEBYTECODE", "value": "1"},
        {"name": "PYTHONUNBUFFERED", "value": "1"},
        *[
            {
                "name": key,
                "valueFrom": {"configMapKeyRef": {"name": config_map_name, "key": key}},
            }
            for key in config_map_keys
        ],
        {
            "name": "CLUSTER_ID",
            "valueFrom": {"secretKeyRef": {"name": secret_name, "key": "cluster-id"}},
        },
        {
            "name": "AGENT_TOKEN",
            "valueFrom": {"secretKeyRef": {"name": secret_name, "key": "agent-token"}},
        },
        {
            "name": "NODE_NAME",
            "valueFrom": {"fieldRef": {"fieldPath": "spec.nodeName"}},
        },
    ]
    return env


def _agent_volume_mounts() -> list[dict[str, object]]:
    return [
        {"name": "host-root", "mountPath": "/host/root", "readOnly": True},
        {"name": "host-var-log", "mountPath": "/host/var/log", "readOnly": True},
        {"name": "host-run-systemd", "mountPath": "/host/run/systemd", "readOnly": True},
        {"name": "host-etc", "mountPath": "/host/etc", "readOnly": True},
        {"name": "host-proc", "mountPath": "/host/proc", "readOnly": True},
        {"name": "host-sys", "mountPath": "/host/sys", "readOnly": True},
        {
            "name": "containerd-sock",
            "mountPath": "/host/run/containerd/containerd.sock",
            "readOnly": True,
        },
    ]


def _agent_volumes() -> list[dict[str, object]]:
    return [
        {"name": "host-root", "hostPath": {"path": "/"}},
        {"name": "host-var-log", "hostPath": {"path": "/var/log"}},
        {"name": "host-run-systemd", "hostPath": {"path": "/run/systemd"}},
        {"name": "host-etc", "hostPath": {"path": "/etc"}},
        {"name": "host-proc", "hostPath": {"path": "/proc"}},
        {"name": "host-sys", "hostPath": {"path": "/sys"}},
        {
            "name": "containerd-sock",
            "hostPath": {"path": "/run/containerd/containerd.sock", "type": "Socket"},
        },
    ]
