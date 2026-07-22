from __future__ import annotations

import argparse
import json
import logging
import os
import random
import socket
import sys
import time
from decimal import Decimal, InvalidOperation
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from node_agent import AGENT_PROTOCOL_VERSION, SUPPORTED_COLLECTORS, __version__
from node_agent.capabilities import agent_status_for, collect_capabilities
from node_agent.client import AgentClient, AgentClientError
from node_agent.collectors import (
    AgentPaths,
    CommandRunner,
    agent_mode,
    allowed_collectors,
    collect_evidence,
    collect_node,
    collector_metadata,
)
from node_agent.ebpf import EbpfEventManager
from node_agent.payload import bounded_collectors_payload
from node_agent.redaction import redact_value
from node_agent.state import AgentStateStore


LOGGER = logging.getLogger("cluster-infra-rca-agent")


def process_pending_requests(
    client: AgentClient,
    paths: AgentPaths,
    runner: CommandRunner,
    limit: int,
    state: AgentStateStore | None = None,
) -> int:
    if state is not None:
        flush_spooled_responses(client, state)
    pending_requests = client.poll_evidence_requests(limit=limit)
    processed = 0

    for item in pending_requests:
        request_id = item.get("request_id")
        if not request_id:
            LOGGER.warning("skipping malformed evidence request without request_id: %s", item)
            continue
        if state is not None and state.has_pending_response(request_id):
            LOGGER.warning("evidence request %s remains spooled; collection will not be repeated", request_id)
            continue

        try:
            collectors = collect_evidence(item.get("requested_collectors") or [], paths=paths, runner=runner)
            collectors = bounded_collectors_payload(
                redact_value(collectors),
                _positive_int_env(
                    "AGENT_EVIDENCE_MAX_BYTES",
                    8 * 1024 * 1024,
                    minimum=64 * 1024,
                ),
            )
            response_payload = {
                "request_id": request_id,
                "status": "completed",
                "collectors": collectors,
                "error_message": None,
            }
        except Exception as exc:  # noqa: BLE001 - report collector failures as evidence.
            LOGGER.exception("failed to collect evidence request %s", request_id)
            response_payload = {
                "request_id": request_id,
                "status": "failed",
                "collectors": {},
                "error_message": str(exc)[:1000],
            }

        if state is not None:
            state.enqueue_response(response_payload)

        try:
            client.submit_evidence_response(
                request_id=request_id,
                status=str(response_payload["status"]),
                collectors=response_payload["collectors"],
                error_message=response_payload["error_message"],
            )
            if state is not None:
                state.acknowledge_response(request_id)
            if response_payload["status"] == "completed":
                processed += 1
            LOGGER.info("submitted %s evidence request %s", response_payload["status"], request_id)
        except AgentClientError as exc:
            if exc.status_code in {404, 409}:
                if state is not None:
                    state.acknowledge_response(request_id)
                LOGGER.warning("discarded closed evidence response %s after HTTP %s", request_id, exc.status_code)
            else:
                LOGGER.exception("failed to submit evidence request %s; response remains spooled", request_id)

    return processed


def flush_spooled_responses(client: AgentClient, state: AgentStateStore, limit: int = 100) -> int:
    submitted = 0
    for payload in state.pending_responses(limit=limit):
        request_id = str(payload["request_id"])
        try:
            client.submit_evidence_response(
                request_id=request_id,
                status=str(payload.get("status") or "failed"),
                collectors=payload.get("collectors") if isinstance(payload.get("collectors"), dict) else {},
                error_message=payload.get("error_message"),
            )
            state.acknowledge_response(request_id)
            submitted += 1
            LOGGER.info("submitted spooled evidence response %s", request_id)
        except AgentClientError as exc:
            if exc.status_code in {404, 409}:
                state.acknowledge_response(request_id)
                LOGGER.warning("discarded closed spooled response %s after HTTP %s", request_id, exc.status_code)
                continue
            LOGGER.exception("spooled evidence response %s is still pending", request_id)
            break
    return submitted


