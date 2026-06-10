from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


class AgentClientError(RuntimeError):
    pass


@dataclass(frozen=True)
class AgentClient:
    backend_url: str
    cluster_id: str
    node_name: str
    agent_token: str
    timeout_seconds: float = 10

    def register(
        self,
        agent_version: str,
        supported_collectors: list[str],
        metadata: dict[str, Any],
    ) -> dict[str, Any]:
        return self._post(
            "/api/agents/register",
            {
                "cluster_id": self.cluster_id,
                "node_name": self.node_name,
                "agent_token": self.agent_token,
                "agent_version": agent_version,
                "supported_collectors": supported_collectors,
                "metadata": metadata,
            },
        )

    def heartbeat(
        self,
        agent_version: str,
        supported_collectors: list[str],
        health: dict[str, Any],
        status: str = "healthy",
    ) -> dict[str, Any]:
        return self._post(
            "/api/agents/heartbeat",
            {
                "cluster_id": self.cluster_id,
                "node_name": self.node_name,
                "agent_token": self.agent_token,
                "status": status,
                "agent_version": agent_version,
                "supported_collectors": supported_collectors,
                "health": health,
            },
        )

    def poll_evidence_requests(self, limit: int = 10) -> list[dict[str, Any]]:
        response = self._post(
            "/api/agents/evidence-requests",
            {
                "cluster_id": self.cluster_id,
                "node_name": self.node_name,
                "agent_token": self.agent_token,
                "limit": limit,
            },
        )
        if not isinstance(response, list):
            raise AgentClientError("backend returned non-list evidence request response")
        return response

    def submit_evidence_response(
        self,
        request_id: str,
        status: str,
        collectors: dict[str, Any] | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any]:
        payload = {
            "request_id": request_id,
            "cluster_id": self.cluster_id,
            "node_name": self.node_name,
            "agent_token": self.agent_token,
            "status": status,
            "collectors": collectors or {},
            "error_message": error_message,
        }
        return self._post("/api/agents/evidence-responses", payload)

    def _post(self, path: str, payload: dict[str, Any]) -> Any:
        url = self.backend_url.rstrip("/") + path
        data = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=data,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "cluster-infra-rca-agent",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                body = response.read().decode("utf-8")
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:2000]
            raise AgentClientError(f"backend returned HTTP {exc.code} for {path}: {body}") from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
            raise AgentClientError(f"failed to call backend {path}: {exc}") from exc
        except json.JSONDecodeError as exc:
            raise AgentClientError(f"backend returned invalid JSON for {path}") from exc
