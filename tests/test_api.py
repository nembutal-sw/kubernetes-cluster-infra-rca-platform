from fastapi.testclient import TestClient
from datetime import datetime, timedelta, timezone

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect
from sqlalchemy.dialects import mysql, postgresql
from sqlalchemy.schema import CreateTable

import backend.app.db_models  # noqa: F401
import backend.app.main as main_module
from backend.app.config import load_settings, normalize_database_url
from backend.app.database import Base, create_db_engine
from backend.app.main import create_app
from backend.app.models import EvidenceBundle
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.policy import PolicyEngine


WEBHOOK_HEADERS = {"X-Webhook-Token": "dev-webhook-token"}


def admin_headers(client: TestClient) -> dict[str, str]:
    response = client.post("/api/auth/login", json={"username": "admin", "password": "admin"})
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def test_cluster_registration_and_install_command(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    headers = admin_headers(client)

    assert client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).status_code == 401

    create_response = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert create_response.status_code == 201
    cluster = create_response.json()
    assert cluster["cluster_id"].startswith("cluster-")
    assert cluster["status"] == "agent_pending"

    assert client.get(f"/api/clusters/{cluster['cluster_id']}/install-command").status_code == 401

    install_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/install-command",
        headers=headers,
    )

    assert install_response.status_code == 200
    install = install_response.json()
    assert install["namespace"] == "rca-system"
    assert any("agent-token" in command for command in install["commands"])
    assert install["notes"]
    assert all("\ufffd" not in note for note in install["notes"])

    list_response = client.get("/api/clusters", headers=headers)
    assert list_response.status_code == 200
    assert "bootstrap_token" not in list_response.json()[0]


def test_backend_root_is_api_only_metadata(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    root_response = client.get("/")
    script_response = client.get("/static/app.js")

    assert root_response.status_code == 200
    assert root_response.json() == {
        "service": "cluster-infra-rca-backend",
        "status": "api_only",
        "web_console": "spring-boot-web-console",
        "docs": "/docs",
        "health": "/health",
    }
    assert root_response.headers["X-Frame-Options"] == "DENY"
    assert root_response.headers["X-Content-Type-Options"] == "nosniff"
    assert script_response.status_code == 404


def test_cluster_install_command_can_use_generated_manifest_url(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    install_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/install-command",
        headers=admin_headers(client),
        params={
            "backend_url": "https://rca.example.com",
            "image": "ghcr.io/acme/cluster-infra-rca-agent:v1",
            "namespace": "custom-rca",
        },
    )

    assert install_response.status_code == 200
    install = install_response.json()
    assert install["namespace"] == "custom-rca"
    assert install["commands"][0] == "kubectl create namespace custom-rca --dry-run=client -o yaml | kubectl apply -f -"
    assert "--from-literal=cluster-id=" + cluster["cluster_id"] in install["commands"][1]
    assert "--from-literal=agent-token=" + cluster["bootstrap_token"] in install["commands"][1]
    assert "/api/clusters/" + cluster["cluster_id"] + "/agent-manifest?" in install["commands"][2]
    assert "backend_url=https%3A%2F%2Frca.example.com" in install["commands"][2]
    assert "image=ghcr.io%2Facme%2Fcluster-infra-rca-agent%3Av1" in install["commands"][2]
    assert "namespace=custom-rca" in install["commands"][2]
    assert "systemd_collector_mode=file" in install["commands"][2]
    assert "agent_token=" + cluster["bootstrap_token"] in install["commands"][2]
    assert any("cluster_id" in note for note in install["notes"])


def test_agent_manifest_generation_and_validation(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com"},
    ).status_code == 401
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com", "agent_token": "wrong-token"},
    ).status_code == 401

    manifest_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com/",
            "image": "ghcr.io/acme/cluster-infra-rca-agent:v1",
            "namespace": "custom-rca",
            "poll_interval_seconds": 30,
            "http_timeout_seconds": 20,
            "command_timeout_seconds": 7,
            "kubernetes_api_timeout_seconds": 9,
            "control_plane_probe_ports": "6443, 9345, 6443",
            "runtime_socket_paths": "crio=/run/crio/crio.sock;/run/containerd/containerd.sock",
            "systemd_collector_mode": "file",
            "agent_token": cluster["bootstrap_token"],
        },
    )

    assert manifest_response.status_code == 200
    manifest = manifest_response.json()
    assert manifest["apiVersion"] == "v1"
    assert manifest["kind"] == "List"
    assert "metadata" not in manifest

    items = {item["kind"]: item for item in manifest["items"]}
    assert items["Namespace"]["metadata"]["name"] == "custom-rca"

    config_map = items["ConfigMap"]
    assert config_map["metadata"]["annotations"]["cluster-infra-rca.io/cluster-id"] == cluster["cluster_id"]
    assert config_map["data"] == {
        "BACKEND_URL": "https://rca.example.com",
        "POLL_INTERVAL_SECONDS": "30",
        "HTTP_TIMEOUT_SECONDS": "20",
        "COMMAND_TIMEOUT_SECONDS": "7",
        "KUBERNETES_API_TIMEOUT_SECONDS": "9",
        "CONTROL_PLANE_PROBE_PORTS": "6443,9345",
        "CONTAINER_RUNTIME_SOCKET_PATHS": "crio=/run/crio/crio.sock,/run/containerd/containerd.sock",
        "SYSTEMD_COLLECTOR_MODE": "file",
    }
    cluster_role = items["ClusterRole"]
    assert cluster_role["rules"][0]["resources"] == ["nodes", "pods", "events"]
    assert cluster_role["rules"][0]["verbs"] == ["get", "list"]
    cluster_role_binding = items["ClusterRoleBinding"]
    assert cluster_role_binding["subjects"][0]["namespace"] == "custom-rca"

    daemonset = items["DaemonSet"]
    container = daemonset["spec"]["template"]["spec"]["containers"][0]
    assert container["image"] == "ghcr.io/acme/cluster-infra-rca-agent:v1"
    assert container["command"] == ["python", "-m", "node_agent.main"]
    assert {"name": "host-root", "mountPath": "/host/root", "readOnly": True} in container["volumeMounts"]
    assert {"name": "host-run", "mountPath": "/host/run", "readOnly": True} in container["volumeMounts"]
    assert {"name": "host-root", "hostPath": {"path": "/"}} in daemonset["spec"]["template"]["spec"]["volumes"]
    assert {"name": "host-run", "hostPath": {"path": "/run"}} in daemonset["spec"]["template"]["spec"]["volumes"]
    assert "containerd-sock" not in {item["name"] for item in container["volumeMounts"]}

    env = {item["name"]: item for item in container["env"]}
    assert env["BACKEND_URL"]["valueFrom"]["configMapKeyRef"]["name"] == "cluster-infra-rca-agent-config"
    assert env["CLUSTER_ID"]["valueFrom"]["secretKeyRef"]["key"] == "cluster-id"
    assert env["AGENT_TOKEN"]["valueFrom"]["secretKeyRef"]["key"] == "agent-token"
    assert env["KUBERNETES_API_TIMEOUT_SECONDS"]["valueFrom"]["configMapKeyRef"]["key"] == "KUBERNETES_API_TIMEOUT_SECONDS"
    assert env["CONTROL_PLANE_PROBE_PORTS"]["valueFrom"]["configMapKeyRef"]["key"] == "CONTROL_PLANE_PROBE_PORTS"
    assert env["CONTAINER_RUNTIME_SOCKET_PATHS"]["valueFrom"]["configMapKeyRef"]["key"] == "CONTAINER_RUNTIME_SOCKET_PATHS"
    assert env["SYSTEMD_COLLECTOR_MODE"]["valueFrom"]["configMapKeyRef"]["key"] == "SYSTEMD_COLLECTOR_MODE"
    assert daemonset["spec"]["template"]["spec"]["dnsPolicy"] == "ClusterFirstWithHostNet"
    assert daemonset["spec"]["template"]["spec"]["terminationGracePeriodSeconds"] == 20
    assert cluster["bootstrap_token"] not in str(manifest)

    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "not-a-url", "agent_token": cluster["bootstrap_token"]},
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com",
            "namespace": "Bad_Namespace",
            "agent_token": cluster["bootstrap_token"],
        },
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com", "image": "bad image", "agent_token": cluster["bootstrap_token"]},
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com",
            "poll_interval_seconds": 1,
            "agent_token": cluster["bootstrap_token"],
        },
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com",
            "control_plane_probe_ports": "6443,bad",
            "agent_token": cluster["bootstrap_token"],
        },
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com",
            "runtime_socket_paths": "crio=relative.sock",
            "agent_token": cluster["bootstrap_token"],
        },
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com",
            "systemd_collector_mode": "journal",
            "agent_token": cluster["bootstrap_token"],
        },
    ).status_code == 422
    assert client.get(
        "/api/clusters/cluster-does-not-exist/agent-manifest",
        params={"backend_url": "https://rca.example.com"},
    ).status_code == 404


