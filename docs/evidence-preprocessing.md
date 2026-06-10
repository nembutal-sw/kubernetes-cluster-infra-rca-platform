# Evidence Preprocessing

LLM Analyzer는 raw collector output을 직접 입력으로 받지 않습니다. Backend는 먼저 evidence를 표준 JSON으로 전처리하고, LLM에는 이 payload만 넘기는 구조를 사용합니다.

## 목적

- raw log 양을 줄입니다.
- 로그 형식 차이를 줄이고 유사 메시지를 묶습니다.
- User-Agent, browser, OS version 같은 RCA에 불필요한 웹 클라이언트 노이즈를 제거합니다.
- IP는 나중에 자동 필터링과 집계를 위해 `client_ips`에 보존합니다.
- 민감 query parameter는 마스킹합니다.

## Report Section

전처리 결과는 RCA report의 `evidence` 배열에 아래 section으로 들어갑니다.

```json
{
  "type": "preprocessed_evidence",
  "payload": {
    "schema_version": "preprocessed-evidence/v2",
    "alert": {},
    "node": {},
    "collector_status": {},
    "evidence_quality": {},
    "incident_focus": {},
    "component_health": {},
    "key_metrics": {},
    "derived_signals": [],
    "log_summary": {},
    "log_clusters": [],
    "command_failures": [],
    "config_findings": {},
    "llm_input_policy": {
      "use_this_payload_only": true,
      "raw_collectors_excluded": true,
      "web_user_agent_removed": true,
      "client_ips_preserved_for_filtering": true
    }
  }
}
```

## RCA Focus

`preprocessed-evidence/v2`부터는 LLM이 우선순위를 잡기 쉽도록 아래 요약을 추가합니다.

- `evidence_quality`: 수집된 collector, alert별 기대 collector, 누락 collector, 실패 collector, command failure 수, log cluster 수
- `incident_focus`: alert 기준으로 봐야 할 collector, 우선 component, 상위 signal, 관측된 failure mode
- `component_health`: component별 `ok`, `warning`, `critical`, `unknown` 상태와 관련 signal
- `log_summary`: severity count, HTTP status family count, 에러 path 집계, 상위 error cluster

이 값들은 원본 collector를 새로 노출하지 않고, 이미 선별한 metric과 signal에서 만든 요약입니다.

## Log Cluster

웹 access log처럼 포맷이 달라도 method, path, status family를 기준으로 유사 로그를 묶습니다.

```json
{
  "fingerprint": "6f3d...",
  "severity": "error",
  "count": 12,
  "normalized_message": "http GET /api/orders/:id status_5xx",
  "client_ips": ["10.0.1.20"],
  "http": {
    "methods": ["GET"],
    "paths": ["/api/orders/:id"],
    "status_codes": [500, 503],
    "status_families": ["5xx"],
    "max_latency_ms": 1200.0
  },
  "sample_lines": []
}
```

## 제거하는 값

아래 값은 LLM 입력에서 제거합니다.

- `user_agent`
- `http_user_agent`
- `ua`
- `browser`
- `browser_version`
- `os_version`
- `device`, `device_type`
- access log 안의 browser/OS User-Agent 문자열

노드 OS 정보인 `node.os_release`, kernel version 같은 infrastructure field는 별도 collector 원본에는 남아 있습니다. 다만 LLM 입력용 preprocessed payload에는 RCA에 필요한 핵심 값만 선별합니다.

## 유지하는 값

- alert name, cluster id, node name
- collector status
- kubelet/containerd/systemd 상태
- disk, inode, memory, process, network, conntrack 주요 수치
- CNI/DNS config 요약
- command failure stderr/stdout excerpt
- log severity, fingerprint, count, source, sample line
- IP 주소

## 민감값 처리

URL query parameter 중 `token`, `password`, `secret`, `authorization`, `api_key` 계열은 `<redacted>`로 바꿉니다.

LLM 연결 시에는 `llm_input_policy.use_this_payload_only`를 지켜 raw collector 전체를 함께 보내지 않습니다.
