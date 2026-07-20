#!/usr/bin/env python3
"""Exercise Helm Prometheus rules through Prometheus, Alertmanager, and an authenticated webhook."""

from __future__ import annotations

import argparse
import json
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

try:
    from scripts.llm_prometheus_rule_test import command_path, render_rules
except ModuleNotFoundError:
    from llm_prometheus_rule_test import command_path, render_rules


WEBHOOK_PATH = "/api/webhooks/alertmanager"
EXPECTED_ALERT = "ClusterRcaLlmCircuitBreakerOpen"


@dataclass
class DeliveryCapture:
    token: str
    payloads: list[dict[str, Any]] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    condition: threading.Condition = field(default_factory=threading.Condition)

    def record(self, payload: dict[str, Any]) -> None:
        with self.condition:
            self.payloads.append(payload)
            self.condition.notify_all()

    def fail(self, message: str) -> None:
        with self.condition:
            self.errors.append(message)
            self.condition.notify_all()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Prometheus -> Alertmanager -> authenticated webhook delivery."
    )
    parser.add_argument("--helm", default="helm")
    parser.add_argument("--prometheus", default="prometheus")
    parser.add_argument("--alertmanager", default="alertmanager")
    parser.add_argument("--timeout-seconds", type=int, default=45)
    return parser.parse_args()


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind(("127.0.0.1", 0))
        return int(server.getsockname()[1])


def webhook_handler(capture: DeliveryCapture) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            if self.path != WEBHOOK_PATH:
                self.send_error(404)
                return
            if self.headers.get("Authorization") != f"Bearer {capture.token}":
                capture.fail("Alertmanager webhook did not include the expected Bearer credential")
                self.send_error(401)
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size <= 0 or size > 1_048_576:
                    raise ValueError("webhook body size is invalid")
                payload = json.loads(self.rfile.read(size).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("webhook payload must be a JSON object")
            except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                capture.fail(str(error))
                self.send_error(400)
                return
            capture.record(payload)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"accepted"}')

        def log_message(self, _format: str, *_args: object) -> None:
            return

    return Handler


