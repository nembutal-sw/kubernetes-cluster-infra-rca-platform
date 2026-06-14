from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Protocol

from backend.app.config import LlmSettings


PROMPT_VERSION = "llm-rca-analyzer/v1"
SUPPORTED_PROVIDERS = {"openai", "anthropic", "gemini", "openai_compatible", "self_hosted"}
SUPPORTED_ACTION_KEYS = {
    "collect_more_evidence",
    "collect_linux_low_level_evidence",
    "inspect_kernel_state",
    "inspect_network_state",
    "inspect_storage_state",
    "restart_kubelet",
    "restart_containerd",
    "cleanup_disk",
    "cordon_node",
    "open_gitops_pr",
    "reboot_node",
    "manual_hardware_check",
    "manual_investigation",
}
MAX_LLM_CANDIDATES = 5
MAX_LLM_CHECKS = 10
MAX_LLM_ACTIONS = 10
MAX_LLM_RISK_NOTES = 10
UNSAFE_COMMAND_PATTERNS = [
    r"\brm\s+-",
    r"\breboot\b",
    r"\bshutdown\b",
    r"\bpoweroff\b",
    r"\bhalt\b",
    r"\bmkfs\b",
    r"\bdd\s+",
    r"\bsystemctl\s+(restart|stop|start|kill|disable|mask)\b",
    r"\bkubectl\s+(delete|drain|cordon|uncordon|apply|patch|replace|scale|rollout)\b",
    r"\bcrictl\s+(rm|rmp|stop|kill)\b",
    r"\bdocker\s+(rm|stop|kill|restart)\b",
    r"\bmount\s+-o\s+remount\b",
    r"\bsysctl\s+-w\b",
    r"\bsed\s+-i\b",
    r"\btee\s+",
]
SHELL_CONTROL_TOKENS = [";", "&&", "||", "`", "$(", ">", "<"]
SENSITIVE_ERROR_PATTERNS = [
    re.compile(r"(?i)(authorization:\s*bearer\s+)[^\s,;]+"),
    re.compile(r"(?i)(api[-_]?key[\"'\s:=]+)[^\"'\s,;]+"),
    re.compile(r"(?i)(access[-_]?token[\"'\s:=]+)[^\"'\s,;]+"),
    re.compile(r"(?i)(token[\"'\s:=]+)[^\"'\s,;]+"),
    re.compile(r"(?i)(key=)[^&\s]+"),
]


class LlmClient(Protocol):
    def complete_json(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]: ...


class LlmAnalyzer:
    def __init__(
        self,
        settings: LlmSettings,
        client: LlmClient | None = None,
        disabled_reason: str | None = None,
    ) -> None:
        self._settings = settings
        self._client = client
        self._disabled_reason = disabled_reason

    @property
    def enabled(self) -> bool:
        return self._client is not None and self._disabled_reason is None

    def analyze(self, preprocessed_evidence: dict[str, Any], rule_context: dict[str, Any]) -> dict[str, Any]:
        base = {
            "status": "skipped",
            "provider": self._settings.provider,
            "model": self._settings.model,
            "prompt_version": PROMPT_VERSION,
        }
        if self._disabled_reason is not None:
            return {**base, "reason": self._disabled_reason}
        if self._client is None:
            return {**base, "reason": "llm analyzer disabled"}

        user_payload = {
            "task": "Analyze Kubernetes node/Linux infrastructure RCA evidence.",
            "preprocessed_evidence": preprocessed_evidence,
            "rule_context": rule_context,
            "output_schema": _output_schema(),
        }
        try:
            result = self._client.complete_json(_system_prompt(), user_payload)
        except Exception as exc:  # noqa: BLE001 - LLM failure must not break RCA report creation.
            return {
                **base,
                "status": "failed",
                "error": _safe_error_message(exc),
            }

        return {
            **base,
            "status": "completed",
            "result": _normalize_result(result),
        }