def test_action_collectors_do_not_request_unsupported_journal_collector() -> None:
    low_level_collectors = main_module._collectors_for_action("collect_linux_low_level_evidence")
    kernel_collectors = main_module._collectors_for_action("inspect_kernel_state")

    assert "journal" not in low_level_collectors
    assert "journal" not in kernel_collectors
    assert "kubelet" in low_level_collectors
    assert "kubelet" in kernel_collectors
    assert "inode" in low_level_collectors


def test_alertmanager_webhook_creates_rca_report(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    webhook_response = client.post(
        "/api/webhooks/alertmanager",
        headers=WEBHOOK_HEADERS,
        json={
            "receiver": "cluster-infra-rca",
            "status": "firing",
            "alerts": [
                {
                    "status": "firing",
                    "labels": {
                        "alertname": "NodeNotReady",
                        "severity": "critical",
                        "cluster_id": cluster["cluster_id"],
                        "node": "worker-3",
                        "component": "kubelet",
                    },
                    "annotations": {
                        "summary": "worker-3 node is NotReady",
                    },
                    "startsAt": "2026-06-10T09:15:00+09:00",
                }
            ],
        },
    )

    assert webhook_response.status_code == 200
    result = webhook_response.json()
    assert result["received_alerts"] == 1
    assert len(result["created_jobs"]) == 1
    assert len(result["created_reports"]) == 1
    assert result["created_evidence_requests"] == []
    assert result["created_jobs"][0]["evidence_id"].startswith("evidence-")
    assert result["created_jobs"][0]["report_id"] == result["created_reports"][0]

    report_response = client.get(f"/api/rca/reports/{result['created_reports'][0]}", headers=admin_headers(client))

    assert report_response.status_code == 200
    report = report_response.json()
    assert report["summary"]["confidence"] == "high"
    assert report["scope"]["nodes"] == ["worker-3"]
    preprocessed = _report_section(report, "preprocessed_evidence")["payload"]
    assert preprocessed["llm_input_policy"]["use_this_payload_only"] is True
    assert preprocessed["key_metrics"]["runtime"]["containerd_socket_healthy"] is False
    derived_signals = _report_section(report, "derived_signals")["signals"]
    assert {signal["signal"] for signal in derived_signals} >= {
        "containerd_socket_unhealthy",
        "conntrack_near_limit",
    }
    assert {action["policy"] for action in report["recommended_actions"]} == {
        "AUTO_SAFE",
        "APPROVAL_REQUIRED",
        "GITOPS_PR_ONLY",
    }
    jobs_response = client.get("/api/rca/jobs", headers=admin_headers(client))
    reports_response = client.get("/api/rca/reports", headers=admin_headers(client))
    assert jobs_response.json()[0]["report_id"] == reports_response.json()[0]["report_id"]


def test_alertmanager_webhook_requires_webhook_token(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    payload = {"receiver": "cluster-infra-rca", "status": "firing", "alerts": []}

    assert client.post("/api/webhooks/alertmanager", json=payload).status_code == 401
    assert client.post(
        "/api/webhooks/alertmanager",
        headers={"X-Webhook-Token": "wrong-token"},
        json=payload,
    ).status_code == 401
    assert client.post(
        "/api/webhooks/alertmanager",
        headers={"Authorization": "Bearer wrong-token"},
        json=payload,
    ).status_code == 401

    bearer_response = client.post(
        "/api/webhooks/alertmanager",
        headers={"Authorization": "Bearer dev-webhook-token"},
        json=payload,
    )
    assert bearer_response.status_code == 200
    assert bearer_response.json()["received_alerts"] == 0


def test_signup_and_approval_endpoints_are_removed(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    assert client.post(
        "/api/auth/signup",
        json={
            "email": "operator@example.com",
            "full_name": "Cluster Operator",
            "password": "safe-password-123",
            "requested_role": "operator",
        },
    ).status_code == 404
    assert client.get("/api/admin/users", headers=admin_headers(client)).status_code == 404
    assert client.post(
        "/api/admin/users/user-123/approval",
        headers=admin_headers(client),
        json={"decision": "approve", "role": "operator"},
    ).status_code == 404


def test_default_admin_login_password_change_and_api_auth(tmp_path) -> None:
    database_url = f"sqlite:///{tmp_path / 'test.db'}"
    client = TestClient(create_app(database_url=database_url, auto_create_tables=True))

    assert client.get("/api/clusters").status_code == 401
    assert client.post(
        "/api/clusters",
        headers={"X-Admin-Token": "dev-admin-approval-token"},
        json={"name": "token-bypass-blocked", "environment": "dev"},
    ).status_code == 401

    wrong_password = client.post("/api/auth/login", json={"username": "admin", "password": "wrong-password"})
    assert wrong_password.status_code == 401

    login = client.post("/api/auth/login", json={"username": "admin", "password": "admin"})
    assert login.status_code == 200
    session = login.json()
    assert session["token_type"] == "bearer"
    assert session["access_token"]
    assert session["user"]["email"] == "admin"
    assert session["user"]["role"] == "admin"
    assert session["user"]["status"] == "active"
    assert "password" not in session["user"]
    headers = {"Authorization": f"Bearer {session['access_token']}"}

    me_response = client.get("/api/auth/me", headers=headers)
    assert me_response.status_code == 200
    assert me_response.json()["email"] == "admin"

    create_cluster = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "admin-created", "environment": "stage"},
    )
    assert create_cluster.status_code == 201
    assert create_cluster.json()["bootstrap_token"]

    wrong_change = client.post(
        "/api/auth/change-password",
        headers=headers,
        json={"current_password": "wrong-password", "new_password": "new-admin-password"},
    )
    assert wrong_change.status_code == 401

    change_response = client.post(
        "/api/auth/change-password",
        headers=headers,
        json={"current_password": "admin", "new_password": "new-admin-password"},
    )
    assert change_response.status_code == 200
    assert change_response.json() == {"changed": True}

    logout_response = client.post("/api/auth/logout", headers=headers)
    assert logout_response.status_code == 200
    assert logout_response.json() == {"revoked": True}
    assert client.get("/api/auth/me", headers=headers).status_code == 401

    second_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))
    assert second_client.post("/api/auth/login", json={"username": "admin", "password": "admin"}).status_code == 401
    changed_login = second_client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "new-admin-password"},
    )
    assert changed_login.status_code == 200


