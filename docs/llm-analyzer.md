# LLM Analyzer

LLM Analyzer는 Rule-based Analyzer를 대체하지 않습니다. 먼저 rule과 preprocessor가 안정적인 JSON을 만들고, LLM은 그 JSON을 읽어 추가 원인 후보와 확인 항목을 제안합니다.

## Provider 구조

Backend는 provider별 SDK에 직접 묶이지 않습니다. 공통 `LlmAnalyzer` 인터페이스를 두고, HTTP adapter가 provider별 요청 형식만 담당합니다.

지원 provider:

- `openai`
- `anthropic`
- `gemini`
- `ollama`
- `openai_compatible`
- `self_hosted`
- `none`

기본값은 `none`이며 `RCA_LLM_ENABLED=false`입니다. 설정이 빠져 있거나 provider가 맞지 않으면 RCA report 생성은 실패하지 않고 `llm_analysis.status = "skipped"`로 남습니다.

## 환경변수

| 이름 | 설명 |
| --- | --- |
| `RCA_LLM_ENABLED` | `true`일 때만 LLM 보조 분석을 실행 |
| `RCA_LLM_PROVIDER` | `none`, `openai`, `anthropic`, `gemini`, `ollama`, `openai_compatible`, `self_hosted` |
| `RCA_LLM_MODEL` | provider model name |
| `RCA_SPRING_AI_CHAT_MODEL` | Spring AI chat model: `openai-sdk`, `anthropic`, `google-genai`, `ollama`, `none` |
| `SPRING_AI_OPENAI_SDK_API_KEY` | OpenAI 또는 OpenAI-compatible API key |
| `SPRING_AI_ANTHROPIC_API_KEY` | Claude API key |
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | Gemini API key |
| `SPRING_AI_OPENAI_SDK_BASE_URL` | OpenAI-compatible 또는 self-hosted endpoint base URL. Docker Compose 기본 env에는 넣지 않고 필요할 때 직접 주입 |
| `SPRING_AI_OLLAMA_BASE_URL` | Ollama endpoint base URL |
| `RCA_LLM_TIMEOUT_SECONDS` | provider 호출 timeout. 기본 `30` |
| `RCA_LLM_MAX_OUTPUT_TOKENS` | 최대 출력 token. 기본 `1800`, 최소 `128` |
| `RCA_LLM_MAX_ATTEMPTS` | 진단 호출 최대 시도 횟수. 기본 `2`, 최대 `3` |
| `RCA_LLM_FAILURE_THRESHOLD` | circuit breaker를 여는 연속 실패 횟수. 기본 `3` |
| `RCA_LLM_COOLDOWN_SECONDS` | circuit breaker 대기 시간. 기본 `60` |

Provider 호출이 실패해도 RCA report 생성은 계속됩니다. 실패 메시지는 `llm_analysis.status = "failed"`에 남기되 token, API key 계열 문자열은 마스킹합니다.

## 예시

OpenAI:

```powershell
$env:RCA_LLM_ENABLED = "true"
$env:RCA_LLM_PROVIDER = "openai"
$env:RCA_LLM_MODEL = "gpt-4.1-mini"
$env:RCA_SPRING_AI_CHAT_MODEL = "openai-sdk"
$env:SPRING_AI_OPENAI_SDK_API_KEY = "..."
```

Claude:

```powershell
$env:RCA_LLM_ENABLED = "true"
$env:RCA_LLM_PROVIDER = "anthropic"
$env:RCA_LLM_MODEL = "claude-3-5-sonnet-latest"
$env:RCA_SPRING_AI_CHAT_MODEL = "anthropic"
$env:SPRING_AI_ANTHROPIC_API_KEY = "..."
```

Gemini:

```powershell
$env:RCA_LLM_ENABLED = "true"
$env:RCA_LLM_PROVIDER = "gemini"
$env:RCA_LLM_MODEL = "gemini-2.0-flash"
$env:RCA_SPRING_AI_CHAT_MODEL = "google-genai"
$env:SPRING_AI_GOOGLE_GENAI_API_KEY = "..."
```

Ollama:

```powershell
$env:RCA_LLM_ENABLED = "true"
$env:RCA_LLM_PROVIDER = "ollama"
$env:RCA_LLM_MODEL = "llama3.1"
$env:RCA_SPRING_AI_CHAT_MODEL = "ollama"
$env:SPRING_AI_OLLAMA_BASE_URL = "http://localhost:11434"
```