class HttpLlmClient:
    def __init__(self, settings: LlmSettings) -> None:
        self._settings = settings

    def complete_json(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
        provider = self._settings.provider
        if provider == "openai":
            return self._complete_openai(system_prompt, user_payload, "https://api.openai.com/v1")
        if provider in {"openai_compatible", "self_hosted"}:
            base_url = self._settings.base_url
            if base_url is None:
                raise ValueError("RCA_LLM_BASE_URL is required for openai_compatible/self_hosted providers")
            return self._complete_openai(system_prompt, user_payload, base_url)
        if provider == "anthropic":
            return self._complete_anthropic(system_prompt, user_payload)
        if provider == "gemini":
            return self._complete_gemini(system_prompt, user_payload)
        raise ValueError(f"unsupported LLM provider: {provider}")

    def _complete_openai(self, system_prompt: str, user_payload: dict[str, Any], default_base_url: str) -> dict[str, Any]:
        model = _required(self._settings.model, "RCA_LLM_MODEL")
        endpoint = _chat_completions_endpoint(self._settings.base_url or default_base_url)
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": _json(user_payload)},
            ],
            "temperature": 0.1,
            "max_tokens": self._settings.max_output_tokens,
            "response_format": {"type": "json_object"},
        }
        headers = {"Content-Type": "application/json"}
        if self._settings.api_key:
            headers["Authorization"] = f"Bearer {self._settings.api_key}"
        response = _post_json(endpoint, headers, body, self._settings.timeout_seconds)
        content = response["choices"][0]["message"]["content"]
        return _parse_json_object(content)

    def _complete_anthropic(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
        model = _required(self._settings.model, "RCA_LLM_MODEL")
        api_key = _required(self._settings.api_key, "RCA_LLM_API_KEY")
        endpoint = self._settings.base_url or "https://api.anthropic.com/v1/messages"
        body = {
            "model": model,
            "max_tokens": self._settings.max_output_tokens,
            "temperature": 0.1,
            "system": system_prompt,
            "messages": [{"role": "user", "content": _json(user_payload)}],
        }
        response = _post_json(
            endpoint,
            {
                "Content-Type": "application/json",
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
            body,
            self._settings.timeout_seconds,
        )
        text_parts = [
            item.get("text", "")
            for item in response.get("content", [])
            if isinstance(item, dict) and item.get("type") == "text"
        ]
        return _parse_json_object("\n".join(text_parts))

    def _complete_gemini(self, system_prompt: str, user_payload: dict[str, Any]) -> dict[str, Any]:
        model = _required(self._settings.model, "RCA_LLM_MODEL")
        api_key = _required(self._settings.api_key, "RCA_LLM_API_KEY")
        endpoint = self._settings.base_url or (
            "https://generativelanguage.googleapis.com/v1beta/models/"
            f"{urllib.parse.quote(model, safe='')}:generateContent?key={urllib.parse.quote(api_key, safe='')}"
        )
        body = {
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": f"{system_prompt}\n\n{_json(user_payload)}"}],
                }
            ],
            "generationConfig": {
                "temperature": 0.1,
                "maxOutputTokens": self._settings.max_output_tokens,
                "response_mime_type": "application/json",
            },
        }
        response = _post_json(endpoint, {"Content-Type": "application/json"}, body, self._settings.timeout_seconds)
        parts = response["candidates"][0]["content"].get("parts", [])
        text = "\n".join(part.get("text", "") for part in parts if isinstance(part, dict))
        return _parse_json_object(text)