def test_alertmanager_webhook_creates_evidence_request_for_registered_agent(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["node", "systemd", "runtime", "kernel", "network"],
        },
    )
    node_token = register_response.json()["node_token"]

    webhook_response = client.post(
        "/api/webhooks/alertmanager",
        headers=WEBHOOK_HEADERS,
        json={
            "receiver": "cluster-infra-rca",
            "status": "firing",
            "alerts": [
                {
                    "status": "firing",
                    "labels": {
                        "alertname": "NodeNotReady",
                        "severity": "critical",
                        "cluster_id": cluster["cluster_id"],
                        "node": "worker-3",
                        "component": "kubelet",
                    },
                    "annotations": {
                        "summary": "worker-3 node is NotReady",
                    },
                    "startsAt": "2026-06-10T09:15:00+09:00",
                }
            ],
        },
    )

    assert webhook_response.status_code == 200
    result = webhook_response.json()
    assert result["created_jobs"] == []
    assert result["created_reports"] == []
    assert len(result["created_evidence_requests"]) == 1

    evidence_request = result["created_evidence_requests"][0]
    assert evidence_request["status"] == "pending"
    assert evidence_request["cluster_id"] == cluster["cluster_id"]
    assert evidence_request["node_name"] == "worker-3"
    assert evidence_request["alert_name"] == "NodeNotReady"
    assert evidence_request["requested_collectors"] == [
        "node",
        "kubernetes",
        "systemd",
        "runtime",
        "kernel",
        "network",
        "conntrack",
    ]
    assert evidence_request["time_range"]["from"] == "2026-06-10T09:15:00+09:00"

    poll_response = client.post(
        "/api/agents/evidence-requests",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
        },
    )

    assert poll_response.status_code == 200
    assert [item["request_id"] for item in poll_response.json()] == [evidence_request["request_id"]]

    submit_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
            "status": "completed",
            "collectors": {
                "kubernetes": {
                    "api_available": True,
                    "node_ready": False,
                    "metrics_available": False,
                    "metrics_error": "HTTP Error 503: Service Unavailable",
                    "failed_peer_probe_count": 1,
                    "control_plane_peer_connectivity": [
                        {"node": "core-b", "address": "10.0.0.2", "port": 9345, "ok": False}
                    ],
                    "cni_high_restart_pods": [
                        {"namespace": "kube-system", "name": "cilium-pdvd8", "restart_count": 22029}
                    ],
                },
                "systemd": {"kubelet_status": "restarting", "kubelet_restart_count": 7},
                "runtime": {"containerd_socket_healthy": False},
                "network": {"conntrack_usage_percent": 91},
            },
        },
    )

    assert submit_response.status_code == 200
    jobs_response = client.get("/api/rca/jobs", headers=admin_headers(client))
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    assert jobs[0]["alert_name"] == "NodeNotReady"
    assert jobs[0]["evidence_id"] == submit_response.json()["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{jobs[0]['report_id']}", headers=admin_headers(client))
    assert report_response.status_code == 200
    report = report_response.json()
    signals = _report_section(report, "derived_signals")["signals"]
    assert {signal["signal"] for signal in signals} >= {
        "kubelet_unit_unhealthy",
        "containerd_socket_unhealthy",
        "conntrack_near_limit",
        "control_plane_peer_unreachable",
        "cni_pod_restarting",
        "node_metrics_unavailable",
    }
    checklist = _report_section(report, "resolution_checklist")["items"]
    assert {item["component"] for item in checklist} >= {"kubelet", "containerd", "network"}


