# Performance Tuning

## RCA Operations Pagination

Report, Incident, Analysis Task 운영 목록은 offset 대신 timestamp와 ID를 결합한 keyset cursor를 사용합니다. 기본 페이지 크기는 50, 최대값은 200입니다. `V19__cursor_pagination_indexes.sql`의 복합 인덱스가 정렬과 cursor 조건을 지원합니다.

UI는 필터 변경 시 첫 페이지로 돌아가며 이전 페이지는 브라우저 메모리의 cursor history로 이동합니다. 자세한 API 계약은 [pagination.md](pagination.md)를 참고합니다.

## Evidence Request Pagination

클러스터별 요청 조회는 최신순으로 반환하며 기본 `limit=100`, 최대 `200`입니다.

```text
GET /api/clusters/{cluster_id}/evidence-requests
  ?node_name=worker-a
  &status=pending
  &before=2026-06-21T00:00:00Z
  &limit=100
```

복합 인덱스:

- `(cluster_id, created_at)`
- `(cluster_id, node_name, status, created_at)`

## Kubernetes API Cache

`KUBERNETES_API_CACHE_TTL_SECONDS`의 기본값은 10초입니다.
최대값은 30초이며 `0`으로 비활성화할 수 있습니다.

짧은 캐시는 여러 collector 요청이 동시에 발생할 때 Node/Pod/metrics 조회가
API Server에 집중되는 것을 줄입니다.

## Log Collection Limits

```text
HOST_LOG_MAX_FILES=12
HOST_LOG_MAX_BYTES_PER_FILE=262144
HOST_LOG_MAX_LINES=80
```

로그는 후보 파일 수, 파일별 byte, 최종 line 수를 모두 제한합니다.

## Evidence Size Limits

```text
AGENT_EVIDENCE_MAX_BYTES=8388608
RCA_EVIDENCE_REQUEST_MAX_BYTES=10485760
RCA_STANDARD_REQUEST_MAX_BYTES=1048576
```

Agent는 큰 collector부터 요약 객체로 교체하고 truncation metadata를 남깁니다.
Backend는 제한을 초과한 요청을 `413 Payload Too Large`로 거부합니다.

## Queue And HA

- 분석 task는 DB lease owner와 만료 시각으로 claim합니다.
- 완료와 실패 갱신은 동일 lease owner만 가능합니다.
- Evidence response는 request row lock으로 멱등 처리합니다.
- Agent spool 재전송은 같은 request ID로 중복 분석 task를 만들지 않습니다.