def build_llm_analyzer(settings: LlmSettings) -> LlmAnalyzer:
    provider = settings.provider.strip().lower()
    normalized_settings = LlmSettings(
        provider=provider,
        model=settings.model,
        api_key=settings.api_key,
        base_url=settings.base_url,
        timeout_seconds=settings.timeout_seconds,
        max_output_tokens=settings.max_output_tokens,
    )
    if provider in {"", "disabled", "none", "off"}:
        return LlmAnalyzer(normalized_settings, disabled_reason="llm analyzer disabled by RCA_LLM_PROVIDER")
    if provider not in SUPPORTED_PROVIDERS:
        return LlmAnalyzer(normalized_settings, disabled_reason=f"unsupported LLM provider: {provider}")
    if settings.model is None:
        return LlmAnalyzer(normalized_settings, disabled_reason="RCA_LLM_MODEL is not configured")
    if provider in {"openai", "anthropic", "gemini"} and settings.api_key is None:
        return LlmAnalyzer(normalized_settings, disabled_reason="RCA_LLM_API_KEY is not configured")
    if provider in {"openai_compatible", "self_hosted"} and settings.base_url is None:
        return LlmAnalyzer(normalized_settings, disabled_reason="RCA_LLM_BASE_URL is not configured")
    return LlmAnalyzer(normalized_settings, client=HttpLlmClient(normalized_settings))


def _system_prompt() -> str:
    return (
        "You are an RCA analyst for Kubernetes node and Linux infrastructure incidents. "
        "Use only the provided preprocessed evidence. Do not invent missing facts. "
        "Do not execute or imply automatic remediation. "
        "Return valid JSON only. "
        "Write all user-facing RCA text in English. "
        "Classify confidence as low, medium, or high. "
        "Action suggestions must be diagnostic or operator-reviewed proposals."
        " Evidence paths must start with preprocessed_evidence."
        " Diagnostic commands must be read-only and must not contain shell control operators."
    )


def _output_schema() -> dict[str, Any]:
    return {
        "summary": {
            "most_likely_cause": "string",
            "confidence": "low|medium|high",
            "reasoning": "short string",
        },
        "root_cause_candidates": [
            {
                "cause": "string",
                "confidence": "low|medium|high",
                "supporting_signals": ["signal_name"],
                "evidence_paths": ["preprocessed_evidence.key_metrics..."],
            }
        ],
        "additional_checks": [
            {
                "component": "string",
                "reason": "string",
                "command": "read-only diagnostic command or empty string",
            }
        ],
        "action_suggestions": [
            {
                "action_key": "collect_more_evidence|collect_linux_low_level_evidence|inspect_kernel_state|inspect_network_state|inspect_storage_state|restart_kubelet|restart_containerd|restart_container_runtime|cleanup_disk|cordon_node|open_gitops_pr|reboot_node|manual_hardware_check|manual_investigation",
                "action": "string",
                "reason": "string",
            }
        ],
        "risk_notes": ["string"],
    }


def _normalize_result(result: dict[str, Any]) -> dict[str, Any]:
    summary = result.get("summary") if isinstance(result.get("summary"), dict) else {}
    return {
        "summary": {
            "most_likely_cause": _safe_text(summary.get("most_likely_cause"), 300),
            "confidence": _confidence(summary.get("confidence")),
            "reasoning": _safe_text(summary.get("reasoning"), 800),
        },
        "root_cause_candidates": _normalize_candidates(result.get("root_cause_candidates")),
        "additional_checks": _normalize_additional_checks(result.get("additional_checks")),
        "action_suggestions": _normalize_action_suggestions(result.get("action_suggestions")),
        "risk_notes": _normalize_risk_notes(result.get("risk_notes")),
    }


def _confidence(value: Any) -> str:
    value = str(value or "low").lower()
    return value if value in {"low", "medium", "high"} else "low"