def test_rca_report_identifies_disk_and_kernel_evidence_for_resolution(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["disk", "kernel", "systemd"],
        },
    )
    node_token = register_response.json()["node_token"]
    evidence_request = client.post(
        "/api/evidence/requests",
        headers=admin_headers(client),
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "alert_name": "DiskPressure",
            "requested_collectors": ["disk", "kernel", "systemd"],
        },
    ).json()

    submit_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
            "status": "completed",
            "collectors": {
                "disk": {
                    "root_usage_percent": 96,
                    "inode_usage_percent": 99,
                    "root_mount_read_only": True,
                    "kernel_io_error_detected": True,
                    "io_pressure": {"full": {"avg10": 12}},
                },
                "kernel": {
                    "io_error_detected": True,
                    "read_only_filesystem_detected": True,
                },
            },
        },
    )

    assert submit_response.status_code == 200
    job = client.get("/api/rca/jobs", headers=admin_headers(client)).json()[0]
    report = client.get(f"/api/rca/reports/{job['report_id']}", headers=admin_headers(client)).json()

    assert report["summary"]["confidence"] == "high"
    signals = _report_section(report, "derived_signals")["signals"]
    assert {signal["signal"] for signal in signals} >= {
        "disk_usage_critical",
        "inode_usage_critical",
        "root_filesystem_read_only",
        "kernel_io_error",
        "read_only_filesystem_detected",
    }
    assert any("filesystem" in candidate["cause"] for candidate in report["root_cause_candidates"])
    assert {action["policy"] for action in report["recommended_actions"]} >= {
        "APPROVAL_REQUIRED",
        "MANUAL_INVESTIGATION",
        "NEVER_AUTO_EXECUTE",
    }
    checklist = _report_section(report, "resolution_checklist")["items"]
    assert {item["component"] for item in checklist} >= {"disk", "runtime-storage", "inode", "kernel"}
    assert any("df -hT" in item["command"] for item in checklist)
    assert any("find /var" in item["command"] and "uniq -c" in item["command"] for item in checklist)


