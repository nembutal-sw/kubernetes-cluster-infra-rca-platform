#!/usr/bin/env python3
"""Validate LLM-backed RCA analysis against a running platform API.

The script intentionally does not read or print provider API keys. Configure
LLM credentials through the platform environment or Kubernetes Secret first,
then run this smoke test with an operator/admin account.
"""

from __future__ import annotations

import argparse
import json
import math
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
    re.compile(r"AIza[A-Za-z0-9_-]{30,}"),
    re.compile(r"AQ\.[A-Za-z0-9_-]{30,}"),
    re.compile(
        r'(?i)["\']?(api[_-]?key|authorization|bearer|token|password)["\']?\s*[:=]\s*["\']?'
        r'(?!\[redacted\]|<redacted>|redacted(?:["\']|\s|,|}))[^,\s}"\']+'
    ),
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
        "--skip-connectivity-test",
        action="store_true",
        default=os.getenv("RCA_LLM_SMOKE_SKIP_CONNECTIVITY_TEST", "").lower() in {"1", "true", "yes"},
        help="Skip POST /api/llm/test. The default performs a live provider call before RCA validation.",
    )
    parser.add_argument(
        "--provider-call-budget",
        type=int,
        default=int(os.getenv("RCA_LLM_SMOKE_PROVIDER_CALL_BUDGET", "0")),
        help=(
            "Maximum worst-case provider calls, including configured analysis retries. "
            "Set 0 to disable. Use 1 with --skip-connectivity-test for quota-limited validation."
        ),
    )
    parser.add_argument(
        "--require-usage-metadata",
        action="store_true",
        default=os.getenv("RCA_LLM_SMOKE_REQUIRE_USAGE_METADATA", "").lower() in {"1", "true", "yes"},
        help="Fail when the provider does not return token usage metadata.",
    )
    parser.add_argument(
        "--max-llm-latency-ms",
        type=int,
        default=int(os.getenv("RCA_LLM_SMOKE_MAX_LATENCY_MS", "60000")),
        help="Maximum connectivity/report LLM latency in milliseconds. Set 0 to disable.",
    )
    parser.add_argument(
        "--max-estimated-cost-usd",
        type=float,
        default=float(os.getenv("RCA_LLM_SMOKE_MAX_ESTIMATED_COST_USD", "0")),
        help="Maximum estimated cost for the report LLM analysis. Set 0 to disable.",
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


def llm_call_plan(platform_info: dict[str, Any], *, skip_connectivity_test: bool) -> dict[str, int]:
    llm = platform_info.get("llm")
    if not isinstance(llm, dict) or not bool(llm.get("enabled")):
        return {
            "connectivity_test_calls": 0,
            "analysis_max_attempts": 0,
            "provider_retry_max_attempts": 0,
            "analysis_worst_case_calls": 0,
            "worst_case_provider_calls": 0,
        }
    raw_attempts = llm.get("max_attempts", llm.get("maxAttempts", 1))
    try:
        max_attempts = max(1, min(int(raw_attempts), 3))
    except (TypeError, ValueError):
        max_attempts = 1
    raw_provider_retries = llm.get(
        "provider_retry_max_attempts",
        llm.get("providerRetryMaxAttempts", 1),
    )
    try:
        provider_retry_max_attempts = max(1, min(int(raw_provider_retries), 10))
    except (TypeError, ValueError):
        provider_retry_max_attempts = 1
    connectivity_calls = 0 if skip_connectivity_test else provider_retry_max_attempts
    analysis_calls = max_attempts * provider_retry_max_attempts
    return {
        "connectivity_test_calls": connectivity_calls,
        "analysis_max_attempts": max_attempts,
        "provider_retry_max_attempts": provider_retry_max_attempts,
        "analysis_worst_case_calls": analysis_calls,
        "worst_case_provider_calls": connectivity_calls + analysis_calls,
    }


def validate_provider_call_budget(
    platform_info: dict[str, Any],
    *,
    skip_connectivity_test: bool,
    provider_call_budget: int,
) -> list[str]:
    if provider_call_budget <= 0:
        return []
    plan = llm_call_plan(platform_info, skip_connectivity_test=skip_connectivity_test)
    planned = plan["worst_case_provider_calls"]
    if planned > provider_call_budget:
        return [
            f"worst-case provider calls {planned} exceed call budget {provider_call_budget}; "
            "reduce RCA_LLM_MAX_ATTEMPTS/RCA_SPRING_AI_RETRY_MAX_ATTEMPTS or use --skip-connectivity-test"
        ]
    return []


def validate_llm_connectivity(
    response: dict[str, Any],
    *,
    allow_disabled: bool,
    max_latency_ms: int,
) -> list[str]:
    errors: list[str] = []
    outcome = str(response.get("outcome") or "")
    if outcome != "completed" and not (allow_disabled and outcome == "skipped"):
        errors.append(f"LLM connectivity test outcome is '{outcome or 'missing'}'")
    latency = non_negative_number(response.get("latency_ms", response.get("latencyMs")))
    if outcome == "completed" and latency is None:
        errors.append("LLM connectivity test has no valid latency_ms")
    elif latency is not None and max_latency_ms > 0 and latency > max_latency_ms:
        errors.append(f"LLM connectivity latency {latency:g}ms exceeds {max_latency_ms}ms")
    response_chars = non_negative_number(response.get("response_chars", response.get("responseChars")))
    if outcome == "completed" and (response_chars is None or response_chars <= 0):
        errors.append("LLM connectivity test returned no response content")
    if contains_sensitive_text(response):
        errors.append("LLM connectivity response appears to contain an unredacted secret-like value")
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


def llm_evidence_ids(report: dict[str, Any]) -> set[str]:
    for section in report.get("evidence", []):
        if not isinstance(section, dict) or section.get("type") != "preprocessed_evidence":
            continue
        payload = section.get("payload")
        if not isinstance(payload, dict):
            return set()
        return {
            str(item.get("evidence_id"))
            for item in payload.get("evidence_catalog", [])
            if isinstance(item, dict) and item.get("evidence_id")
        }
    return set()


def validate_llm_report(
    report: dict[str, Any],
    *,
    expected_statuses: set[str],
    allow_disabled: bool,
    require_usage_metadata: bool = False,
    max_latency_ms: int = 0,
    max_estimated_cost_usd: float = 0,
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
        if analysis.get("prompt_version") != "llm-rca-analyzer/v2":
            errors.append("completed llm_analysis does not use llm-rca-analyzer/v2")
        latency = non_negative_number(analysis.get("latency_ms", analysis.get("latencyMs")))
        if latency is None:
            errors.append("completed llm_analysis has no valid latency_ms")
        elif max_latency_ms > 0 and latency > max_latency_ms:
            errors.append(f"llm_analysis latency {latency:g}ms exceeds {max_latency_ms}ms")
        usage = analysis.get("usage")
        if not isinstance(usage, dict):
            errors.append("completed llm_analysis has no usage object")
        else:
            for key in (
                "usage_available",
                "input_tokens",
                "output_tokens",
                "total_tokens",
                "cost_estimation_enabled",
                "estimated_cost_usd",
            ):
                if key not in usage:
                    errors.append(f"llm_analysis usage has no {key}")
            if not isinstance(usage.get("usage_available"), bool):
                errors.append("llm_analysis usage_available must be boolean")
            if not isinstance(usage.get("cost_estimation_enabled"), bool):
                errors.append("llm_analysis cost_estimation_enabled must be boolean")
            usage_available = usage.get("usage_available") is True
            if require_usage_metadata and not usage_available:
                errors.append("LLM provider did not return required token usage metadata")
            token_values: dict[str, float] = {}
            for key in ("input_tokens", "output_tokens", "total_tokens"):
                value = non_negative_number(usage.get(key))
                if value is None or not value.is_integer():
                    errors.append(f"llm_analysis usage {key} must be a non-negative integer")
                else:
                    token_values[key] = value
            if usage_available and token_values.get("total_tokens", 0) <= 0:
                errors.append("available LLM usage metadata must report total_tokens greater than zero")
            if not usage_available and any(value > 0 for value in token_values.values()):
                errors.append("unavailable LLM usage metadata must not report positive token counts")
            if token_values.get("total_tokens", 0) < max(
                token_values.get("input_tokens", 0),
                token_values.get("output_tokens", 0),
            ):
                errors.append("llm_analysis total_tokens is smaller than an input or output token count")
            estimated_cost = non_negative_number(usage.get("estimated_cost_usd"))
            if estimated_cost is None:
                errors.append("llm_analysis estimated_cost_usd must be a non-negative number")
            elif usage.get("cost_estimation_enabled") is False and estimated_cost > 0:
                errors.append("disabled LLM cost estimation must not report a positive cost")
            if max_estimated_cost_usd > 0:
                if usage.get("cost_estimation_enabled") is not True:
                    errors.append("LLM cost limit requires configured token prices")
                elif estimated_cost is not None and estimated_cost > max_estimated_cost_usd:
                    errors.append(
                        f"llm_analysis estimated cost ${estimated_cost:g} exceeds ${max_estimated_cost_usd:g}"
                    )
        result = analysis.get("result")
        if not isinstance(result, dict):
            errors.append("completed llm_analysis has no result object")
        else:
            has_content = bool(result.get("summary")) or bool(result.get("root_cause_candidates")) or bool(
                result.get("additional_checks")
            )
            if not has_content:
                errors.append("completed llm_analysis result is empty")
            allowed_ids = llm_evidence_ids(report)
            for index, candidate in enumerate(result.get("root_cause_candidates") or []):
                if not isinstance(candidate, dict):
                    continue
                references = candidate.get("supporting_evidence_ids")
                if not isinstance(references, list) or not references:
                    errors.append(f"LLM candidate {index} has no supporting_evidence_ids")
                    continue
                unknown = {str(reference) for reference in references} - allowed_ids
                if unknown:
                    errors.append(f"LLM candidate {index} references unknown evidence IDs: {sorted(unknown)}")
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


def non_negative_number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number >= 0 else None


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
    if args.max_llm_latency_ms < 0:
        print("--max-llm-latency-ms must be zero or greater", file=sys.stderr)
        return 2
    if args.provider_call_budget < 0:
        print("--provider-call-budget must be zero or greater", file=sys.stderr)
        return 2
    if not math.isfinite(args.max_estimated_cost_usd) or args.max_estimated_cost_usd < 0:
        print("--max-estimated-cost-usd must be a finite non-negative number", file=sys.stderr)
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
    call_plan = llm_call_plan(
        platform_info,
        skip_connectivity_test=args.skip_connectivity_test,
    )
    budget_errors = validate_provider_call_budget(
        platform_info,
        skip_connectivity_test=args.skip_connectivity_test,
        provider_call_budget=args.provider_call_budget,
    )
    connectivity_test: dict[str, Any] = {"outcome": "skipped", "reason": "disabled by smoke option"}
    connectivity_errors: list[str] = []
    if config_errors or budget_errors:
        result = {
            "status": "failed",
            "started_at": run_started.isoformat(),
            "base_url": args.base_url,
            "scenario": args.scenario,
            "cluster_id": args.cluster_id,
            "node_name": args.node_name,
            "task": {},
            "report_id": None,
            "llm": platform_info.get("llm", {}),
            "connectivity_test": connectivity_test,
            "llm_analysis": {},
            "limits": {
                "provider_call_budget": args.provider_call_budget,
                "call_plan": call_plan,
                "require_usage_metadata": args.require_usage_metadata,
                "max_llm_latency_ms": args.max_llm_latency_ms,
                "max_estimated_cost_usd": args.max_estimated_cost_usd,
            },
            "errors": config_errors + budget_errors,
        }
        write_json(output_dir / "llm-staging-smoke-result.json", result)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 1
    if not args.skip_connectivity_test:
        connectivity_test = client.request("POST", "/api/llm/test", {"confirmed": True})
        connectivity_errors.extend(
            validate_llm_connectivity(
                connectivity_test,
                allow_disabled=args.allow_disabled,
                max_latency_ms=args.max_llm_latency_ms,
            )
        )

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
                require_usage_metadata=args.require_usage_metadata,
                max_latency_ms=args.max_llm_latency_ms,
                max_estimated_cost_usd=args.max_estimated_cost_usd,
            )
        )

    result = {
        "status": "failed" if config_errors or connectivity_errors or report_errors else "passed",
        "started_at": run_started.isoformat(),
        "base_url": args.base_url,
        "scenario": args.scenario,
        "cluster_id": cluster_id,
        "node_name": args.node_name,
        "task": task,
        "report_id": report_id,
        "llm": platform_info.get("llm", {}),
        "connectivity_test": connectivity_test,
        "llm_analysis": llm_analysis_section(report or {}),
        "limits": {
            "provider_call_budget": args.provider_call_budget,
            "call_plan": call_plan,
            "require_usage_metadata": args.require_usage_metadata,
            "max_llm_latency_ms": args.max_llm_latency_ms,
            "max_estimated_cost_usd": args.max_estimated_cost_usd,
        },
        "errors": config_errors + connectivity_errors + report_errors,
    }
    write_json(output_dir / "llm-staging-smoke-result.json", result)
    if report:
        write_json(output_dir / f"report-{report_id}.json", report)

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 1 if result["status"] != "passed" else 0


if __name__ == "__main__":
    sys.exit(main())