def _normalize_candidates(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    candidates = []
    for item in value:
        if not isinstance(item, dict):
            continue
        cause = _safe_text(item.get("cause"), 300)
        if not cause:
            continue
        candidates.append(
            {
                "cause": cause,
                "confidence": _confidence(item.get("confidence")),
                "supporting_signals": _safe_string_list(item.get("supporting_signals"), 20, 120),
                "evidence_paths": _evidence_paths(item.get("evidence_paths")),
            }
        )
    return candidates[:MAX_LLM_CANDIDATES]


def _normalize_additional_checks(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    checks = []
    for item in value:
        if not isinstance(item, dict):
            continue
        component = _safe_text(item.get("component"), 80)
        reason = _safe_text(item.get("reason"), 300)
        command = _read_only_command(item.get("command"))
        if not component and not reason and not command:
            continue
        checks.append({"component": component, "reason": reason, "command": command})
    return checks[:MAX_LLM_CHECKS]


def _normalize_action_suggestions(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    actions = []
    for item in value:
        if not isinstance(item, dict):
            continue
        action = _safe_text(item.get("action"), 300)
        reason = _safe_text(item.get("reason"), 300)
        if not action or not reason:
            continue
        action_key = str(item.get("action_key") or "manual_investigation").strip()
        if action_key not in SUPPORTED_ACTION_KEYS:
            action_key = "manual_investigation"
        actions.append({"action_key": action_key, "action": action, "reason": reason})
    return actions[:MAX_LLM_ACTIONS]


def _normalize_risk_notes(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [_safe_text(item, 300) for item in value if _safe_text(item, 300)][:MAX_LLM_RISK_NOTES]


def _evidence_paths(value: Any) -> list[str]:
    paths = _safe_string_list(value, 20, 180)
    return [path for path in paths if path == "preprocessed_evidence" or path.startswith("preprocessed_evidence.")]


def _safe_string_list(value: Any, limit: int, item_limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    result = []
    for item in value:
        text = _safe_text(item, item_limit)
        if text:
            result.append(text)
    return result[:limit]


def _safe_text(value: Any, limit: int) -> str:
    if value is None:
        return ""
    text = re.sub(r"\s+", " ", str(value)).strip()
    if len(text) <= limit:
        return text
    return text[:limit] + "...<truncated>"


def _read_only_command(value: Any) -> str:
    command = _safe_text(value, 300)
    if not command:
        return ""
    lowered = command.lower()
    if any(token in command for token in SHELL_CONTROL_TOKENS):
        return ""
    if any(re.search(pattern, lowered) for pattern in UNSAFE_COMMAND_PATTERNS):
        return ""
    return command


def _chat_completions_endpoint(base_url: str) -> str:
    base_url = base_url.rstrip("/")
    if base_url.endswith("/chat/completions"):
        return base_url
    return f"{base_url}/chat/completions"


def _post_json(endpoint: str, headers: dict[str, str], body: dict[str, Any], timeout_seconds: float) -> dict[str, Any]:
    request = urllib.request.Request(
        endpoint,
        data=_json(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:  # noqa: S310 - endpoint is user config.
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"LLM provider HTTP {exc.code}: {_redact_sensitive_text(error_body[:500])}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"LLM provider request failed: {exc.reason}") from exc
    try:
        return json.loads(payload)
    except json.JSONDecodeError as exc:
        raise RuntimeError("LLM provider returned invalid JSON") from exc


def _parse_json_object(text: str) -> dict[str, Any]:
    text = text.strip()
    if text.startswith("```"):
        text = rewrap_json_fence(text)
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start == -1 or end == -1 or end <= start:
            raise
        value = json.loads(text[start : end + 1])
    if not isinstance(value, dict):
        raise ValueError("LLM response must be a JSON object")
    return value


def rewrap_json_fence(text: str) -> str:
    lines = text.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].startswith("```"):
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _required(value: str | None, name: str) -> str:
    if value is None:
        raise ValueError(f"{name} is required")
    return value


def _safe_error_message(exc: Exception) -> str:
    return _redact_sensitive_text(_safe_text(str(exc), 500)) or exc.__class__.__name__


def _redact_sensitive_text(text: str) -> str:
    redacted = text
    for pattern in SENSITIVE_ERROR_PATTERNS:
        redacted = pattern.sub(r"\1<redacted>", redacted)
    return redacted