def test_rule_based_rca_covers_planned_node_failure_categories() -> None:
    analyzer = RuleBasedRcaAnalyzer(PolicyEngine())

    report = analyzer.analyze(
        "report-planned-categories",
        EvidenceBundle(
            cluster_id="cluster-1",
            node_name="worker-7",
            alert_name="NetworkUnavailable",
            collectors={
                "kubernetes": {
                    "api_available": False,
                    "node_ready": False,
                    "node_pressure": {"MemoryPressure": "True", "PIDPressure": "True"},
                    "failed_peer_probe_count": 1,
                    "control_plane_peer_connectivity": [
                        {"node": "cp-1", "address": "10.0.0.10", "port": 6443, "ok": False}
                    ],
                    "api_readyz_failed_checks": ["etcd", "poststarthook/start-kube-apiserver-admission-initializer"],
                    "metrics_available": False,
                    "metrics_error": "HTTP 503",
                    "cni_high_restart_pods": [
                        {"namespace": "kube-system", "name": "cni-agent-worker-7", "restart_count": 42}
                    ],
                },
                "systemd": {
                    "kubelet_status": "active",
                    "kubelet_restart_count": 8,
                    "failed_units": ["node-problem-detector.service"],
                },
                "runtime": {
                    "runtime_kind": "crio",
                    "runtime_socket_healthy": False,
                    "runtime_socket_path": "/run/crio/crio.sock",
                    "runtime_socket_latency_ms": 1500,
                },
                "kernel": {
                    "blocked_task_detected": True,
                    "nic_error_detected": True,
                    "oom_detected": True,
                    "kernel_tainted": True,
                },
                "memory": {
                    "usage_percent": 96,
                    "oom_kill_detected": True,
                    "swap_usage_percent": 60,
                    "pressure": {"full": {"avg10": 15}},
                },
                "process": {
                    "pid_usage_percent": 91,
                    "zombie_process_count": 5,
                },
                "network": {
                    "interfaces_down": ["eth1"],
                    "nic_link_flap_detected": True,
                    "conntrack_usage_percent": 93,
                    "physical_interface_rx_drop_total": 1500,
                    "physical_interface_tx_drop_total": 100,
                    "tcp_ext_listen_overflows": 3,
                    "dns_lookup_latency_ms": 900,
                },
                "conntrack": {
                    "count": 930000,
                    "max": 1000000,
                    "available": 70000,
                },
                "cni": {
                    "parse_errors": ["invalid JSON in /etc/cni/net.d/10-cni.conf"],
                    "plugin_errors_detected": True,
                    "mtu_values": [1450, 1500],
                },
                "dns": {
                    "dns_configured": False,
                    "attempts": 5,
                    "timeout_seconds": 5,
                },
            },
        ),
    )

    signals = {item["signal"] for item in _report_section(report.model_dump(mode="json"), "derived_signals")["signals"]}
    assert signals >= {
        "kubernetes_api_unavailable",
        "node_not_ready_condition",
        "node_pressure_condition_active",
        "control_plane_peer_unreachable",
        "apiserver_readyz_failed",
        "node_metrics_unavailable",
        "cni_pod_restarting",
        "kubelet_restarting",
        "systemd_failed_units",
        "container_runtime_socket_unhealthy",
        "container_runtime_socket_latency_high",
        "blocked_task_detected",
        "kernel_nic_error",
        "kernel_oom_detected",
        "kernel_tainted",
        "memory_pressure_critical",
        "oom_kill_detected",
        "swap_usage_high",
        "memory_psi_high",
        "pid_usage_high",
        "zombie_process_detected",
        "interface_down",
        "nic_link_flap",
        "conntrack_near_limit",
        "interface_packet_errors",
        "tcp_error_counters_high",
        "dns_latency_high",
        "cni_config_invalid",
        "cni_plugin_error",
        "cni_mtu_values_inconsistent",
        "dns_unconfigured",
        "dns_resolver_timeout_budget_high",
    }

    action_keys = {action.action_key for action in report.recommended_actions}
    assert action_keys >= {
        "collect_more_evidence",
        "collect_linux_low_level_evidence",
        "inspect_network_state",
        "restart_container_runtime",
        "cordon_node",
        "manual_investigation",
        "open_gitops_pr",
        "manual_hardware_check",
        "reboot_node",
    }
    assert any(action.policy == "NEVER_AUTO_EXECUTE" for action in report.recommended_actions)
    assert any(action.policy == "GITOPS_PR_ONLY" for action in report.recommended_actions)

    checklist = _report_section(report.model_dump(mode="json"), "resolution_checklist")["items"]
    assert {item["component"] for item in checklist} >= {
        "systemd",
        "kubelet",
        "container-runtime",
        "kernel",
        "memory",
        "process",
        "network",
        "cni",
        "kubernetes",
        "dns",
    }


