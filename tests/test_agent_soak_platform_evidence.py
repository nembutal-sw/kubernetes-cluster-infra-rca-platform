import pytest

from agent_soak.platform_evidence import PlatformEvidenceClient, PlatformEvidenceError


def client() -> PlatformEvidenceClient:
    return PlatformEvidenceClient(
        "https://platform.example.test",
        "cluster-1",
        "non-secret-test-token",
    )


def test_collect_fleet_creates_and_resolves_one_request_per_agent(monkeypatch) -> None:
    platform = client()
    calls = []

    def request(self, method, path, payload=None):
        calls.append((method, path, payload))
        if path.endswith("/collection-runs"):
            return {
                "created_evidence_requests": [
                    {"node_name": "worker-a", "request_id": "request-a"},
                    {"node_name": "worker-b", "request_id": "request-b"},
                ],
                "skipped_nodes": [],
            }
        if path.endswith("request-a"):
            return {"status": "completed", "evidence_id": "evidence-a"}
        if path.endswith("request-b"):
            return {"status": "completed", "evidence_id": "evidence-b"}
        return {
            "collectors": {
                "node": {"_schema_version": "collector-evidence/v1", "status": "ok"},
            }
        }

    monkeypatch.setattr(PlatformEvidenceClient, "_request_json", request)
    results = platform.collect_fleet(
        [
            {"node_name": "worker-a", "target_id": "1111111111111111"},
            {"node_name": "worker-b", "target_id": "2222222222222222"},
        ],
        ["node"],
        iteration=7,
        completion_timeout_seconds=10,
    )

    assert [item["target_id"] for item in results] == ["1111111111111111", "2222222222222222"]
    assert all(item["success"] for item in results)
    assert calls[0][2]["context"] == {
        "source": "agent_fleet_burn_in",
        "read_only": True,
        "iteration": 7,
    }
    assert calls[0][2]["node_names"] == ["worker-a", "worker-b"]


def test_collect_fleet_rejects_partial_or_skipped_assignment(monkeypatch) -> None:
    platform = client()
    monkeypatch.setattr(
        PlatformEvidenceClient,
        "_request_json",
        lambda self, *args, **kwargs: {
            "created_evidence_requests": [],
            "skipped_nodes": ["worker-a: agent offline"],
        },
    )

    with pytest.raises(PlatformEvidenceError, match="every fleet target"):
        platform.collect_fleet(
            [{"node_name": "worker-a", "target_id": "1111111111111111"}],
            ["node"],
            iteration=1,
            completion_timeout_seconds=10,
        )


def test_platform_client_rejects_credentials_in_url_and_never_serializes_token() -> None:
    with pytest.raises(ValueError, match="credentials"):
        PlatformEvidenceClient("https://user:password@example.test", "cluster-1", "token")

    platform = client()
    assert "non-secret-test-token" not in repr(platform)
    assert not hasattr(platform, "__dict__")
