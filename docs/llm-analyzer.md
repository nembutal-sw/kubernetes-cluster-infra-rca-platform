# LLM Analyzer

LLM Analyzer는 Rule-based Analyzer를 대체하지 않습니다. Backend는 먼저 rule 기반으로 RCA 후보, evidence, policy action을 만들고, LLM에는 전처리된 JSON만 넘겨 보조 설명과 추가 확인 항목을 받습니다.

LLM이 실패하거나 응답 형식이 틀려도 RCA report 생성은 중단되지 않습니다. 이 경우 `llm_analysis.status`는 `failed` 또는 `skipped`로 남고, rule-based report는 그대로 유지됩니다.

## Providers

지원 provider:

| Provider | Spring AI chat model |
| --- | --- |
| `openai` | `openai-sdk` |
| `anthropic`, `claude` | `anthropic` |
| `gemini`, `google`, `google-genai` | `google-genai` |
| `ollama` | `ollama` |
| `openai_compatible` | `openai-sdk` |
| `self_hosted` | `openai-sdk` |
| `none` | `none` |

기본값은 `RCA_LLM_ENABLED=false`, `RCA_LLM_PROVIDER=none`입니다.

## Environment

| Env | 설명 |
| --- | --- |
| `RCA_LLM_ENABLED` | `true`일 때만 LLM 보조 분석 실행 |
| `RCA_LLM_PROVIDER` | provider 이름 |
| `RCA_LLM_MODEL` | provider model name |
| `RCA_SPRING_AI_CHAT_MODEL` | Spring AI chat model |
| `SPRING_AI_OPENAI_SDK_API_KEY` | OpenAI 또는 OpenAI-compatible API key |
| `SPRING_AI_ANTHROPIC_API_KEY` | Claude API key |
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | Gemini API key |
| `SPRING_AI_OPENAI_SDK_BASE_URL` | OpenAI-compatible 또는 self-hosted endpoint |
| `SPRING_AI_OLLAMA_BASE_URL` | Ollama endpoint |
| `RCA_LLM_TIMEOUT_SECONDS` | provider 호출 timeout, 기본 `30` |
| `RCA_LLM_MAX_OUTPUT_TOKENS` | 최대 출력 token, 기본 `1800`, 최소 `128` |
| `RCA_LLM_MAX_ATTEMPTS` | 최대 재시도 횟수, 기본 `2`, 최대 `3` |
| `RCA_LLM_FAILURE_THRESHOLD` | circuit breaker 연속 실패 기준, 기본 `3` |
| `RCA_LLM_COOLDOWN_SECONDS` | circuit breaker 대기 시간, 기본 `60` |

## Examples

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

## Input Contract

LLM에는 raw collector output을 직접 넘기지 않습니다. Backend가 만든 `preprocessed_evidence.payload`만 전달합니다.

입력에는 다음 정보가 포함됩니다.

- cluster, node, alert 정보
- collector status
- evidence quality와 quality gate
- derived signals
- rule-based root cause candidates
- policy-classified recommended actions
- redaction이 적용된 collector 요약

민감정보는 LLM 호출 전에 redaction을 거칩니다.

## Output Contract

LLM은 JSON object를 반환해야 합니다. 허용되는 필드는 아래 네 개입니다.

```json
{
  "summary": "short diagnostic summary",
  "root_cause_candidates": [
    {
      "cause": "candidate cause",
      "confidence": "low|medium|high",
      "supporting_evidence": ["evidence line"]
    }
  ],
  "action_suggestions": [
    {
      "action_key": "inspect_storage_state",
      "action": "Inspect filesystem and inode state.",
      "reason": "DiskPressure is present."
    }
  ],
  "additional_checks": ["df -hT; df -i"]
}
```

`summary`는 문자열 또는 object를 허용합니다. object인 경우 Backend는 `most_likely_cause`, `confidence`, `reasoning` 값을 사람이 읽기 쉬운 문자열로 정리합니다.

## Validation

Provider 응답은 신뢰하지 않는 입력으로 취급합니다. Backend는 report에 반영하기 전에 다음을 검증합니다.

- 응답에서 JSON object만 추출합니다. 설명 문구나 fenced code block이 섞여 있어도 JSON object만 사용합니다.
- `summary`, `root_cause_candidates`, `action_suggestions`, `additional_checks` 키가 있어야 합니다.
- candidate와 action item은 object여야 합니다.
- `additional_checks` item은 문자열이어야 합니다.
- `confidence`는 `low`, `medium`, `high`만 허용하며, 그 외 값은 `low`로 낮춥니다.
- `action_key` 형식이 맞지 않으면 `manual_investigation`으로 낮춥니다.
- 최대 candidate/action/check 개수와 문자열 길이를 제한합니다.
- schema가 틀리거나 usable content가 없으면 LLM 분석은 `failed`로 처리합니다.

LLM-origin action은 항상 `source = "llm"`입니다. Policy Engine은 LLM 제안을 자동 실행 후보로 만들지 않으며, `automation_allowed=false`와 non-executable action plan을 유지합니다.

## Web Console Diagnostics

Settings 화면에서 LLM 상태와 설정 가이드를 확인할 수 있습니다.

- `/api/llm/diagnostics`: 현재 provider, model, Spring AI chat model, credential/base URL 설정 상태
- `/api/llm/setup`: provider별 필요한 env 이름, Spring AI chat model, model 예시
- `/api/llm/test`: admin/operator가 확인 후 실행하는 실제 provider 연결 테스트

Web Console은 API key 값을 입력받거나 저장하지 않습니다. 운영에서는 env 파일, Docker/Compose 환경 변수, Kubernetes Secret 또는 외부 Secret Manager를 사용합니다. 설정 변경 후에는 Platform process 또는 Pod 재시작이 필요합니다.

## Failure Behavior

LLM 호출 실패, timeout, schema validation 실패는 RCA pipeline 실패가 아닙니다.

- provider 장애: `llm_analysis.status = "failed"`
- LLM disabled: `llm_analysis.status = "skipped"`
- 연속 실패 기준 초과: circuit breaker open
- 실패 메시지: token, API key, authorization 계열 문자열 redaction

Completed response에는 `prompt_version = "llm-rca-analyzer/v1"`이 포함됩니다.

## LLM Staging Smoke

실제 provider API 검증은 staging 환경에서만 수행합니다. API key는 script 인자로 넘기지 말고 Platform 실행 환경 또는 Kubernetes Secret으로 주입합니다.

```bash
export RCA_BASE_URL=https://rca.example.com
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='...'

python3 scripts/llm-staging-smoke.py \
  --scenario disk-pressure \
  --expected-llm-status completed
```

확인 항목:

- `/api/v1/platform/info`의 LLM enabled/provider/model/credential/base URL 상태
- Demo scenario 기반 RCA report 생성
- report evidence의 `llm_analysis.status`
- LLM 결과가 비어 있지 않은지
- LLM-origin action의 `automation_allowed=false`
- 실패 메시지에 secret-like 문자열이 남지 않는지
