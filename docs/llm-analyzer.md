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
| `RCA_SPRING_AI_RETRY_MAX_ATTEMPTS` | 각 LLM 시도 내부의 Spring AI 재시도, 기본 `1`, 최대 `3` |
| `RCA_LLM_FAILURE_THRESHOLD` | circuit breaker 연속 실패 기준, 기본 `3` |
| `RCA_LLM_COOLDOWN_SECONDS` | circuit breaker 대기 시간, 기본 `60` |
| `RCA_LLM_INPUT_COST_PER_MILLION_TOKENS` | 입력 100만 token당 USD 단가, 기본 `0` |
| `RCA_LLM_OUTPUT_COST_PER_MILLION_TOKENS` | 출력 100만 token당 USD 단가, 기본 `0` |

단가가 모두 `0`이면 비용 추정을 비활성화합니다. 모델 가격은 코드에 고정하지 않으며, 실제 provider 계약 단가를 운영 환경에 설정합니다.

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
$env:RCA_LLM_MODEL = "gemini-3.1-flash-lite"
$env:RCA_SPRING_AI_CHAT_MODEL = "google-genai"
$env:SPRING_AI_GOOGLE_GENAI_API_KEY = "..."
```

Spring AI `1.1.8`의 기본 Google SDK보다 최신 authorization key 흐름을 반영하기
위해 Google GenAI Java SDK `1.61.0`을 명시적으로 사용합니다. Spring AI를
업그레이드할 때는 이 override가 계속 필요한지 공식 의존성과 함께 확인합니다.
`AQ.` 형식 키에서 `401 ACCESS_TOKEN_TYPE_UNSUPPORTED`가 발생하면 호출을
재시도하지 말고 AI Studio에서 키의 상태와 연결된 service account/IAM binding을
확인한 뒤 새 키로 smoke를 다시 실행합니다.

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
- derived signal로 만든 `evidence_catalog`와 허용 ID 목록
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
      "supporting_evidence_ids": ["ev-0123456789abcdef"]
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

`supporting_evidence_ids`는 입력의 `evidence_catalog[].evidence_id`만 참조할 수 있습니다. Backend는 검증을 통과한 ID를 사람이 읽을 수 있는 근거와 `evidence_paths`로 해석한 후 report에 저장합니다.

## Validation

Provider 응답은 신뢰하지 않는 입력으로 취급합니다. Backend는 report에 반영하기 전에 다음을 검증합니다.

- 응답에서 JSON object만 추출합니다. 설명 문구나 fenced code block이 섞여 있어도 JSON object만 사용합니다.
- `summary`, `root_cause_candidates`, `action_suggestions`, `additional_checks` 키가 있어야 합니다.
- candidate와 action item은 object여야 합니다.
- root cause candidate는 하나 이상의 `supporting_evidence_ids`를 가져야 하며, catalog에 없는 ID는 거부합니다.
- LLM이 만든 자유 형식 supporting evidence는 신뢰하지 않으며 report 근거로 사용하지 않습니다.
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

Completed response에는 `prompt_version = "llm-rca-analyzer/v2"`, 전체 호출 `latency_ms`, provider usage가 포함됩니다.

```json
{
  "usage": {
    "usage_available": true,
    "input_tokens": 1200,
    "output_tokens": 300,
    "total_tokens": 1500,
    "cost_estimation_enabled": true,
    "estimated_cost_usd": 0.0042,
    "pricing_unit": "usd_per_million_tokens"
  }
}
```

Provider가 usage metadata를 제공하지 않으면 token 값은 `0`, `usage_available=false`로 기록합니다. schema validation 재시도로 여러 응답을 받았다면 report usage는 모든 응답의 합계입니다. 비용은 provider 청구서가 아니라 설정 단가에 따른 추정치입니다.

## LLM Staging Smoke

실제 provider API 검증은 staging 환경에서만 수행합니다. API key는 script 인자로 넘기지 말고 Platform 실행 환경 또는 Kubernetes Secret으로 주입합니다.

```bash
export RCA_BASE_URL=https://rca.example.com
export RCA_ADMIN_USERNAME=admin
export RCA_ADMIN_PASSWORD='...'

python3 scripts/llm-staging-smoke.py \
  --scenario disk-pressure \
  --expected-llm-status completed \
  --skip-connectivity-test \
  --provider-call-budget 1 \
  --require-usage-metadata \
  --max-llm-latency-ms 60000 \
  --max-estimated-cost-usd 0.01
