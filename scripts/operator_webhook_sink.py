#!/usr/bin/env python3
"""Minimal authenticated Alertmanager webhook sink for the Operator E2E canary."""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


TOKEN = os.environ.get("WEBHOOK_TOKEN", "")
EXPECTED_ALERT = os.environ.get("EXPECTED_ALERT", "ClusterRcaOperatorDeliveryCanary")
PORT = int(os.environ.get("PORT", "8080"))
PATH = "/api/webhooks/alertmanager"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802
        if self.path != PATH:
            self.send_error(404)
            return
        if not TOKEN or self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_error(401)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 1_048_576:
                raise ValueError("invalid request size")
            payload = json.loads(self.rfile.read(size).decode("utf-8"))
            alerts = payload.get("alerts", [])
            if not isinstance(alerts, list):
                raise ValueError("alerts must be a list")
            matches = matching_alerts(alerts)
            if not matches:
                raise ValueError("expected canary alert is missing")
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            print(f"RCA_OPERATOR_DELIVERY_ERROR {error}", flush=True)
            self.send_error(400)
            return
        for alert in matches:
            event = {
                "alert": EXPECTED_ALERT,
                "status": alert.get("status", ""),
                "cluster_id": alert.get("labels", {}).get("cluster_id", ""),
                "node": alert.get("labels", {}).get("node", ""),
            }
            print(f"RCA_OPERATOR_DELIVERY {json.dumps(event, sort_keys=True)}", flush=True)
        body = b'{"status":"accepted"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def matching_alerts(alerts: list[Any]) -> list[dict[str, Any]]:
    return [
        alert
        for alert in alerts
        if isinstance(alert, dict)
        and isinstance(alert.get("labels"), dict)
        and alert["labels"].get("alertname") == EXPECTED_ALERT
    ]


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("WEBHOOK_TOKEN is required")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
