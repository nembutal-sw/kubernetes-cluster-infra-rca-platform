# Typed Evidence And RCA Quality

Node Agent와 Backend 분석기 사이의 Evidence 계약, 정규화 방식, Rule-based 품질 게이트를 정리합니다.

## Evidence Contract

계약 파일:

```text
web-console/src/main/resources/evidence/collector-evidence-schemas.json
```

- 계약 버전: `collector-evidence/v1`
- 대상: Node Agent의 14개 Collector와 호환 입력(`containerd`, `etcd`, `ebpf`)
- 필드 정의: 타입, alias, 단위, 필수 여부
- 원본 필드: 삭제하지 않음
- 알려진 alias: canonical 필드로 복사 후 타입 정규화
- 잘못된 타입: 추정 변환하지 않고 `evidence_contract`에 기록
- 등록되지 않은 Collector: 분석 입력은 유지하고 계약 상태를 `unknown`으로 기록

Agent는 Collector metadata와 각 수집 결과의 `_schema_version`에 계약 버전을 포함합니다. Backend의
`CollectorEvidenceAdapter`는 detector 실행 전에 number, integer, boolean, string/text, array, object를
검증하고 정규화합니다.

스키마 조회:

```text
GET /api/v1/evidence/schemas
```

## Report Contract Status

RCA Report의 Evidence section에 다음 항목이 추가됩니다.

```json
{
  "type": "evidence_contract",
  "contract": {
    "schema_version": "collector-evidence/v1",
    "status": "valid",
    "invalid_collector_count": 0,
    "unknown_collector_count": 0,
    "collectors": {}
  }
}
```

`invalid`은 알려진 필드의 타입이 계약과 다르다는 의미입니다. 분석 자체를 중단하지는 않지만 해당
필드는 canonical 값으로 사용하지 않으며, 운영자는 원본 Evidence와 Agent 버전을 확인해야 합니다.

## Golden Scenario Quality Gate

기준 데이터:

```text
web-console/src/test/resources/analysis/rule-based-rca-regression-scenarios.json
```

`RuleAnalysisQualityTests`는 실제 `SignalDetectionEngine`을 실행해 다음 지표를 계산합니다.

| Metric | Minimum |
| --- | ---: |
| Micro precision | 0.90 |
| Micro recall | 0.95 |
| Top-1 expected-signal hit rate | 0.90 |
| Top-3 expected-signal hit rate | 0.95 |

결과는 다음 파일로 생성되고 CI artifact로 30일간 보존됩니다.

```text
web-console/target/analysis-quality-report.json
```

로컬 실행:

```bash
cd web-console
mvn -Dtest=RuleAnalysisQualityTests test
```

Detector 또는 fixture를 변경할 때는 예상 신호를 함께 검토합니다. 단순히 gate를 낮추는 방식으로
회귀를 숨기지 않고, false positive와 false negative가 발생한 시나리오를 report에서 확인합니다.

## Production-Like Corpus Gate

운영 형태 회귀 데이터는 golden fixture와 분리합니다.

```text
web-console/src/test/resources/analysis/production-like-evidence-corpus.json
```

이 corpus는 저장소에 보관된 Agent E2E 산출물의 collector 필드 구조를 바탕으로 작성한
`sanitized_production_like_reproduction`입니다. 실제 클러스터 식별자, 주소, 시각, 로그 원문을
포함하지 않으며 `example.invalid`, 문서용 IP 대역, 합성 로그를 사용합니다.

현재 13개 시나리오가 다음 변형을 검증합니다.

- 정상 containerd 및 CRI-O 음성 사례
- 기본 threshold 바로 아래와 정확히 일치하는 경계값
- 디스크 장애 전파, conntrack/DNS, etcd/API Server 복합 장애
- containerd, CRI-O, K3s embedded runtime 및 file collector 차이
- journal 접근 저하와 file fallback
- 잘린 로그와 순서가 뒤바뀐 kernel/eBPF event

`ProductionLikeEvidenceCorpusTests`는 실제 `SignalDetectionEngine`과 Evidence 품질 평가를 실행하고
다음 gate를 적용합니다.

| Metric | Minimum |
| --- | ---: |
| Micro precision | 0.95 |
| Micro recall | 0.95 |
| Positive scenario pass rate | 1.00 |
| Negative scenario pass rate | 1.00 |

결과 파일:

```text
web-console/target/production-like-evidence-corpus-report.json
```

이 수치는 비식별 재현 corpus에 대한 회귀 성능입니다. 실운영 정확도를 의미하지 않습니다. 실제
Precision과 Recall은 비식별 장애 corpus, 정상 negative 표본, 복합 장애, blind evaluation set,
managed Kubernetes canary를 별도로 축적한 뒤 평가합니다.

## Blind Evaluation Corpus Gate

Production-like fixture와 달리 blind evaluation은 analyzer 입력과 정답을 물리적으로 분리합니다.

```text
web-console/src/test/resources/analysis/blind-evaluation-evidence.json
web-console/src/test/resources/analysis/blind-evaluation-labels.json
```

Evidence 파일의 case는 `blind-NNN` ID, platform/runtime/window, collectors만 가집니다. 장애 설명,
expected/allowed/forbidden signal, root cause, alert 이름과 분류 필드는 허용하지 않습니다. Label 파일은
같은 opaque ID와 정답 signal만 가지며 collectors를 포함할 수 없습니다.

`BlindEvaluationCorpusTests`의 실행 순서는 고정되어 있습니다.

1. Evidence 계약과 비식별 상태 검사
2. 19개 case의 detector 결과를 메모리에 확정
3. Sealed label 로드
4. Case ID로만 결과와 label 결합
5. 품질 계산과 비식별 보고서 생성

| Metric | Minimum |
| --- | ---: |
| Micro precision | 0.90 |
| Micro recall | 0.95 |
| Positive scenario pass rate | 0.90 |
| Negative scenario pass rate | 1.00 |
| Top-1 expected-signal hit rate | 0.90 |
| Top-3 expected-signal hit rate | 0.95 |
| Forbidden signal matches | 0 |

결과 파일:

```text
web-console/target/blind-evaluation-report.json
```

보고서에는 raw evidence 대신 case별 expected/actual 결과, 입력·label SHA-256과
`label_loaded_after_detection=true`를 기록합니다. `scripts/verify-blind-evaluation-corpus.py`는 CI에서
입력/label 분리, case coverage, secret marker와 exact label value 누출을 정적으로 검사합니다.

현재 데이터는 저장소 E2E 구조를 바탕으로 만든 비식별 holdout 재현 표본입니다. Analyzer가 label을
입력으로 받지 않는다는 점을 검증하지만, 동일 저장소에서 관리되는 데이터이므로 실운영 정확도나
완전한 외부 blind test로 해석하지 않습니다.
