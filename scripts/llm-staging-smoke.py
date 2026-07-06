#!/usr/bin/env python3
"""Validate LLM-backed RCA analysis against a running platform API.

The script intentionally does not read or print provider API keys. Configure
LLM credentials through the platform environment or Kubernetes Secret first,
then run this smoke test with an operator/admin account.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SENSITIVE_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9_-]{12,}"),
    re.compile(r"(?i)(api[_-]?key|authorization|bearer|token|password)\s*[:=]\s*[^,\s}]+"),
]


class ApiError(RuntimeError):
    def __init__(self, method: str, url: str, status: int, body: str) -> None:
        self.method = method
        self.url = url
        self.status = status
        self.body = body
        super().__init__(f"{method} {url} failed with HTTP {status}: {body[:500]}")


class Client:
    def __init__(self, base_url: str, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        token_required: bool = True,
    ) -> Any:
        url = self.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token_required and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise ApiError(method, url, exc.code, exc.read().decode("utf-8", "replace")) from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {url} failed: {exc}") from exc
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))

    def login(self, username: str, password: str) -> dict[str, Any]:
        response = self.request(
            "POST",
            "/api/auth/login",
            {"username": username, "password": password},
            token_required=False,
        )
        token = response.get("access_token")
        if not isinstance(token, str) or not token:
            raise RuntimeError("login response did not include access_token")
        self.token = token
        return response


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a live LLM RCA smoke test through the platform API."
    )
    parser.add_argument("--base-url", default=os.getenv("RCA_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("RCA_ADMIN_USERNAME", os.getenv("RCA_USERNAME", "admin")))
    parser.add_argument("--password", default=os.getenv("RCA_ADMIN_PASSWORD", os.getenv("RCA_PASSWORD", "")))
    parser.add_argument("--scenario", default=os.getenv("RCA_LLM_SMOKE_SCENARIO", "disk-pressure"))
    parser.add_argument("--node-name", default=os.getenv("RCA_LLM_SMOKE_NODE_NAME", "llm-smoke-node-01"))
    parser.add_argument("--cluster-id", default=os.getenv("RCA_LLM_SMOKE_CLUSTER_ID", ""))
    parser.add_argument(
        "--cluster-prefix",
        default=os.getenv("RCA_LLM_SMOKE_CLUSTER_PREFIX", "LLM Staging Smoke"),
        help="Name prefix for an isolated validation cluster when --cluster-id is not set.",
    )
    parser.add_argument(
        "--expected-llm-status",
        default=os.getenv("RCA_EXPECT_LLM_STATUS", "completed"),
        help="Comma-separated acceptable llm_analysis statuses. Default: completed.",
    )
    parser.add_argument(
        "--allow-disabled",
        action="store_true",
        default=os.getenv("RCA_LLM_SMOKE_ALLOW_DISABLED", "").lower() in {"1", "true", "yes"},
        help="Allow the smoke to pass when LLM is disabled. Useful for baseline environments only.",
    )
    parser.add_argument(
        "--task-timeout-seconds",
        type=int,
        default=int(os.getenv("RCA_TASK_TIMEOUT_SECONDS", "240")),
    )
    parser.add_argument("--poll-seconds", type=float, default=float(os.getenv("RCA_POLL_SECONDS", "2")))
    parser.add_argument(
        "--http-timeout-seconds",
        type=int,
        default=int(os.getenv("RCA_HTTP_TIMEOUT_SECONDS", "30")),
    )
    parser.add_argument(
        "--output-dir",
        default=os.getenv("RCA_OUTPUT_DIR", "validation-results/llm-staging-smoke"),
    )
    return parser.parse_args()


def validate_llm_configuration(platform_info: dict[str, Any], *, allow_disabled: bool) -> list[str]:
    llm = platform_info.get("llm")
    if not isinstance(llm, dict):
        return ["platform info does not include llm configuration"]

    errors: list[str] = []
    enabled = bool(llm.get("enabled"))
    provider = str(llm.get("provider") or "none")
    model = str(llm.get("model") or "")
    chat_model = str(llm.get("spring_ai_chat_model") or llm.get("springAiChatModel") or "none")
    credential_required = bool(llm.get("credential_required") or llm.get("credentialRequired"))
    credential_configured = bool(llm.get("credential_configured") or llm.get("credentialConfigured"))
    base_url_required = bool(llm.get("base_url_required") or llm.get("baseUrlRequired"))
    base_url_configured = bool(llm.get("base_url_configured") or llm.get("baseUrlConfigured"))

    if not enabled:
        if not allow_disabled:
            errors.append("LLM is disabled on the target platform")
        return errors
    if provider in {"", "none", "disabled"}:
        errors.append("LLM provider is not configured")
    if not model:
        errors.append("LLM model is not configured")
    if chat_model in {"", "none"}:
        errors.append("Spring AI chat model is not configured")
    if credential_required and not credential_configured:
        env_name = llm.get("credential_env") or llm.get("credentialEnv") or "provider credential env"
        errors.append(f"LLM credential is required but not configured: {env_name}")
    if base_url_required and not base_url_configured:
        env_name = llm.get("base_url_env") or llm.get("baseUrlEnv") or "provider base URL env"
        errors.append(f"LLM base URL is required but not configured: {env_name}")
    return errors


def create_validation_cluster(client: Client, prefix: str, scenario: str, run_id: str) -> dict[str, Any]:
    safe_scenario = scenario.replace("_", "-")
    return client.request(
        "POST",
        "/api/clusters",
        {
            "name": f"{prefix} {safe_scenario} {run_id}",
            "environment": "validation",
            "description": "Created by llm-staging-smoke.py for LLM RCA validation.",
        },
    )


def wait_for_task(client: Client, task_id: str, timeout_seconds: int, poll_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_task: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        task = client.request("GET", f"/api/rca/analysis-tasks/{urllib.parse.quote(task_id)}")
        last_task = task
        if task.get("status") in {"completed", "skipped", "dead_letter"}:
            return task
        time.sleep(poll_seconds)
    status = last_task.get("status") if last_task else "unknown"
    raise RuntimeError(f"analysis task {task_id} did not finish within {timeout_seconds}s; last status={status}")


def run_demo_scenario(client: Client, scenario: str, cluster_id: str, node_name: str) -> dict[str, Any]:
    scenarios = client.request("GET", "/api/demo/scenarios")
    if not scenarios.get("enabled"):
        raise RuntimeError("demo scenario mode is disabled on the target platform")
    available = {str(item.get("key")) for item in scenarios.get("scenarios", []) if isinstance(item, dict)}
    if scenario not in available:
        raise RuntimeError(f"demo scenario '{scenario}' is not available. Available: {', '.join(sorted(available))}")
    return client.request(
        "POST",
        f"/api/demo/scenarios/{urllib.parse.quote(scenario)}/run",
        {"confirmed": True, "cluster_id": cluster_id, "node_name": node_name},
    )


def llm_analysis_section(report: dict[str, Any]) -> dict[str, Any]:
    for section in report.get("evidence", []):
        if isinstance(section, dict) and section.get("type") == "llm_analysis":
            analysis = section.get("analysis")
            return analysis if isinstance(analysis, dict) else {}
    return {}


def validate_llm_report(
    report: dict[str, Any],
    *,
    expected_statuses: set[str],
    allow_disabled: bool,
) -> list[str]:
    errors: list[str] = []
    analysis = llm_analysis_section(report)
    if not analysis:
        return ["report does not contain llm_analysis evidence section"]

    status = str(analysis.get("status") or "")
    if status not in expected_statuses:
        if not (allow_disabled and status == "skipped"):
            errors.append(
                f"llm_analysis status '{status}' is not one of {', '.join(sorted(expected_statuses))}"
            )
    if status == "completed":
        result = analysis.get("result")
        if not isinstance(result, dict):
            errors.append("completed llm_analysis has no result object")
        else:
            has_content = bool(result.get("summary")) or bool(result.get("root_cause_candidates")) or bool(
                result.get("additional_checks")
            )
            if not has_content:
                errors.append("completed llm_analysis result is empty")
    if contains_sensitive_text(analysis):
        errors.append("llm_analysis appears to contain an unredacted secret-like value")

    for action in report.get("recommended_actions") or []:
        if not isinstance(action, dict) or action.get("source") != "llm":
            continue
        action_key = str(action.get("action_key") or "")
        if bool(action.get("automation_allowed")):
            errors.append(f"LLM action {action_key} has automation_allowed=true")
        execution_plan = action.get("execution_plan")
        if isinstance(execution_plan, dict) and bool(execution_plan.get("executable")):
            errors.append(f"LLM action {action_key} has executable=true")
    return errors


def contains_sensitive_text(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return any(pattern.search(text) for pattern in SENSITIVE_PATTERNS)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if not args.password:
        print("RCA_ADMIN_PASSWORD or RCA_PASSWORD is required", file=sys.stderr)
        return 2

    run_started = datetime.now(timezone.utc)
    run_id = run_started.strftime("%Y%m%d-%H%M%S")
    output_dir = Path(args.output_dir) / run_id
    expected_statuses = {
        item.strip() for item in args.expected_llm_status.split(",") if item.strip()
    } or {"completed"}
    client = Client(args.base_url, args.http_timeout_seconds)

    print(f"Logging in to {args.base_url} as {args.username}")
    client.login(args.username, args.password)
    platform_info = client.request("GET", "/api/v1/platform/info")
    config_errors = validate_llm_configuration(platform_info, allow_disabled=args.allow_disabled)

    cluster_id = args.cluster_id
    if not cluster_id:
        cluster = create_validation_cluster(client, args.cluster_prefix, args.scenario, run_id)
        cluster_id = str(cluster["cluster_id"])

    print(f"Running LLM smoke scenario '{args.scenario}' on cluster {cluster_id}")
    run_response = run_demo_scenario(client, args.scenario, cluster_id, args.node_name)
    task_id = str(run_response["analysis_task"]["task_id"])
    task = wait_for_task(client, task_id, args.task_timeout_seconds, args.poll_seconds)

    report: dict[str, Any] | None = None
    report_errors: list[str] = []
    report_id = task.get("report_id")
    if task.get("status") != "completed":
        report_errors.append(f"analysis task ended with status={task.get('status')}")
    elif not report_id:
        report_errors.append("completed task has no report_id")
    else:
        report = client.request("GET", f"/api/rca/reports/{urllib.parse.quote(str(report_id))}")
        report_errors.extend(
            validate_llm_report(
                report,
                expected_statuses=expected_statuses,
                allow_disabled=args.allow_disabled,
            )
        )

    result = {
        "status": "failed" if config_errors or report_errors else "passed",
        "started_at": run_started.isoformat(),
        "base_url": args.base_url,
        "scenario": args.scenario,
        "cluster_id": cluster_id,
        "node_name": args.node_name,
        "task": task,
        "report_id": report_id,
        "llm": platform_info.get("llm", {}),
        "llm_analysis": llm_analysis_section(report or {}),
        "errors": config_errors + report_errors,
    }
    write_json(output_dir / "llm-staging-smoke-result.json", result)
    if report:
        write_json(output_dir / f"report-{report_id}.json", report)

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 1 if result["status"] != "passed" else 0


if __name__ == "__main__":
    sys.exit(main())