def collect_local_evidence(
    paths: AgentPaths,
    runner: CommandRunner,
    requested_collectors: list[str],
) -> dict[str, Any]:
    mode = agent_mode()
    capabilities = collect_capabilities(
        paths=paths,
        runner=runner,
        mode=mode,
        ebpf_enabled=mode == "ebpf" and _boolean_env("EBPF_ENABLED", False),
    )
    collectors = collect_evidence(requested_collectors, paths=paths, runner=runner)
    return {
        "agent_version": __version__,
        "agent_protocol_version": AGENT_PROTOCOL_VERSION,
        "node_name": os.getenv("NODE_NAME") or socket.gethostname(),
        "collected_at": datetime.now(timezone.utc).isoformat(),
        "requested_collectors": requested_collectors,
        "capabilities": capabilities,
        "host_paths": {
            "root": str(paths.root),
            "proc": str(paths.proc),
            "sys": str(paths.sys),
            "etc": str(paths.etc),
            "var_log": str(paths.var_log),
            "run": str(paths.run),
        },
        "collectors": collectors,
    }


def write_local_evidence(evidence: dict[str, Any], output: str) -> None:
    encoded = json.dumps(evidence, ensure_ascii=False, indent=2, default=str)
    if output == "-":
        sys.stdout.write(encoded)
        sys.stdout.write("\n")
        return

    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(encoded + "\n", encoding="utf-8")


def build_client_from_env(timeout_seconds: float) -> AgentClient:
    backend_url = _required_env("BACKEND_URL")
    cluster_id = _required_env("CLUSTER_ID")
    enrollment_mode = os.getenv("AGENT_ENROLLMENT_MODE", "bootstrap-token").strip().lower()
    if enrollment_mode not in {"bootstrap-token", "kubernetes-token-review"}:
        raise ValueError("AGENT_ENROLLMENT_MODE must be bootstrap-token or kubernetes-token-review")
    agent_token = _required_env("AGENT_TOKEN") if enrollment_mode == "bootstrap-token" else None
    identity_token_path = (
        _required_env("AGENT_IDENTITY_TOKEN_PATH")
        if enrollment_mode == "kubernetes-token-review"
        else None
    )
    node_name = os.getenv("NODE_NAME") or socket.gethostname()
    return AgentClient(
        backend_url=backend_url,
        cluster_id=cluster_id,
        node_name=node_name,
        agent_token=agent_token,
        timeout_seconds=timeout_seconds,
        ca_bundle=os.getenv("AGENT_CA_BUNDLE") or None,
        client_cert=os.getenv("AGENT_CLIENT_CERT") or None,
        client_key=os.getenv("AGENT_CLIENT_KEY") or None,
        enrollment_mode=enrollment_mode,
        identity_token_path=identity_token_path,
    )