def test_webhook_skips_unknown_cluster(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    response = client.post(
        "/api/webhooks/alertmanager",
        headers=WEBHOOK_HEADERS,
        json={
            "alerts": [
                {
                    "status": "firing",
                    "labels": {
                        "alertname": "DiskPressure",
                        "cluster_id": "cluster-does-not-exist",
                        "node": "worker-1",
                    },
                }
            ]
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["created_jobs"] == []
    assert body["skipped_alerts"] == [
        "DiskPressure: cluster cluster-does-not-exist is not registered"
    ]


def test_agent_register_heartbeat_and_lookup(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["systemd", "disk", "network"],
            "metadata": {"kernel": "6.8.0", "runtime": "containerd"},
        },
    )

    assert register_response.status_code == 201
    registered = register_response.json()
    assert registered["agent_id"].startswith("agent-")
    assert registered["node_token"]
    assert registered["status"] == "registered"
    assert registered["supported_collectors"] == ["systemd", "disk", "network"]

    heartbeat_response = client.post(
        "/api/agents/heartbeat",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": registered["node_token"],
            "status": "healthy",
            "agent_version": "0.1.1",
            "supported_collectors": ["systemd", "disk", "network", "kubelet"],
            "health": {"kubelet": "active", "containerd": "active"},
        },
    )

    assert heartbeat_response.status_code == 200
    heartbeat = heartbeat_response.json()
    assert heartbeat["status"] == "healthy"
    assert heartbeat["agent_version"] == "0.1.1"
    assert heartbeat["last_heartbeat_at"] is not None

    list_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents", headers=admin_headers(client))
    assert list_response.status_code == 200
    assert [agent["node_name"] for agent in list_response.json()] == ["worker-3"]
    assert "node_token" not in list_response.json()[0]

    get_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents/worker-3", headers=admin_headers(client))
    assert get_response.status_code == 200
    agent_detail = get_response.json()
    assert agent_detail["health"]["kubelet"] == "active"
    assert agent_detail["health"]["containerd"] == "active"
    assert agent_detail["health"]["freshness"]["offline"] is False
    assert agent_detail["health"]["freshness"]["offline_after_seconds"] == 180
    assert "node_token" not in agent_detail

    cluster_response = client.get(f"/api/clusters/{cluster['cluster_id']}", headers=admin_headers(client))
    assert cluster_response.status_code == 200
    cluster_after_agent = cluster_response.json()
    assert cluster_after_agent["status"] == "active"
    assert cluster_after_agent["last_seen_at"] is not None


def test_agent_lookup_marks_stale_agents_offline(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("RCA_AGENT_OFFLINE_AFTER_SECONDS", "30")
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    headers = admin_headers(client)
    cluster = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-stale",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
        },
    )

    assert register_response.status_code == 201
    registered = register_response.json()
    registered_at = datetime.fromisoformat(registered["registered_at"])
    if registered_at.tzinfo is None:
        registered_at = registered_at.replace(tzinfo=timezone.utc)

    fresh_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents", headers=headers)
    assert fresh_response.status_code == 200
    fresh_agent = fresh_response.json()[0]
    assert fresh_agent["status"] == "registered"
    assert fresh_agent["health"]["freshness"]["offline"] is False

    monkeypatch.setattr(
        main_module,
        "now_utc",
        lambda: registered_at + timedelta(seconds=31),
    )

    stale_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents", headers=headers)
    assert stale_response.status_code == 200
    stale_agent = stale_response.json()[0]
    assert stale_agent["status"] == "offline"
    freshness = stale_agent["health"]["freshness"]
    assert freshness["last_seen_at"].startswith(registered["registered_at"].removesuffix("Z"))
    assert freshness["age_seconds"] == 31
    assert freshness["offline_after_seconds"] == 30
    assert freshness["offline"] is True

    single_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents/worker-stale", headers=headers)
    assert single_response.status_code == 200
    assert single_response.json()["status"] == "offline"


def test_agent_auth_and_registration_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    invalid_token_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": "wrong-token",
            "agent_version": "0.1.0",
        },
    )
    assert invalid_token_response.status_code == 401

    unregistered_heartbeat_response = client.post(
        "/api/agents/heartbeat",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": "node-token-for-unregistered-agent",
            "status": "healthy",
        },
    )
    assert unregistered_heartbeat_response.status_code == 404

    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
        },
    )
    assert register_response.status_code == 201
    wrong_node_token_response = client.post(
        "/api/agents/heartbeat",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": "wrong-node-token",
            "status": "healthy",
        },
    )
    assert wrong_node_token_response.status_code == 401


def test_evidence_request_poll_and_submit(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["systemd", "disk", "network"],
        },
    )
    node_token = register_response.json()["node_token"]

    create_response = client.post(
        "/api/evidence/requests",
        headers=admin_headers(client),
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "alert_name": "NodeNotReady",
            "requested_collectors": ["systemd", "runtime"],
            "time_range": {
                "from": "2026-06-10T09:10:00+09:00",
                "to": "2026-06-10T09:25:00+09:00",
            },
            "reason": "NodeNotReady fired",
        },
    )

    assert create_response.status_code == 201
    evidence_request = create_response.json()
    assert evidence_request["request_id"].startswith("evidence-request-")
    assert evidence_request["status"] == "pending"

    poll_response = client.post(
        "/api/agents/evidence-requests",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
        },
    )

    assert poll_response.status_code == 200
    assert [item["request_id"] for item in poll_response.json()] == [evidence_request["request_id"]]

    submit_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
            "status": "completed",
            "collectors": {
                "systemd": {"kubelet_status": "active"},
                "runtime": {"containerd_socket_healthy": True},
            },
        },
    )

    assert submit_response.status_code == 200
    completed_request = submit_response.json()
    assert completed_request["status"] == "completed"
    assert completed_request["evidence_id"].startswith("evidence-")
    assert completed_request["completed_at"] is not None

    evidence_response = client.get(f"/api/evidence/{completed_request['evidence_id']}", headers=admin_headers(client))
    assert evidence_response.status_code == 200
    evidence = evidence_response.json()
    assert evidence["alert_name"] == "NodeNotReady"
    assert evidence["collectors"]["systemd"]["kubelet_status"] == "active"

    jobs_response = client.get("/api/rca/jobs", headers=admin_headers(client))
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    job = jobs[0]
    assert job["status"] == "completed"
    assert job["evidence_id"] == completed_request["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{job['report_id']}", headers=admin_headers(client))
    assert report_response.status_code == 200
    report = report_response.json()
    assert report["trigger"]["alert_name"] == "NodeNotReady"
    assert report["scope"]["nodes"] == ["worker-3"]

    poll_after_submit_response = client.post(
        "/api/agents/evidence-requests",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
        },
    )
    assert poll_after_submit_response.status_code == 200
    assert poll_after_submit_response.json() == []


