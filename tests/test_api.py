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


def test_cluster_registration_and_install_command(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))

    create_response = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert create_response.status_code == 201
    cluster = create_response.json()
    assert cluster["cluster_id"].startswith("cluster-")
    assert cluster["status"] == "agent_pending"

    install_response = client.get(f"/api/clusters/{cluster['cluster_id']}/install-command")

    assert install_response.status_code == 200
    install = install_response.json()
    assert install["namespace"] == "rca-system"
    assert any("agent-token" in command for command in install["commands"])


def test_cluster_install_command_can_use_generated_manifest_url(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    install_response = client.get(
        f"/api/clusters/{cluster['cluster_id']}/install-command",
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

    report_response = client.get(f"/api/rca/reports/{result['created_reports'][0]}")

    assert report_response.status_code == 200
    report = report_response.json()
    assert report["summary"]["confidence"] == "high"
    assert report["scope"]["nodes"] == ["worker-3"]
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


def test_alertmanager_webhook_creates_evidence_request_for_registered_agent(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["node", "systemd", "runtime", "kernel", "network"],
        },
    )

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
            "status": "completed",
            "collectors": {
                "systemd": {"kubelet_status": "restarting", "kubelet_restart_count": 7},
                "runtime": {"containerd_socket_healthy": False},
                "network": {"conntrack_usage_percent": 91},
            },
        },
    )

    assert submit_response.status_code == 200
    jobs_response = client.get("/api/rca/jobs")
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    assert jobs[0]["alert_name"] == "NodeNotReady"
    assert jobs[0]["evidence_id"] == submit_response.json()["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{jobs[0]['report_id']}")
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
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["disk", "kernel", "systemd"],
        },
    )
    evidence_request = client.post(
        "/api/evidence/requests",
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
    job = client.get("/api/rca/jobs").json()[0]
    report = client.get(f"/api/rca/reports/{job['report_id']}").json()

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
    assert registered["status"] == "registered"
    assert registered["supported_collectors"] == ["systemd", "disk", "network"]

    heartbeat_response = client.post(
        "/api/agents/heartbeat",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
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

    list_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents")
    assert list_response.status_code == 200
    assert [agent["node_name"] for agent in list_response.json()] == ["worker-3"]

    get_response = client.get(f"/api/clusters/{cluster['cluster_id']}/agents/worker-3")
    assert get_response.status_code == 200
    assert get_response.json()["health"] == {"kubelet": "active", "containerd": "active"}

    cluster_response = client.get(f"/api/clusters/{cluster['cluster_id']}")
    assert cluster_response.status_code == 200
    cluster_after_agent = cluster_response.json()
    assert cluster_after_agent["status"] == "active"
    assert cluster_after_agent["last_seen_at"] is not None


def test_agent_auth_and_registration_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
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
            "status": "healthy",
        },
    )
    assert unregistered_heartbeat_response.status_code == 404


def test_evidence_request_poll_and_submit(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    client.post(
        "/api/agents/register",
        json={
            "cluster_id": cluster["cluster_id"],
            "node_name": "worker-3",
            "agent_token": cluster["bootstrap_token"],
            "agent_version": "0.1.0",
            "supported_collectors": ["systemd", "disk", "network"],
        },
    )

    create_response = client.post(
        "/api/evidence/requests",
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

    evidence_response = client.get(f"/api/evidence/{completed_request['evidence_id']}")
    assert evidence_response.status_code == 200
    evidence = evidence_response.json()
    assert evidence["alert_name"] == "NodeNotReady"
    assert evidence["collectors"]["systemd"]["kubelet_status"] == "active"

    jobs_response = client.get("/api/rca/jobs")
    assert jobs_response.status_code == 200
    jobs = jobs_response.json()
    assert len(jobs) == 1
    job = jobs[0]
    assert job["status"] == "completed"
    assert job["evidence_id"] == completed_request["evidence_id"]

    report_response = client.get(f"/api/rca/reports/{job['report_id']}")
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
        },
    )
    assert poll_after_submit_response.status_code == 200
    assert poll_after_submit_response.json() == []


def test_evidence_request_failure_and_wrong_agent_errors(tmp_path) -> None:
    client = TestClient(create_app(database_url=f"sqlite:///{tmp_path / 'test.db'}", auto_create_tables=True))
    cluster = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()
    for node_name in ("worker-3", "worker-4"):
        client.post(
            "/api/agents/register",
            json={
                "cluster_id": cluster["cluster_id"],
                "node_name": node_name,
                "agent_token": cluster["bootstrap_token"],
                "agent_version": "0.1.0",
                "supported_collectors": ["systemd"],
            },
        )

    evidence_request = client.post(
        "/api/evidence/requests",
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
            "status": "failed",
            "error_message": "journalctl timed out",
        },
    )
    assert failed_response.status_code == 200
    failed_request = failed_response.json()
    assert failed_request["status"] == "failed"
    assert failed_request["error_message"] == "journalctl timed out"
    assert failed_request["evidence_id"] is None

    assert client.get("/api/rca/jobs").json() == []
    assert client.get("/api/rca/reports").json() == []


def test_sqlalchemy_store_persists_data_across_app_instances(tmp_path) -> None:
    database_url = f"sqlite:///{tmp_path / 'persistent.db'}"
    first_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))

    cluster = first_client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    ).json()

    second_client = TestClient(create_app(database_url=database_url, auto_create_tables=True))
    response = second_client.get(f"/api/clusters/{cluster['cluster_id']}")

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
    assert "evidence_requests" in postgres_ddl
    assert "evidence_requests" in mariadb_ddl


def test_alembic_initial_migration_creates_schema(tmp_path, monkeypatch) -> None:
    database_url = f"sqlite:///{tmp_path / 'alembic.db'}"
    monkeypatch.setenv("RCA_DATABASE_URL", database_url)

    command.upgrade(Config("alembic.ini"), "head")

    engine = create_db_engine(database_url)
    tables = set(inspect(engine).get_table_names())
    assert {
        "alembic_version",
        "clusters",
        "node_agents",
        "evidence_requests",
        "evidence_bundles",
        "rca_reports",
        "rca_jobs",
    } <= tables

    client = TestClient(create_app(database_url=database_url, auto_create_tables=False))
    response = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert response.status_code == 201


def _report_section(report: dict, section_type: str) -> dict:
    for section in report["evidence"]:
        if section.get("type") == section_type:
            return section
    raise AssertionError(f"report section not found: {section_type}")
