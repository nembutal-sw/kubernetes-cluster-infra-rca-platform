from __future__ import annotations

import argparse
import json
import logging
import os
import random
import socket
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from node_agent import SUPPORTED_COLLECTORS, __version__
from node_agent.client import AgentClient, AgentClientError
from node_agent.collectors import AgentPaths, CommandRunner, collect_evidence, collect_node
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
    collectors = collect_evidence(requested_collectors, paths=paths, runner=runner)
    return {
        "agent_version": __version__,
        "node_name": os.getenv("NODE_NAME") or socket.gethostname(),
        "collected_at": datetime.now(timezone.utc).isoformat(),
        "requested_collectors": requested_collectors,
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
    agent_token = _required_env("AGENT_TOKEN")
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
    )


def run_agent(args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper()),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    paths = AgentPaths.from_env()
    runner = CommandRunner(timeout_seconds=args.command_timeout_seconds)
    if args.collect_local:
        evidence = collect_local_evidence(
            paths=paths,
            runner=runner,
            requested_collectors=_parse_collector_list(args.collectors),
        )
        write_local_evidence(evidence, args.output)
        return 0

    client = build_client_from_env(timeout_seconds=args.http_timeout_seconds)
    metadata = _agent_metadata(paths)
    state = AgentStateStore(
        Path(os.getenv("AGENT_STATE_DIR", "/tmp/cluster-infra-rca-agent")),
        client.cluster_id,
        client.node_name,
        max_spool_files=int(os.getenv("AGENT_MAX_SPOOL_FILES", "1000")),
        max_spool_bytes=int(os.getenv("AGENT_MAX_SPOOL_BYTES", str(256 * 1024 * 1024))),
    )
    state.initialize()
    client.node_token = state.load_node_token()
    backoff = RetryBackoff(
        initial_seconds=args.retry_initial_seconds,
        maximum_seconds=args.retry_max_seconds,
    )

    if not client.node_token and not _register_with_retry(client, metadata, state, args.once, backoff):
        return 1

    while True:
        try:
            flush_spooled_responses(client, state)
            client.heartbeat(
                agent_version=__version__,
                supported_collectors=SUPPORTED_COLLECTORS,
                health={"agent": "running"},
            )
            processed = process_pending_requests(
                client=client,
                paths=paths,
                runner=runner,
                limit=args.request_limit,
                state=state,
            )
            LOGGER.info("poll cycle completed; processed=%s", processed)
            backoff.reset()
        except AgentClientError as exc:
            LOGGER.exception("backend communication failed")
            if exc.status_code in {401, 404}:
                client.node_token = None
                if not _register_with_retry(client, metadata, state, args.once, backoff):
                    return 1
                if args.once:
                    continue
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
) -> bool:
    while True:
        try:
            response = client.register(
                agent_version=__version__,
                supported_collectors=SUPPORTED_COLLECTORS,
                metadata=metadata,
            )
            state.save_node_token(str(response["node_token"]))
            backoff.reset()
            LOGGER.info("registered node agent for cluster=%s node=%s", client.cluster_id, client.node_name)
            return True
        except AgentClientError:
            LOGGER.exception("agent registration failed")
            if once:
                return False
            time.sleep(backoff.next_delay())


def _agent_metadata(paths: AgentPaths) -> dict[str, Any]:
    try:
        node = collect_node(paths)
        return {
            "host_name": node.get("host_name"),
            "kernel_version": node.get("kernel_version"),
            "os_release": node.get("os_release"),
        }
    except Exception as exc:  # noqa: BLE001 - metadata is useful but not required.
        return {"metadata_error": str(exc)}


def _required_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise ValueError(f"{name} environment variable is required")
    return value


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