def test_backend_initiated_collection_creates_evidence_request_and_report(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    headers = admin_headers(client)
    cluster = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    no_agent_response = client.post(
        f"/api/clusters/{cluster['cluster_id']}/collection-runs",
        headers=headers,
        json={"confirmed": True},
    )
    assert no_agent_response.status_code == 409

    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["node", "systemd", "runtime", "kernel", "network", "conntrack"],
        },
    )
    node_token = register_response.json()["node_token"]

    unconfirmed = client.post(
        f"/api/clusters/{cluster['cluster_id']}/collection-runs",
        headers=headers,
        json={"confirmed": False},
    )
    assert unconfirmed.status_code == 400

    collection_response = client.post(
        f"/api/clusters/{cluster['cluster_id']}/collection-runs",
        headers=headers,
        json={
            "confirmed": True,
            "reason": "manual scan without Prometheus",
            "context": {"source": "web-console"},
        },
    )
    assert collection_response.status_code == 201
    collection = collection_response.json()
    assert collection["requested_nodes"] == ["worker-3"]
    assert collection["skipped_nodes"] == []
    evidence_request = collection["created_evidence_requests"][0]
    assert evidence_request["alert_name"] == "BackendManualCollection"
    assert "conntrack" in evidence_request["requested_collectors"]
    assert evidence_request["context"]["trigger"] == "backend_collection"

    poll_response = client.post(
        "/api/agents/evidence-requests",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
        },
    )
    assert [item["request_id"] for item in poll_response.json()] == [evidence_request["request_id"]]

    submit_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_token,
            "status": "completed",
            "collectors": {
                "systemd": {"kubelet_status": "active"},
                "runtime": {"containerd_socket_healthy": True},
                "kernel": {"recent_errors": []},
            },
        },
    )
    assert submit_response.status_code == 200

    reports = client.get("/api/rca/reports", headers=headers).json()
    assert len(reports) == 1
    assert reports[0]["trigger"]["alert_name"] == "BackendManualCollection"
    assert reports[0]["scope"]["nodes"] == ["worker-3"]


def test_rca_action_execution_requests_read_only_evidence(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    headers = admin_headers(client)
    cluster = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["node", "systemd", "runtime", "kernel", "network", "conntrack"],
        },
    )
    evidence_request = client.post(
        "/api/evidence/requests",
        headers=headers,
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "alert_name": "NodeNotReady",
            "requested_collectors": ["systemd", "runtime"],
            "reason": "NodeNotReady fired",
        },
    ).json()
    submit_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": register_response.json()["node_token"],
            "status": "completed",
            "collectors": {
                "systemd": {"kubelet_status": "active"},
                "runtime": {"containerd_socket_healthy": False},
            },
        },
    )
    assert submit_response.status_code == 200

    report = client.get("/api/rca/reports", headers=headers).json()[0]
    action_index = next(
        index
        for index, action in enumerate(report["recommended_actions"])
        if action["action_key"] == "collect_more_evidence"
    )

    unconfirmed = client.post(
        f"/api/rca/reports/{report['report_id']}/actions/{action_index}/execute",
        headers=headers,
        json={"confirmed": False},
    )
    assert unconfirmed.status_code == 400

    execute_response = client.post(
        f"/api/rca/reports/{report['report_id']}/actions/{action_index}/execute",
        headers=headers,
        json={"confirmed": True, "note": "collect follow-up diagnostics"},
    )

    assert execute_response.status_code == 200
    execution = execute_response.json()
    assert execution["status"] == "accepted"
    assert execution["execution_started"] is True
    assert execution["evidence_request"]["status"] == "pending"
    assert execution["evidence_request"]["context"]["report_id"] == report["report_id"]
    assert "conntrack" in execution["evidence_request"]["requested_collectors"]


def test_rca_action_execution_blocks_non_auto_safe_actions(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    headers = admin_headers(client)
    cluster = client.post(
        "/api/clusters",
        headers=headers,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    register_response = client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["systemd", "runtime"],
        },
    )
    evidence_request = client.post(
        "/api/evidence/requests",
        headers=headers,
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "alert_name": "NodeNotReady",
            "requested_collectors": ["systemd", "runtime"],
        },
    ).json()
    client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": register_response.json()["node_token"],
            "status": "completed",
            "collectors": {
                "systemd": {"containerd_status": "failed"},
                "runtime": {"containerd_socket_healthy": False},
            },
        },
    )
    report = client.get("/api/rca/reports", headers=headers).json()[0]
    action_index = next(
        index
        for index, action in enumerate(report["recommended_actions"])
        if action["policy"] == "APPROVAL_REQUIRED"
    )

    execute_response = client.post(
        f"/api/rca/reports/{report['report_id']}/actions/{action_index}/execute",
        headers=headers,
        json={"confirmed": True},
    )

    assert execute_response.status_code == 200
    execution = execute_response.json()
    assert execution["status"] == "approval_required"
    assert execution["execution_started"] is False
    assert execution["evidence_request"] is None