def metrics_handler() -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        scrapes = 0
        lock = threading.Lock()

        def do_GET(self) -> None:  # noqa: N802
            if self.path != "/metrics":
                self.send_error(404)
                return
            with self.lock:
                type(self).scrapes += 1
                value = 0 if type(self).scrapes < 3 else 1
            body = (
                "# TYPE rca_llm_analysis_total counter\n"
                "rca_llm_analysis_total{"
                'result="circuit_open",namespace="rca-system",service="rca",provider="gemini"'
                f"}} {value}\n"
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    return Handler


def alertmanager_config(webhook_port: int, token_file: Path) -> str:
    return f"""global:
  resolve_timeout: 5s
route:
  receiver: cluster-infra-rca
  group_by: [alertname, cluster_id, node]
  group_wait: 0s
  group_interval: 1s
  repeat_interval: 1h
receivers:
  - name: cluster-infra-rca
    webhook_configs:
      - url: http://127.0.0.1:{webhook_port}{WEBHOOK_PATH}
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials_file: {json.dumps(str(token_file.resolve()))}
"""


def prometheus_config(metrics_port: int, alertmanager_port: int, rule_file: Path) -> str:
    return f"""global:
  scrape_interval: 1s
  evaluation_interval: 1s
alerting:
  alertmanagers:
    - static_configs:
        - targets: [\"127.0.0.1:{alertmanager_port}\"]
rule_files:
  - {json.dumps(str(rule_file.resolve()))}
scrape_configs:
  - job_name: rca-delivery-test
    static_configs:
      - targets: [\"127.0.0.1:{metrics_port}\"]
"""


def wait_ready(url: str, process: subprocess.Popen[bytes], timeout_at: float, name: str) -> None:
    while time.monotonic() < timeout_at:
        if process.poll() is not None:
            raise RuntimeError(f"{name} exited before becoming ready with code {process.returncode}")
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            time.sleep(0.2)
    raise TimeoutError(f"timed out waiting for {name} readiness")


def matching_statuses(payloads: list[dict[str, Any]]) -> set[str]:
    statuses: set[str] = set()
    for payload in payloads:
        for alert in payload.get("alerts", []):
            if alert.get("labels", {}).get("alertname") != EXPECTED_ALERT:
                continue
            status = alert.get("status")
            if isinstance(status, str):
                statuses.add(status)
    return statuses


def validate_delivery(capture: DeliveryCapture, timeout_at: float) -> set[str]:
    expected = {"firing", "resolved"}
    with capture.condition:
        while time.monotonic() < timeout_at:
            if capture.errors:
                raise RuntimeError(capture.errors[0])
            statuses = matching_statuses(capture.payloads)
            if expected.issubset(statuses):
                return statuses
            capture.condition.wait(timeout=min(0.5, max(0, timeout_at - time.monotonic())))
    statuses = matching_statuses(capture.payloads)
    raise TimeoutError(
        f"delivery did not produce firing and resolved {EXPECTED_ALERT} notifications; "
        f"received={sorted(statuses)} payloads={len(capture.payloads)}"
    )


def process_tail(path: Path, lines: int = 30) -> str:
    if not path.exists():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def run() -> dict[str, Any]:
    args = parse_args()
    helm = command_path(args.helm)
    prometheus = command_path(args.prometheus)
    alertmanager = command_path(args.alertmanager)
    timeout_at = time.monotonic() + args.timeout_seconds
    token = secrets.token_urlsafe(32)
    capture = DeliveryCapture(token)
    webhook_server = ThreadingHTTPServer(("127.0.0.1", 0), webhook_handler(capture))
    metrics_server = ThreadingHTTPServer(("127.0.0.1", 0), metrics_handler())
    webhook_thread = threading.Thread(target=webhook_server.serve_forever, daemon=True)
    metrics_thread = threading.Thread(target=metrics_server.serve_forever, daemon=True)
    webhook_thread.start()
    metrics_thread.start()
    prometheus_process: subprocess.Popen[bytes] | None = None
    alertmanager_process: subprocess.Popen[bytes] | None = None

    try:
        with tempfile.TemporaryDirectory(prefix="rca-alert-delivery-") as directory:
            workspace = Path(directory)
            token_file = workspace / "webhook-token"
            token_file.write_text(token, encoding="utf-8", newline="\n")
            rule_file = workspace / "llm-rules.yml"
            rule_file.write_text(
                render_rules(
                    helm,
                    [
                        "--set",
                        "platform.prometheusRule.groupInterval=1s",
                        "--set",
                        "platform.prometheusRule.circuitBreaker.lookback=5s",
                        "--set-string",
                        "platform.prometheusRule.ruleLabels.cluster_id=delivery-test",
                        "--set-string",
                        "platform.prometheusRule.ruleLabels.node=delivery-node",
                    ],
                ),
                encoding="utf-8",
                newline="\n",
            )
            alertmanager_file = workspace / "alertmanager.yml"
            alertmanager_file.write_text(
                alertmanager_config(webhook_server.server_port, token_file),
                encoding="utf-8",
                newline="\n",
            )
            prometheus_file = workspace / "prometheus.yml"
            alertmanager_port = free_port()
            prometheus_port = free_port()
            prometheus_file.write_text(
                prometheus_config(metrics_server.server_port, alertmanager_port, rule_file),
                encoding="utf-8",
                newline="\n",
            )
            alertmanager_log = workspace / "alertmanager.log"
            prometheus_log = workspace / "prometheus.log"
            try:
                with alertmanager_log.open("wb") as alert_log, prometheus_log.open("wb") as prom_log:
                    alertmanager_process = subprocess.Popen(
                        [
                            alertmanager,
                            f"--config.file={alertmanager_file}",
                            f"--storage.path={workspace / 'alertmanager-data'}",
                            f"--web.listen-address=127.0.0.1:{alertmanager_port}",
                            "--cluster.listen-address=",
                        ],
                        stdout=alert_log,
                        stderr=subprocess.STDOUT,
                    )
                    wait_ready(
                        f"http://127.0.0.1:{alertmanager_port}/-/ready",
                        alertmanager_process,
                        timeout_at,
                        "Alertmanager",
                    )
                    prometheus_process = subprocess.Popen(
                        [
                            prometheus,
                            f"--config.file={prometheus_file}",
                            f"--storage.tsdb.path={workspace / 'prometheus-data'}",
                            f"--web.listen-address=127.0.0.1:{prometheus_port}",
                            "--storage.tsdb.retention.time=1h",
                        ],
                        stdout=prom_log,
                        stderr=subprocess.STDOUT,
                    )
                    wait_ready(
                        f"http://127.0.0.1:{prometheus_port}/-/ready",
                        prometheus_process,
                        timeout_at,
                        "Prometheus",
                    )
                    try:
                        statuses = validate_delivery(capture, timeout_at)
                    except Exception as error:
                        raise RuntimeError(
                            f"{error}\nPrometheus log:\n{process_tail(prometheus_log)}\n"
                            f"Alertmanager log:\n{process_tail(alertmanager_log)}"
                        ) from error
            finally:
                stop_process(prometheus_process)
                stop_process(alertmanager_process)
                prometheus_process = None
                alertmanager_process = None
            return {
                "status": "passed",
                "alert": EXPECTED_ALERT,
                "notification_statuses": sorted(statuses),
                "payload_count": len(capture.payloads),
                "authentication": "bearer credentials file",
                "chain": ["Helm PrometheusRule", "Prometheus", "Alertmanager", "webhook"],
            }
    finally:
        stop_process(prometheus_process)
        stop_process(alertmanager_process)
        webhook_server.shutdown()
        metrics_server.shutdown()
        webhook_server.server_close()
        metrics_server.server_close()


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