```

기본 실행은 report 생성 전에 `POST /api/llm/test`로 실제 provider 연결을 한 번 확인합니다. 무료 tier처럼 호출량이 제한된 환경에서는 Platform의 `RCA_LLM_MAX_ATTEMPTS=1`, `RCA_SPRING_AI_RETRY_MAX_ATTEMPTS=1`과 `--skip-connectivity-test --provider-call-budget 1`을 함께 사용합니다. smoke는 애플리케이션 재시도와 Spring AI 내부 재시도를 곱한 최악의 호출 수가 예산을 넘으면 provider를 호출하기 전에 중단합니다. 비용 상한을 사용하려면 input/output token 단가가 설정되어 있어야 합니다.

Gemini 예시는 현재 안정 모델인 `gemini-3.1-flash-lite`를 사용합니다. 더 높은 품질이 필요하면 프로젝트에서 사용할 수 있는 `gemini-3.5-flash`를 선택할 수 있습니다. `gemini-2.5-flash-lite`는 신규 사용자에게 `404`와 함께 사용할 수 없는 모델로 응답할 수 있으므로 새 설정에 사용하지 않습니다. 실제 모델 제공 여부, 무료 tier, rate limit은 Google AI Studio의 해당 프로젝트 기준으로 확인합니다.

Gemini 연결 오류는 HTTP 상태를 구분해서 확인합니다. `401`은 API key 원문과 인증 방식을, `404`는 모델 ID와 프로젝트 사용 가능 여부를 확인합니다. `429`는 quota/rate limit, `503`은 provider의 일시적 가용성 문제로 보고 제한된 backoff 후 Rule-based RCA fallback을 유지합니다. 이미지에서 API key를 OCR로 옮기지 말고 Secret 또는 환경 변수에 원문을 직접 주입합니다.

GitHub `Operational Smoke` workflow에서는 기존 수동 실행 입력 수 제한을 유지하기 위해 아래 repository variable로 선택 기준을 설정합니다.

```text
RCA_LLM_SMOKE_REQUIRE_USAGE_METADATA=false
RCA_LLM_SMOKE_MAX_LATENCY_MS=60000
RCA_LLM_SMOKE_MAX_ESTIMATED_COST_USD=0
RCA_LLM_SMOKE_PROVIDER_CALL_BUDGET=0
```

확인 항목:

- `/api/v1/platform/info`의 LLM enabled/provider/model/credential/base URL 상태
- `/api/llm/test` 실제 provider 연결 결과와 latency
- Demo scenario 기반 RCA report 생성
- report evidence의 `llm_analysis.status`
- prompt v2 evidence ID, token usage, latency, 예상 비용 상한
- LLM 결과가 비어 있지 않은지
- LLM-origin action의 `automation_allowed=false`
- 실패 메시지에 secret-like 문자열이 남지 않는지

## LLM Burn-in Aggregation

여러 smoke 실행 결과는 provider를 다시 호출하지 않고 하나의 burn-in report로 합칩니다.

```bash
python3 scripts/llm-burn-in-report.py \
  validation-results/llm-staging-smoke \
  --output validation-results/llm-staging-smoke/burn-in-report.json \
  --minimum-samples 20 \
  --minimum-scenarios 5 \
  --current-p95-ms 60000
```

집계기는 실행 성공률, 장애 시나리오 수, p50/p95 latency, token, 예상 비용과 LLM-origin action 안전성을 함께 확인합니다. `automation_allowed=true`이거나 executable plan을 가진 LLM action이 하나라도 있으면 burn-in은 실패합니다. 표본 20개와 장애 유형 5개를 모두 충족하기 전에는 관측 p95가 낮더라도 `retain_current_threshold`로 판정하며 운영 임계값을 낮추지 않습니다.

### Quota-Aware Campaign

반복 검증은 성공 표본이 가장 적은 장애 유형부터 선택하는 캠페인 실행기로 제한합니다. 관리자 비밀번호는 명령 인자가 아닌 환경 변수로 전달합니다.

```bash
export RCA_ADMIN_PASSWORD='...'

python3 scripts/llm-burn-in-campaign.py \
  --base-url https://rca.example.com \
  --history validation-results/llm-staging-smoke/approved-history \
  --provider-call-budget 1 \
  --output-dir validation-results/llm-staging-smoke/campaign
```

기본 provider 호출 예산은 `0`이며 한 번의 캠페인에서 최대 20회까지만 허용합니다. 각 smoke는 connectivity test를 생략하고 최악의 provider 호출 수를 1로 제한합니다. history 경로가 없거나 결과 파일을 포함하지 않으면 호출 전에 실패하고, smoke 한 건이 실패하면 남은 계획을 중단합니다. 먼저 `--dry-run`으로 선택될 시나리오를 확인합니다.
