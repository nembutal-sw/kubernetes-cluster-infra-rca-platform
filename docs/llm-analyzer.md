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

Gemini 예시는 2026-07-27 기준 Google 공식 model catalog에서 stable로 제공되는
`gemini-3.1-flash-lite`를 사용합니다. 모델 제공 여부, lifecycle, 무료 tier와 rate limit은
프로젝트와 시점에 따라 달라질 수 있으므로 배포 전
[Google Gemini model catalog](https://ai.google.dev/gemini-api/docs/models)와
[Gemini API changelog](https://ai.google.dev/gemini-api/docs/changelog)를 확인합니다.
특정 모델의 과거 응답 상태를 영구 호환 계약으로 간주하지 않습니다.

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
  --minimum-time-buckets 3 \
  --time-bucket-hours 8 \
  --current-p95-ms 60000
```

집계기는 실행 성공률, 장애 시나리오 수, 시간 분산, 전체 및 시나리오별 p50/p95 latency, token, 예상 비용과 LLM-origin action 안전성을 함께 확인합니다. `automation_allowed=true`이거나 executable plan을 가진 LLM action이 하나라도 있으면 burn-in은 실패합니다. 성공 timestamp가 없는 표본도 실패로 처리합니다. 표본 20개, 장애 유형 5개, 8시간 구간 3개를 모두 충족하기 전에는 관측 p95가 낮더라도 `retain_current_threshold`로 판정하며 운영 임계값을 낮추지 않습니다.

### Quota-Aware Campaign

반복 검증은 성공 표본이 가장 적은 장애 유형부터 선택하는 캠페인 실행기로 제한합니다. 관리자 비밀번호는 명령 인자가 아닌 환경 변수로 전달합니다.

```bash
export RCA_ADMIN_PASSWORD='...'

python3 scripts/llm-burn-in-campaign.py \
  --base-url https://rca.example.com \
  --history validation-results/llm-staging-smoke/approved-history \
  --planning-baseline config/llm-burn-in-planning-baseline.json \
  --provider-call-budget 1 \
  --target-time-buckets 3 \
  --time-bucket-hours 8 \
  --require-new-time-bucket \
  --output-dir validation-results/llm-staging-smoke/campaign
```

기본 provider 호출 예산은 `0`이며 한 번의 캠페인에서 최대 20회까지만 허용합니다. 각 smoke는 connectivity test를 생략하고 최악의 provider 호출 수를 1로 제한합니다. `--require-new-time-bucket`을 사용하면 요청 예산과 관계없이 실행당 최대 1회로 줄이고, 현재 8시간 구간에 성공 표본이 있으면 `waiting_for_time_bucket`으로 종료합니다. history 경로가 없거나 결과 파일을 포함하지 않으면 호출 전에 실패하고, smoke 한 건이 실패하면 남은 계획을 중단합니다. 먼저 `--dry-run`으로 선택될 시나리오를 확인합니다.

### Planning Baseline

로컬에서 검증한 표본을 공개 저장소의 workflow 계획에 반영해야 할 때는 원본 report 대신 planning baseline을 사용합니다.

```bash
python3 scripts/llm-burn-in-planning-baseline.py \
  validation-results/llm-staging-smoke/approved-history \
  --output config/llm-burn-in-planning-baseline.json \
  --time-bucket-hours 8
```

baseline에는 result/report SHA-256, 시나리오, timestamp, LLM action 개수와 안전성만 저장합니다. URL, cluster ID, node 이름, evidence 본문, credential은 저장하지 않습니다. 생성기는 통과 표본의 sibling report를 다시 읽어 LLM action의 `automation_allowed=false`, execution plan의 `executable=false`를 확인하며 unsafe action이 있으면 baseline을 만들지 않습니다.

이 파일의 용도는 provider 호출 순서와 시간 구간 중복 방지뿐입니다. `readiness_eligible=false`이며 burn-in report의 표본 수, 성공률, latency, token, 비용 또는 SLO readiness 계산에는 포함되지 않습니다. Artifact에 원본 표본이 쌓이면 sample hash로 중복을 제거한 뒤 planning count에 합칩니다.

### Manual Burn-in Workflow

GitHub Actions의 `LLM Burn-in` workflow는 수동 실행만 허용하며 기본값은 `dry_run=true`입니다. 실제 호출은 다음 조건을 모두 만족해야 시작합니다.

- `dry_run=false`
- `confirm_live_calls=true`
- `provider_call_budget=1`
- `change_reference`에 승인 ticket 또는 change 번호 입력
- `RCA_SMOKE_PASSWORD` repository secret 설정
- 내부 endpoint를 사용할 경우 `TAILSCALE_AUTHKEY` repository secret 설정
- `RCA_LLM_BURN_IN_HISTORY_RUN_ID` repository variable에 최신 canonical run ID 설정

`dry_run=true` 실행은 비보호 `llm-burn-in-preview` Environment를 사용합니다. 실제 호출만 `llm-burn-in` GitHub Environment로 라우팅하므로 이 Environment에는 required reviewer를 설정합니다. 실제 호출은 저장소 기본 branch에서만 가능하고, 같은 8시간 구간에 누적 성공 표본이 있으면 호출하지 않습니다. 동시 실행은 하나로 제한하며 예약 실행은 제공하지 않습니다.

기본 `runner=github-hosted`는 공개 Actions runner를 사용하며 내부 endpoint에 접근할 때 Tailscale을 연결합니다. 전용 데모 서버에서 실행할 때는 Python 3.11 이상을 미리 설치하고 `runner=self-hosted-rca-demo`, `base_url=http://127.0.0.1:18081`, `use_tailscale=false`를 사용합니다. Self-hosted 실제 호출은 loopback HTTP endpoint만 허용하므로 runner가 다른 내부 시스템을 직접 호출하는 용도로 확장되지 않습니다. 배포판별 바이너리를 내려받는 `setup-python`은 GitHub-hosted runner에만 적용합니다.

첫 실행은 `dry_run=true`, `history_run_id` 공란으로 계획만 확인합니다. repository의 planning baseline은 이 단계부터 적용됩니다. 최초 live 표본은 canonical history가 없을 때만 `initialize_history=true`로 명시적으로 허용합니다. 이후에는 성공한 Actions run ID를 `RCA_LLM_BURN_IN_HISTORY_RUN_ID` repository variable에 저장합니다. `history_run_id` 입력값이 있으면 variable보다 우선하며, 둘 다 없고 `initialize_history=false`인 live 실행은 provider 호출 전에 거부됩니다.

```bash
gh variable set RCA_LLM_BURN_IN_HISTORY_RUN_ID --body <canonical-run-id>
```

Workflow summary에는 실제 readiness 표본·scenario·시간 구간, 현재 UTC bucket, provider 호출 허용 여부와 다음 호출 가능 시각이 표시됩니다. 새 cumulative artifact가 정상 생성되면 repository variable을 해당 run ID로 갱신합니다. Workflow 종류가 다르거나 수동 실행이 아닌 run은 history로 받아들이지 않습니다.

실패한 run은 provider 응답과 sibling report가 artifact에 남아 있고, 실패 원인이 현재 allowlist에 있는 검증기 오탐뿐일 때만 `scripts/llm-burn-in-revalidate.py`로 오프라인 재검증합니다. 현재 검증기로 LLM 구성, 호출 예산, task 완료, usage, latency, evidence 참조, action 안전성과 비밀정보 검사를 모두 다시 통과해야 하며 provider는 재호출하지 않습니다. Provider 오류, task 실패, 안전성 위반 등 다른 오류가 하나라도 있으면 history 편입을 거부합니다.

`scripts/llm-burn-in-history.py`는 이전 artifact와 현재 결과를 content hash로 중복 제거하고 `llm-burn-in-history/v1` manifest를 만듭니다. 절대 경로는 manifest에 기록하지 않으며, 통과 표본의 timestamp와 sibling RCA report가 빠지면 검증에 실패합니다. 실패 표본은 숨기지 않고 history에 남아 다음 aggregate report의 신뢰도 계산에 포함됩니다. Artifact에는 노드와 운영 evidence가 포함될 수 있으므로 repository 접근 권한을 제한하고 기본 30일 보존 기간을 조직 정책에 맞게 조정합니다.
