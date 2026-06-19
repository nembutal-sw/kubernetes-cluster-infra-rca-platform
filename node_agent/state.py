from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any


class AgentStateStore:
    def __init__(
        self,
        state_dir: Path,
        cluster_id: str,
        node_name: str,
        max_spool_files: int = 1000,
        max_spool_bytes: int = 256 * 1024 * 1024,
    ) -> None:
        if max_spool_files < 1 or max_spool_bytes < 1024:
            raise ValueError("spool limits are invalid")
        self.state_dir = state_dir
        self.cluster_id = cluster_id
        self.node_name = node_name
        self.spool_dir = state_dir / "spool"
        self.max_spool_files = max_spool_files
        self.max_spool_bytes = max_spool_bytes

    def initialize(self) -> None:
        self.spool_dir.mkdir(parents=True, exist_ok=True)
        try:
            self.state_dir.chmod(0o700)
            self.spool_dir.chmod(0o700)
        except OSError:
            pass

    def load_node_token(self) -> str | None:
        payload = self._read_json(self.state_dir / "identity.json")
        if not isinstance(payload, dict):
            return None
        if payload.get("cluster_id") != self.cluster_id or payload.get("node_name") != self.node_name:
            return None
        token = payload.get("node_token")
        return token if isinstance(token, str) and token else None

    def save_node_token(self, node_token: str) -> None:
        self.initialize()
        self._atomic_write(
            self.state_dir / "identity.json",
            {
                "cluster_id": self.cluster_id,
                "node_name": self.node_name,
                "node_token": node_token,
            },
            mode=0o600,
        )

    def enqueue_response(self, payload: dict[str, Any]) -> None:
        request_id = str(payload.get("request_id") or "").strip()
        if not request_id:
            raise ValueError("spooled evidence response requires request_id")
        self.initialize()
        spool_path = self._spool_path(request_id)
        encoded_size = len(json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8"))
        self._ensure_spool_capacity(spool_path, encoded_size)
        self._atomic_write(spool_path, payload, mode=0o600)

    def pending_responses(self, limit: int = 100) -> list[dict[str, Any]]:
        self.initialize()
        responses: list[dict[str, Any]] = []
        for path in sorted(self.spool_dir.glob("*.json"))[: max(1, limit)]:
            payload = self._read_json(path)
            if isinstance(payload, dict) and payload.get("request_id"):
                responses.append(payload)
            else:
                path.rename(path.with_suffix(".invalid"))
        return responses

    def has_pending_response(self, request_id: str) -> bool:
        return self._spool_path(request_id).is_file()

    def acknowledge_response(self, request_id: str) -> None:
        try:
            self._spool_path(request_id).unlink()
        except FileNotFoundError:
            pass

    def _spool_path(self, request_id: str) -> Path:
        safe_id = re.sub(r"[^A-Za-z0-9._-]+", "_", request_id)[:180]
        if not safe_id:
            raise ValueError("invalid evidence request id")
        return self.spool_dir / f"{safe_id}.json"

    def _ensure_spool_capacity(self, target: Path, incoming_bytes: int) -> None:
        files = list(self.spool_dir.glob("*.json"))
        existing_bytes = target.stat().st_size if target.exists() else 0
        total_bytes = sum(path.stat().st_size for path in files)
        if not target.exists() and len(files) >= self.max_spool_files:
            raise RuntimeError("evidence spool file limit reached")
        if total_bytes - existing_bytes + incoming_bytes > self.max_spool_bytes:
            raise RuntimeError("evidence spool byte limit reached")

    def _atomic_write(self, path: Path, payload: dict[str, Any], mode: int) -> None:
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        temporary_path = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(encoded)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            try:
                temporary_path.chmod(mode)
            except OSError:
                pass
            temporary_path.replace(path)
        finally:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass

    def _read_json(self, path: Path) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, OSError, json.JSONDecodeError):
            return None
