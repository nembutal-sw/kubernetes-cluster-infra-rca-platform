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