Self-hosted 또는 OpenAI-compatible:

```powershell
$env:RCA_LLM_ENABLED = "true"
$env:RCA_LLM_PROVIDER = "self_hosted"
$env:RCA_LLM_MODEL = "local-rca-model"
$env:RCA_SPRING_AI_CHAT_MODEL = "openai-sdk"
$env:SPRING_AI_OPENAI_SDK_BASE_URL = "http://localhost:11434/v1"
```

## 입력 계약

LLM에는 raw collector output을 넘기지 않습니다. `preprocessed_evidence.payload`만 넘깁니다.

입력에는 다음이 포함됩니다.

- alert, node 요약
- collector status
- evidence quality
- incident focus
- component health
- key metrics
- derived signals
- log summary
- log clusters
- command failures
- config findings
- rule-based candidates와 policy-classified actions

## 출력 계약

LLM은 JSON object만 반환해야 합니다.

```json
{
  "summary": {
    "most_likely_cause": "...",
    "confidence": "low|medium|high",
    "reasoning": "..."
  },
  "root_cause_candidates": [],
  "additional_checks": [],
  "action_suggestions": [],
  "risk_notes": []
}
```

Backend는 LLM action suggestion을 그대로 실행하지 않습니다. `action_key`를 Policy Engine에 다시 넣어 `AUTO_SAFE`, `APPROVAL_REQUIRED`, `GITOPS_PR_ONLY`, `NEVER_AUTO_EXECUTE`, `MANUAL_INVESTIGATION`으로 재분류합니다.

Provider 응답은 허용된 네 개 필드만 남기고 문자열 길이, 목록 개수, confidence, action key를 다시 검증합니다. timeout이나 provider 장애가 반복되면 circuit breaker가 열리며 Rule-based RCA는 계속 동작합니다.

읽기 전용 Linux low-level 진단은 `collect_linux_low_level_evidence`, `inspect_kernel_state`, `inspect_network_state`, `inspect_storage_state` 같은 action key로 제안할 수 있습니다. 단, LLM 제안은 항상 `source = "llm"`으로 들어가며 자동 실행 후보가 되지 않습니다.

LLM이 제안한 action은 Policy Engine에 `source = "llm"`으로 전달됩니다. 따라서 조치 문구가 읽기 전용 수집에 해당하더라도 `automation_allowed`는 `false`로 남습니다. LLM은 원인 분석 보조 수단이며, 자동 실행 트리거가 아닙니다.

## 출력 정규화

LLM provider 응답은 신뢰하지 않는 입력으로 취급합니다. Backend는 report에 반영하기 전에 다음 검증을 수행합니다.

- `confidence`는 `low`, `medium`, `high`만 허용하고 나머지는 `low`로 낮춥니다.
- root cause candidate는 최대 5개만 사용합니다.
- `evidence_paths`는 `preprocessed_evidence` 아래 경로만 유지합니다.
- 추가 확인 command는 읽기 전용 형태만 유지하고, shell control operator나 restart/delete/drain 같은 변경 명령은 제거합니다.
- `action_key`가 taxonomy에 없으면 `manual_investigation`으로 낮춥니다.
- action suggestion은 다시 Policy Engine을 통과합니다.

## Provider 검증

현재 자동 테스트는 실제 외부 API를 호출하지 않습니다. 대신 provider별 HTTP request contract를 mock으로 검증합니다.

- OpenAI/OpenAI-compatible: `/chat/completions`, `messages`, `response_format`
- Anthropic: `/v1/messages`, `x-api-key`, `anthropic-version`
- Gemini: `generateContent`, `generationConfig.response_mime_type`

실제 provider API 검증은 staging 환경에서 API key를 주입해 별도로 수행합니다.

## Report 반영

RCA report에는 다음 section이 추가됩니다.

```json
{
  "type": "llm_analysis",
  "analysis": {
    "status": "completed",
    "provider": "self_hosted",
    "model": "local-rca-model",
    "prompt_version": "llm-rca-analyzer/v1",
    "result": {}
  }
}
```

LLM root cause candidate는 `LLM 분석: ...` prefix가 붙어 rule-based candidate와 구분됩니다.
