from fastapi.testclient import TestClient
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect
from sqlalchemy.dialects import mysql, postgresql
from sqlalchemy.schema import CreateTable

import backend.app.db_models  # noqa: F401
from backend.app.config import normalize_database_url
from backend.app.database import Base, create_db_engine
from backend.app.main import create_app


ADMIN_HEADERS = {"X-Admin-Token": "dev-admin-approval-token"}


def test_cluster_registration_and_install_command(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    assert client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).status_code == 401

    create_response = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert create_response.status_code == 201
    cluster = create_response.json()
    assert cluster["cluster_id"].startswith("cluster-")
    assert cluster["status"] == "agent_pending"

    assert client.get(f"/api/clusters/{cluster['cluster_id']}/install-command").status_code == 401

    install_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/install-command",
        headers=ADMIN_HEADERS,
    )

    assert install_response.status_code == 200
    install = install_response.json()
    assert install["namespace"] == "rca-system"
    assert any("agent-token" in command for command in install["commands"])

    list_response = client.get("/api/clusters", headers=ADMIN_HEADERS)
    assert list_response.status_code == 200
    assert "bootstrap_token" not in list_response.json()[0]


def test_web_console_static_assets_are_served(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    index_response = client.get("/")
    script_response = client.get("/static/app.js")
    style_response = client.get("/static/styles.css")

    assert index_response.status_code == 200
    assert "Cluster Infrastructure Control Plane" in index_response.text
    assert script_response.status_code == 200
    assert "loadPendingUsers" in script_response.text
    assert "sessionStorage" in script_response.text
    assert "X-Admin-Token" in script_response.text
    assert style_response.status_code == 200
    assert "auth-panel" in style_response.text
    assert index_response.headers["X-Frame-Options"] == "DENY"
    assert "frame-ancestors 'none'" in index_response.headers["Content-Security-Policy"]


def test_cluster_install_command_can_use_generated_manifest_url(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    install_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/install-command",
        headers=ADMIN_HEADERS,
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


def test_agent_manifest_generation_and_validation(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    manifest_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={
            "backend_url": "https://rca.example.com/",
            "image": "ghcr.io/acme/cluster-infra-rca-agent:v1",
            "namespace": "custom-rca",
            "poll_interval_seconds": 30,
            "http_timeout_seconds": 20,
            "command_timeout_seconds": 7,
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
    }

    daemonset = items["DaemonSet"]
    container = daemonset["spec"]["template"]["spec"]["containers"][0]
    assert container["image"] == "ghcr.io/acme/cluster-infra-rca-agent:v1"
    assert container["command"] == ["python", "-m", "node_agent.main"]
    assert {"name": "host-root", "mountPath": "/host/root", "readOnly": True} in container["volumeMounts"]
    assert {"name": "host-root", "hostPath": {"path": "/"}} in daemonset["spec"]["template"]["spec"]["volumes"]

    env = {item["name"]: item for item in container["env"]}
    assert env["BACKEND_URL"]["valueFrom"]["configMapKeyRef"]["name"] == "cluster-infra-rca-agent-config"
    assert env["CLUSTER_ID"]["valueFrom"]["secretKeyRef"]["key"] == "cluster-id"
    assert env["AGENT_TOKEN"]["valueFrom"]["secretKeyRef"]["key"] == "agent-token"
    assert cluster["bootstrap_token"] not in str(manifest)

    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "not-a-url"},
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com", "namespace": "Bad_Namespace"},
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com", "image": "bad image"},
    ).status_code == 422
    assert client.get(
        f"/api/clusters/{cluster['cluster_id']}/agent-manifest",
        params={"backend_url": "https://rca.example.com", "poll_interval_seconds": 1},
    ).status_code == 422
    assert client.get(
        "/api/clusters/cluster-does-not-exist/agent-manifest",
        params={"backend_url": "https://rca.example.com"},
    ).status_code == 404


def test_alertmanager_webhook_creates_rca_report(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    webhook_response = client.post(
        "/api/webhooks/alertmanager",
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

    report_response = client.get(f"/api/rca/reports/{result['created_reports'][0]}", headers=ADMIN_HEADERS)

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


def test_signup_requires_admin_approval(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    signup_response = client.post(
        "/api/auth/signup",
        json={
            "email": "Operator@Example.com",
            "full_name": "Cluster Operator",
            "password": "safe-password-123",
            "requested_role": "operator",
            "reason": "Need access to RCA reports",
        },
    )

    assert signup_response.status_code == 201
    signup = signup_response.json()
    assert signup["user_id"].startswith("user-")
    assert signup["email"] == "operator@example.com"
    assert signup["status"] == "pending_approval"
    assert signup["requested_role"] == "operator"
    assert signup["role"] is None
    assert "password" not in signup
    assert "password_hash" not in signup

    duplicate_response = client.post(
        "/api/auth/signup",
        json={
            "email": "operator@example.com",
            "full_name": "Duplicate Operator",
            "password": "safe-password-456",
        },
    )
    assert duplicate_response.status_code == 409

    assert client.get(
        "/api/admin/users",
        headers={"X-Admin-Token": "wrong"},
        params={"status": "pending_approval"},
    ).status_code == 401

    pending_response = client.get(
        "/api/admin/users",
        headers={"X-Admin-Token": "dev-admin-approval-token"},
        params={"status": "pending_approval"},
    )
    assert pending_response.status_code == 200
    assert [user["user_id"] for user in pending_response.json()] == [signup["user_id"]]

    approval_response = client.post(
        f"/api/admin/users/{signup['user_id']}/approval",
        headers={"X-Admin-Token": "dev-admin-approval-token"},
        json={
            "decision": "approve",
            "role": "operator",
            "note": "approved for MVP validation",
        },
    )
    assert approval_response.status_code == 200
    approved = approval_response.json()
    assert approved["status"] == "active"
    assert approved["role"] == "operator"
    assert approved["approved_by"] == "platform-admin"
    assert approved["approved_at"] is not None

    approve_again_response = client.post(
        f"/api/admin/users/{signup['user_id']}/approval",
        headers={"X-Admin-Token": "dev-admin-approval-token"},
        json={"decision": "approve"},
    )
    assert approve_again_response.status_code == 409


def test_login_session_and_role_based_access(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    viewer_signup = client.post(
        "/api/auth/signup",
        json={
            "email": "viewer@example.com",
            "full_name": "Report Viewer",
            "password": "safe-password-123",
            "requested_role": "viewer",
        },
    ).json()

    pending_login = client.post(
        "/api/auth/login",
        json={"email": "viewer@example.com", "password": "safe-password-123"},
    )
    assert pending_login.status_code == 403

    client.post(
        f"/api/admin/users/{viewer_signup['user_id']}/approval",
        headers=ADMIN_HEADERS,
        json={"decision": "approve", "role": "viewer"},
    )

    wrong_password = client.post(
        "/api/auth/login",
        json={"email": "viewer@example.com", "password": "wrong-password"},
    )
    assert wrong_password.status_code == 401

    viewer_login = client.post(
        "/api/auth/login",
        json={"email": "viewer@example.com", "password": "safe-password-123"},
    )
    assert viewer_login.status_code == 200
    viewer_session = viewer_login.json()
    assert viewer_session["token_type"] == "bearer"
    assert viewer_session["access_token"]
    assert viewer_session["user"]["role"] == "viewer"
    assert "password" not in viewer_session["user"]
    viewer_headers = {"Authorization": f"Bearer {viewer_session['access_token']}"}

    me_response = client.get("/api/auth/me", headers=viewer_headers)
    assert me_response.status_code == 200
    assert me_response.json()["email"] == "viewer@example.com"

    assert client.get("/api/clusters", headers=viewer_headers).status_code == 200
    assert client.post(
        "/api/clusters",
        headers=viewer_headers,
        json={"name": "viewer-blocked", "environment": "dev"},
    ).status_code == 403

    logout_response = client.post("/api/auth/logout", headers=viewer_headers)
    assert logout_response.status_code == 200
    assert logout_response.json() == {"revoked": True}
    assert client.get("/api/auth/me", headers=viewer_headers).status_code == 401

    operator_signup = client.post(
        "/api/auth/signup",
        json={
            "email": "operator@example.com",
            "full_name": "Cluster Operator",
            "password": "safe-password-456",
            "requested_role": "operator",
        },
    ).json()
    client.post(
        f"/api/admin/users/{operator_signup['user_id']}/approval",
        headers=ADMIN_HEADERS,
        json={"decision": "approve", "role": "operator"},
    )
    operator_login = client.post(
        "/api/auth/login",
        json={"email": "operator@example.com", "password": "safe-password-456"},
    ).json()
    operator_headers = {"Authorization": f"Bearer {operator_login['access_token']}"}
    create_cluster = client.post(
        "/api/clusters",
        headers=operator_headers,
        json={"name": "operator-created", "environment": "stage"},
    )
    assert create_cluster.status_code == 201
    assert create_cluster.json()["bootstrap_token"]


def test_alertmanager_webhook_creates_evidence_request_for_registered_agent(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
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
        "systemd",
        "runtime",
        "kernel",
        "network",
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
                "systemd": {"kubelet_status": "restarting", "kubelet_restart_count": 7},
                "runtime": {"containerd_socket_healthy": False},
                "network": {"conntrack_usage_percent": 91},
            },
        },
    )

    assert submit_response.status_code == 200
    jobs_response = client.get("/api/rca/jobs", headers=ADMIN_HEADERS)
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    assert jobs[0]["alert_name"] == "NodeNotReady"
    assert jobs[0]["evidence_id"] == submit_response.json()["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{jobs[0]['report_id']}", headers=ADMIN_HEADERS)
    assert report_response.status_code == 200
    report = report_response.json()
    signals = _report_section(report, "derived_signals")["signals"]
    assert {signal["signal"] for signal in signals} >= {
        "kubelet_unit_unhealthy",
        "containerd_socket_unhealthy",
        "conntrack_near_limit",
    }
    checklist = _report_section(report, "resolution_checklist")["items"]
    assert {item["component"] for item in checklist} >= {"kubelet", "containerd", "network"}


def test_rca_report_identifies_disk_and_kernel_evidence_for_resolution(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
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
        headers=ADMIN_HEADERS,
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
    job = client.get("/api/rca/jobs", headers=ADMIN_HEADERS).json()[0]
    report = client.get(f"/api/rca/reports/{job['report_id']}", headers=ADMIN_HEADERS).json()

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
    assert {item["component"] for item in checklist} >= {"disk", "kernel"}


def test_webhook_skips_unknown_cluster(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    response = client.post(
        "/api/webhooks/alertmanager",
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
        headers=ADMIN_HEADERS,
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

    list_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents", headers=ADMIN_HEADERS)
    assert list_response.status_code == 200
    assert [agent["node_name"] for agent in list_response.json()] == ["worker-3"]
    assert "node_token" not in list_response.json()[0]

    get_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents/worker-3", headers=ADMIN_HEADERS)
    assert get_response.status_code == 200
    assert get_response.json()["health"] == {"kubelet": "active", "containerd": "active"}
    assert "node_token" not in get_response.json()

    cluster_response = client.get(f"/api/clusters/{cluster['cluster_id']}", headers=ADMIN_HEADERS)
    assert cluster_response.status_code == 200
    cluster_after_agent = cluster_response.json()
    assert cluster_after_agent["status"] == "active"
    assert cluster_after_agent["last_seen_at"] is not None


def test_agent_auth_and_registration_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
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
        headers=ADMIN_HEADERS,
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
        headers=ADMIN_HEADERS,
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

    evidence_response = client.get(f"/api/evidence/{completed_request['evidence_id']}", headers=ADMIN_HEADERS)
    assert evidence_response.status_code == 200
    evidence = evidence_response.json()
    assert evidence["alert_name"] == "NodeNotReady"
    assert evidence["collectors"]["systemd"]["kubelet_status"] == "active"

    jobs_response = client.get("/api/rca/jobs", headers=ADMIN_HEADERS)
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    job = jobs[0]
    assert job["status"] == "completed"
    assert job["evidence_id"] == completed_request["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{job['report_id']}", headers=ADMIN_HEADERS)
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


def test_evidence_request_failure_and_wrong_agent_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
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
        headers=ADMIN_HEADERS,
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

    assert client.get("/api/rca/jobs", headers=ADMIN_HEADERS).json() == []
    assert client.get("/api/rca/reports", headers=ADMIN_HEADERS).json() == []


def test_sqlalchemy_store_persists_data_across_app_instances(tmp_path) -> None:
    database_url = f"sqlite:///{tmp_path / 'persistent.db'}"
    first_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))

    cluster = first_client.post(
        "/api/clusters",
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    second_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))
    response = second_client.get(f"/api/clusters/{cluster['cluster_id']}", headers=ADMIN_HEADERS)

    assert response.status_code == 200
    assert response.json()["name"] == "prod-cluster"


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
        headers=ADMIN_HEADERS,
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert response.status_code == 201


def _report_section(report: dict, section_type: str) -> dict:
    for section in report["evidence"]:
        if section.get("type") == section_type:
            return section
    raise AssertionError(f"report section not found: {section_type}")
