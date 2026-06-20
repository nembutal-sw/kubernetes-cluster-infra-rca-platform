# Rule Engine

## Flow

```text
collector evidence
  -> EvidenceFlattener
  -> AnalysisContext
  -> SignalDetector implementations
  -> SignalDetectionEngine
  -> RootCauseCandidateBuilder
  -> RuleBasedRcaAnalyzer
  -> optional LLM explanation
  -> Policy Engine
```

Rule-based detector가 RCA 판단의 기준입니다. LLM candidate는 rule 결과를 대체하지 않으며,
LLM action은 항상 `automation_allowed=false`입니다.

## Signal Contract

Signal은 다음 설명 정보를 포함합니다.

- `signal`, `component`, `severity`
- `confidence`
- `observed`, `threshold`
- `matched_fields`
- `interpretation`, `next_step`
- `supporting_evidence`

threshold detector는 어떤 field가 어떤 기준을 넘었는지 저장합니다. 상태 detector는
`unknown`, `not collected` 같은 모호한 값을 실패로 간주하지 않고 명시적인 `failed`,
`inactive`, `unhealthy`, `not_ready`, `down`, `error`, boolean `false`만 장애로 판단합니다.

## Adding A Detector

1. `SignalDetector`를 구현합니다.
2. 고유한 `id()`를 반환합니다.
3. `detect(AnalysisContext)`에서 자신의 subsystem만 판단합니다.
4. threshold와 matched field를 Signal에 포함합니다.
5. 정상값, 경계값, 오탐 반례 단위 테스트를 추가합니다.

Detector enable/disable 및 cluster별 threshold override는 이후 설정 계층에서
`SignalDetector.enabled()`와 `AnalysisContext`에 연결할 수 있습니다.
