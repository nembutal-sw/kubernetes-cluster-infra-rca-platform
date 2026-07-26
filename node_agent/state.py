from __future__ import annotations

import json
import os
import re
import secrets
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class NodeTokenIdentity:
    node_token: str
    issued_at: datetime
    pending_node_token: str | None = None
    pending_requested_at: datetime | None = None
    rotation_attempted_at: datetime | None = None

    @property
    def preferred_token(self) -> str:
        return self.pending_node_token or self.node_token


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
        identity = self.load_node_identity()
        return identity.node_token if identity is not None else None

    def load_preferred_node_token(self) -> str | None:
        identity = self.load_node_identity()
        return identity.preferred_token if identity is not None else None

    def load_node_identity(self) -> NodeTokenIdentity | None:
        path = self.state_dir / "identity.json"
        payload = self._read_json(path)
        if not isinstance(payload, dict):
            return None
        if payload.get("cluster_id") != self.cluster_id or payload.get("node_name") != self.node_name:
            return None
        token = payload.get("node_token")
        if not isinstance(token, str) or not token:
            return None
        issued_at = self._timestamp(payload.get("node_token_issued_at")) or self._file_timestamp(path)
        pending_token = payload.get("pending_node_token")
        if not isinstance(pending_token, str) or not pending_token:
            pending_token = None
        return NodeTokenIdentity(
            node_token=token,
            issued_at=issued_at,
            pending_node_token=pending_token,
            pending_requested_at=(
                self._timestamp(payload.get("pending_requested_at"))
                if pending_token is not None
                else None
            ),
            rotation_attempted_at=self._timestamp(payload.get("rotation_attempted_at")),
        )

    def save_node_token(
        self,
        node_token: str,
        *,
        issued_at: datetime | None = None,
    ) -> None:
        if not node_token:
            raise ValueError("node token must not be empty")
        self.initialize()
        self._write_identity(
            NodeTokenIdentity(
                node_token=node_token,
                issued_at=self._utc(issued_at),
            )
        )

    def record_rotation_attempt(self, attempted_at: datetime | None = None) -> bool:
        identity = self.load_node_identity()
        if identity is None or identity.pending_node_token is not None:
            return False
        self._write_identity(
            NodeTokenIdentity(
                node_token=identity.node_token,
                issued_at=identity.issued_at,
                rotation_attempted_at=self._utc(attempted_at),
            )
        )
        return True

    def stage_node_token_rotation(
        self,
        pending_node_token: str,
        *,
        requested_at: datetime | None = None,
    ) -> None:
        if not pending_node_token:
            raise ValueError("pending node token must not be empty")
        identity = self.load_node_identity()
        if identity is None:
            raise RuntimeError("cannot stage rotation without an active node token")
        requested = self._utc(requested_at)
        self._write_identity(
            NodeTokenIdentity(
                node_token=identity.node_token,
                issued_at=identity.issued_at,
                pending_node_token=pending_node_token,
                pending_requested_at=requested,
                rotation_attempted_at=identity.rotation_attempted_at or requested,
            )
        )

    def commit_pending_node_token(self, activated_at: datetime | None = None) -> bool:
        identity = self.load_node_identity()
        if identity is None or identity.pending_node_token is None:
            return False
        self._write_identity(
            NodeTokenIdentity(
                node_token=identity.pending_node_token,
                issued_at=self._utc(activated_at),
                rotation_attempted_at=identity.rotation_attempted_at,
            )
        )
        return True

    def rollback_pending_node_token(self, attempted_at: datetime | None = None) -> str | None:
        identity = self.load_node_identity()
        if identity is None or identity.pending_node_token is None:
            return None
        self._write_identity(
            NodeTokenIdentity(
                node_token=identity.node_token,
                issued_at=identity.issued_at,
                rotation_attempted_at=self._utc(attempted_at),
            )
        )
        return identity.node_token

    def is_pending_node_token(self, candidate: str | None) -> bool:
        identity = self.load_node_identity()
        return bool(
            identity is not None
            and identity.pending_node_token is not None
            and candidate is not None
            and secrets.compare_digest(identity.pending_node_token, candidate)
        )

    def node_token_rotation_due(
        self,
        maximum_age: timedelta,
        retry_after: timedelta,
        *,
        now: datetime | None = None,
    ) -> bool:
        identity = self.load_node_identity()
        if identity is None or identity.pending_node_token is not None:
            return False
        current = self._utc(now)
        if maximum_age.total_seconds() <= 0 or current < identity.issued_at + maximum_age:
            return False
        return (
            identity.rotation_attempted_at is None
            or current >= identity.rotation_attempted_at + retry_after
        )

    def clear_node_token(self) -> None:
        try:
            (self.state_dir / "identity.json").unlink()
        except FileNotFoundError:
            pass

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
            self._fsync_directory(path.parent)
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

    def _write_identity(self, identity: NodeTokenIdentity) -> None:
        self.initialize()
        payload: dict[str, Any] = {
            "version": 2,
            "cluster_id": self.cluster_id,
            "node_name": self.node_name,
            "node_token": identity.node_token,
            "node_token_issued_at": self._format_timestamp(identity.issued_at),
        }
        if identity.pending_node_token is not None:
            payload["pending_node_token"] = identity.pending_node_token
            payload["pending_requested_at"] = self._format_timestamp(
                identity.pending_requested_at or datetime.now(timezone.utc)
            )
        if identity.rotation_attempted_at is not None:
            payload["rotation_attempted_at"] = self._format_timestamp(
                identity.rotation_attempted_at
            )
        self._atomic_write(self.state_dir / "identity.json", payload, mode=0o600)

    def _file_timestamp(self, path: Path) -> datetime:
        try:
            return datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
        except OSError:
            return datetime.now(timezone.utc)

    def _timestamp(self, value: Any) -> datetime | None:
        if not isinstance(value, str) or not value:
            return None
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.astimezone(timezone.utc)
        except ValueError:
            return None

    def _format_timestamp(self, value: datetime) -> str:
        return self._utc(value).isoformat().replace("+00:00", "Z")

    def _utc(self, value: datetime | None) -> datetime:
        if value is None:
            return datetime.now(timezone.utc)
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    def _fsync_directory(self, directory: Path) -> None:
        try:
            descriptor = os.open(directory, os.O_RDONLY)
        except OSError:
            return
        try:
            os.fsync(descriptor)
        except OSError:
            pass
        finally:
            os.close(descriptor)
