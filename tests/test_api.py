from fastapi.testclient import TestClient

from backend.app.main import create_app


def test_cluster_registration_and_install_command() -> None:
    client = TestClient(create_app())

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


def test_alertmanager_webhook_creates_rca_report() -> None:
    client = TestClient(create_app())
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


def test_webhook_skips_unknown_cluster() -> None:
    client = TestClient(create_app())

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