def test_evidence_request_failure_and_wrong_agent_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    node_tokens = {}
    for node_name in ("worker-3", "worker-4"):
        register_response = client.post(
            "/api/agents/register",
            json={
                "cluster_id": cluster["cluster_id"],
                "node_name": node_name,
                "agent_token": cluster["bootstrap_token"],
                "agent_version": "0.1.0",
                "supported_collectors": ["systemd"],
            },
        )
        node_tokens[node_name] = register_response.json()["node_token"]

    evidence_request = client.post(
        "/api/evidence/requests",
        headers=admin_headers(client),
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "alert_name": "KubeletDown",
            "requested_collectors": ["systemd"],
        },
    ).json()

    wrong_agent_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-4",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_tokens["worker-4"],
            "status": "completed",
            "collectors": {"systemd": {"kubelet_status": "failed"}},
        },
    )
    assert wrong_agent_response.status_code == 403

    failed_response = client.post(
        "/api/agents/evidence-responses",
        json={
            "request_id": evidence_request["request_id"],
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "node_token": node_tokens["worker-3"],
            "status": "failed",
            "error_message": "journalctl timed out",
        },
    )
    assert failed_response.status_code == 200
    failed_request = failed_response.json()
    assert failed_request["status"] == "failed"
    assert failed_request["error_message"] == "journalctl timed out"
    assert failed_request["evidence_id"] is None

    assert client.get("/api/rca/jobs", headers=admin_headers(client)).json() == []
    assert client.get("/api/rca/reports", headers=admin_headers(client)).json() == []


def test_sqlalchemy_store_persists_data_across_app_instances(tmp_path) -> None:
    database_url = f"sqlite:///{tmp_path / 'persistent.db'}"
    first_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))

    cluster = first_client.post(
        "/api/clusters",
        headers=admin_headers(first_client),
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    second_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))
    response = second_client.get(f"/api/clusters/{cluster['cluster_id']}", headers=admin_headers(second_client))

    assert response.status_code == 200
    assert response.json()["name"] == "prod-cluster"


def test_readiness_endpoint_checks_database(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'ready.db'}", auto_create_tables=True))

    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "database": "reachable",
        "llm_provider": "disabled",
    }


def test_runtime_settings_are_bounded(monkeypatch) -> None:
    monkeypatch.setenv("RCA_SESSION_TTL_HOURS", "0")
    monkeypatch.setenv("RCA_LLM_TIMEOUT_SECONDS", "0")
    monkeypatch.setenv("RCA_LLM_MAX_OUTPUT_TOKENS", "999999")

    settings = load_settings()

    assert settings.session_ttl_hours == 1
    assert settings.llm.timeout_seconds == 1.0
    assert settings.llm.max_output_tokens == 8000


def test_database_url_normalization() -> None:
    assert normalize_database_url("postgres://u:p@localhost:5432/rca") == (
        "postgresql+psycopg://u:p@localhost:5432/rca"
    )
    assert normalize_database_url("postgresql://u:p@localhost:5432/rca") == (
        "postgresql+psycopg://u:p@localhost:5432/rca"
    )
    assert normalize_database_url("mariadb://u:p@localhost:3306/rca") == (
        "mysql+pymysql://u:p@localhost:3306/rca"
    )
    assert normalize_database_url("mysql://u:p@localhost:3306/rca") == (
        "mysql+pymysql://u:p@localhost:3306/rca"
    )


def test_schema_compiles_for_postgresql_and_mariadb_dialects() -> None:
    postgres_ddl = "\n".join(
        str(CreateTable(table).compile(dialect=postgresql.dialect()))
        for table in Base.metadata.sorted_tables
    )
    mariadb_ddl = "\n".join(
        str(CreateTable(table).compile(dialect=mysql.dialect()))
        for table in Base.metadata.sorted_tables
    )

    assert "CREATE TABLE clusters" in postgres_ddl
    assert "CREATE TABLE clusters" in mariadb_ddl
    assert "rca_reports" in postgres_ddl
    assert "rca_reports" in mariadb_ddl
    assert "node_agents" in postgres_ddl
    assert "node_agents" in mariadb_ddl
    assert "node_token_hash" in postgres_ddl
    assert "node_token_hash" in mariadb_ddl
    assert "evidence_requests" in postgres_ddl
    assert "evidence_requests" in mariadb_ddl
    assert "user_accounts" in postgres_ddl
    assert "user_accounts" in mariadb_ddl
    assert "user_sessions" in postgres_ddl
    assert "user_sessions" in mariadb_ddl


def test_alembic_initial_migration_creates_schema(tmp_path, monkeypatch) -> None:
    database_url = f"sqlite:///{tmp_path / 'alembic.db'}"
    monkeypatch.setenv("RCA_DATABASE_URL", database_url)

    command.upgrade(Config("alembic.ini"), "head")

    engine = create_db_engine(database_url)
    tables = set(inspect(engine).get_table_names())
    node_agent_columns = {column["name"] for column in inspect(engine).get_columns("node_agents")}
    assert {
        "alembic_version",
        "clusters",
        "node_agents",
        "evidence_requests",
        "evidence_bundles",
        "rca_reports",
        "rca_jobs",
        "user_accounts",
        "user_sessions",
    } <= tables
    assert "node_token_hash" in node_agent_columns

    client = TestClient(create_app(database_url=database_url, auto_create_tables=False))
    response = client.post(
        "/api/clusters",
        headers=admin_headers(client),
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert response.status_code == 201


def _report_section(report: dict, section_type: str) -> dict:
    for section in report["evidence"]:
        if section.get("type") == section_type:
            return section
    raise AssertionError(f"report section not found: {section_type}")
