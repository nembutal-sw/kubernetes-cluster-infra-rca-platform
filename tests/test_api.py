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
    assert result["created_jobs"][0]["evidence_id"].startswith("evidence-")

    report_response = client.get(f"/api/rca/reports/{result['created_reports'][0]}")

    assert report_response.status_code == 200
    report = report_response.json()
    assert report["summary"]["confidence"] == "medium"
    assert report["scope"]["nodes"] == ["worker-3"]
    assert {action["policy"] for action in report["recommended_actions"]} == {
        "AUTO_SAFE",
        "APPROVAL_REQUIRED",
        "GITOPS_PR_ONLY",
    }


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


def test_alembic_initial_migration_creates_schema(tmp_path, monkeypatch) -> None:
    database_url = f"sqlite:///{tmp_path / 'alembic.db'}"
    monkeypatch.setenv("RCA_DATABASE_URL", database_url)

    command.upgrade(Config("alembic.ini"), "head")

    engine = create_db_engine(database_url)
    tables = set(inspect(engine).get_table_names())
    assert {"alembic_version", "clusters", "node_agents", "evidence_bundles", "rca_reports", "rca_jobs"} <= tables

    client = TestClient(create_app(database_url=database_url, auto_create_tables=False))
    response = client.post(
        "/api/clusters",
        json={"name": "prod-cluster", "environment": "prod"},
    )

    assert response.status_code == 201