def run_agent(args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper()),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    paths = AgentPaths.from_env()
    runner = CommandRunner(timeout_seconds=args.command_timeout_seconds)
    mode = agent_mode()
    ebpf_enabled = mode == "ebpf" and _boolean_env("EBPF_ENABLED", False)
    if args.capability_check:
        capabilities = collect_capabilities(
            paths=paths,
            runner=runner,
            mode=mode,
            ebpf_enabled=ebpf_enabled,
        )
        write_local_evidence(
            {
                "agent_version": __version__,
                "agent_protocol_version": AGENT_PROTOCOL_VERSION,
                "node_name": os.getenv("NODE_NAME") or socket.gethostname(),
                "checked_at": datetime.now(timezone.utc).isoformat(),
                "capabilities": capabilities,
            },
            args.output,
        )
        return 0
    if args.collect_local:
        evidence = collect_local_evidence(
            paths=paths,
            runner=runner,
            requested_collectors=_parse_collector_list(args.collectors),
        )
        write_local_evidence(evidence, args.output)
        return 0

    client = build_client_from_env(timeout_seconds=args.http_timeout_seconds)
    metadata = _agent_metadata(paths, runner)
    ebpf = EbpfEventManager(
        enabled=ebpf_enabled,
        queue_size=_positive_int_env("EBPF_EVENT_QUEUE_SIZE", 1000),
    )
    supported_collectors = sorted(allowed_collectors(mode))
    if mode == "ebpf" and ebpf.enabled:
        supported_collectors.append("ebpf")
    state = AgentStateStore(
        Path(os.getenv("AGENT_STATE_DIR", "/tmp/cluster-infra-rca-agent")),
        client.cluster_id,
        client.node_name,
        max_spool_files=_positive_int_env("AGENT_MAX_SPOOL_FILES", 1000),
        max_spool_bytes=_positive_int_env("AGENT_MAX_SPOOL_BYTES", 256 * 1024 * 1024, minimum=1024),
    )
    state.initialize()
    client.node_token = state.load_node_token()
    if client.node_token:
        client.discard_bootstrap_token()
    backoff = RetryBackoff(
        initial_seconds=args.retry_initial_seconds,
        maximum_seconds=args.retry_max_seconds,
    )

    if not client.node_token and not _register_with_retry(
        client, metadata, state, args.once, backoff, supported_collectors
    ):
        return 1

    ebpf.start()
    while True:
        try:
            capabilities = collect_capabilities(
                paths=paths,
                runner=runner,
                mode=mode,
                ebpf_enabled=ebpf.enabled,
            )
            flush_spooled_responses(client, state)
            client.heartbeat(
                agent_version=__version__,
                agent_protocol_version=AGENT_PROTOCOL_VERSION,
                supported_collectors=supported_collectors,
                status=agent_status_for(capabilities),
                health={
                    "agent": "running",
                    "mode": mode,
                    "ebpf": "enabled" if ebpf.enabled else "disabled",
                    "capabilities": capabilities,
                },
            )
            processed = process_pending_requests(
                client=client,
                paths=paths,
                runner=runner,
                limit=args.request_limit,
                state=state,
            )
            realtime_batch = ebpf.drain(limit=100)
            if realtime_batch:
                try:
                    client.submit_realtime_events(realtime_batch)
                except AgentClientError:
                    ebpf.requeue(realtime_batch)
                    raise
            LOGGER.info(
                "poll cycle completed; evidence=%s realtime_events=%s",
                processed,
                len(realtime_batch),
            )
            backoff.reset()
        except AgentClientError as exc:
            LOGGER.exception("backend communication failed")
            if exc.status_code in {401, 404}:
                client.node_token = None
                state.clear_node_token()
                LOGGER.error(
                    "node identity was rejected; cleared the local token and stopped for explicit re-enrollment"
                )
                return 1
            if args.once:
                return 1
            time.sleep(backoff.next_delay())
            continue
        except Exception:  # noqa: BLE001 - daemon should keep running after unexpected cycle errors.
            LOGGER.exception("unexpected agent cycle failure")
            if args.once:
                return 1
            time.sleep(backoff.next_delay())
            continue

        if args.once:
            return 0
        time.sleep(args.poll_interval_seconds)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Kubernetes cluster infra RCA node agent")
    parser.add_argument("--once", action="store_true", help="Run one heartbeat/poll/submit cycle and exit")
    parser.add_argument(
        "--collect-local",
        action="store_true",
        help="Collect evidence locally and exit without registering to backend",
    )
    parser.add_argument(
        "--capability-check",
        action="store_true",
        help="Run the Agent host access self-check and exit without registering to backend",
    )
    parser.add_argument(
        "--collectors",
        default=",".join(SUPPORTED_COLLECTORS),
        help="Comma-separated collector list for --collect-local",
    )
    parser.add_argument(
        "--output",
        default="-",
        help="Output path for --collect-local, or '-' for stdout",
    )
    parser.add_argument(
        "--poll-interval-seconds",
        type=float,
        default=float(os.getenv("POLL_INTERVAL_SECONDS", "15")),
    )
    parser.add_argument(
        "--request-limit",
        type=int,
        default=int(os.getenv("REQUEST_LIMIT", "10")),
    )
    parser.add_argument(
        "--http-timeout-seconds",
        type=float,
        default=float(os.getenv("HTTP_TIMEOUT_SECONDS", "10")),
    )
    parser.add_argument(
        "--command-timeout-seconds",
        type=float,
        default=float(os.getenv("COMMAND_TIMEOUT_SECONDS", "5")),
    )
    parser.add_argument(
        "--retry-initial-seconds",
        type=float,
        default=float(os.getenv("RETRY_INITIAL_SECONDS", "2")),
    )
    parser.add_argument(
        "--retry-max-seconds",
        type=float,
        default=float(os.getenv("RETRY_MAX_SECONDS", "120")),
    )
    parser.add_argument(
        "--log-level",
        choices=["debug", "info", "warning", "error"],
        default=os.getenv("LOG_LEVEL", "info").lower(),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return run_agent(args)
    except ValueError as exc:
        logging.basicConfig(level=logging.ERROR, format="%(levelname)s %(message)s")
        LOGGER.error("%s", exc)
        return 2
    except KeyboardInterrupt:
        return 130


def _register_with_retry(
    client: AgentClient,
    metadata: dict[str, Any],
    state: AgentStateStore,
    once: bool,
    backoff: "RetryBackoff",
    supported_collectors: list[str] | None = None,
) -> bool:
    while True:
        try:
            response = client.register(
                agent_version=__version__,
                agent_protocol_version=AGENT_PROTOCOL_VERSION,
                supported_collectors=supported_collectors or SUPPORTED_COLLECTORS,
                metadata=metadata,
            )
            state.save_node_token(str(response["node_token"]))
            client.discard_bootstrap_token()
            backoff.reset()
            LOGGER.info("registered node agent for cluster=%s node=%s", client.cluster_id, client.node_name)
            return True
        except AgentClientError:
            LOGGER.exception("agent registration failed")
            if once:
                return False
            time.sleep(backoff.next_delay())


def _agent_metadata(paths: AgentPaths, runner: CommandRunner) -> dict[str, Any]:
    mode = agent_mode()
    try:
        node = collect_node(paths)
        return {
            "host_name": node.get("host_name"),
            "kernel_version": node.get("kernel_version"),
            "os_release": node.get("os_release"),
            "agent_mode": mode,
            "collectors": collector_metadata(paths, runner, mode),
        }
    except Exception as exc:  # noqa: BLE001 - metadata is useful but not required.
        return {"metadata_error": str(exc)}


def _required_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise ValueError(f"{name} environment variable is required")
    return value


def _positive_int_env(name: str, default: int, minimum: int = 1) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        parsed = Decimal(raw_value)
    except InvalidOperation as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not parsed.is_finite() or parsed != parsed.to_integral_value():
        raise ValueError(f"{name} must be an integer")
    value = int(parsed)
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


def _boolean_env(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    normalized = raw_value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def _parse_collector_list(raw_value: str) -> list[str]:
    collectors = [item.strip() for item in raw_value.split(",") if item.strip()]
    return collectors or SUPPORTED_COLLECTORS


class RetryBackoff:
    def __init__(self, initial_seconds: float = 2, maximum_seconds: float = 120) -> None:
        if initial_seconds <= 0 or maximum_seconds < initial_seconds:
            raise ValueError("retry backoff values are invalid")
        self.initial_seconds = initial_seconds
        self.maximum_seconds = maximum_seconds
        self._attempt = 0

    def next_delay(self) -> float:
        base = min(self.maximum_seconds, self.initial_seconds * (2**self._attempt))
        self._attempt = min(self._attempt + 1, 30)
        return min(self.maximum_seconds, base * random.uniform(0.8, 1.2))

    def reset(self) -> None:
        self._attempt = 0


if __name__ == "__main__":
    sys.exit(main())
