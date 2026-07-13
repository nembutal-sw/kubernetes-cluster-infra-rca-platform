# Cursor Pagination

대량 운영 데이터 조회는 기존 배열 API와 별도로 versioned cursor API를 제공합니다. 기존 endpoint는 호환성을 위해 유지하며 Web Console의 Reports, Incidents, Pipeline 목록은 cursor API를 사용합니다.

## Endpoints

| Resource | Endpoint | Status values |
| --- | --- | --- |
| Reports | `GET /api/v1/rca/reports` | `completed`, `failed` |
| Incidents | `GET /api/v1/rca/incidents` | `open`, `resolved` |
| Analysis tasks | `GET /api/v1/rca/analysis-tasks` | `queued`, `processing`, `retry_wait`, `completed`, `skipped`, `dead_letter` |

공통 query parameter:

| Parameter | Description |
| --- | --- |
| `q` | ID, cluster, node, alert, 원인 또는 오류 메시지 검색. 최대 200자 |
| `cluster_id` | 정확히 일치하는 cluster 필터 |
| `status` | resource별 enum 상태 필터 |
| `limit` | 기본 50, 최소 1, 최대 200 |
| `cursor` | 이전 응답의 `next_cursor`. 내부 값을 해석하거나 수정하지 않음 |

응답 예시:

```json
{
  "items": [],
  "next_cursor": "MjAyNi0wNy0xM1QwMDowMDowMFoKcmVwb3J0LTE",
  "has_more": true,
  "total": 1240,
  "limit": 50
}
```

`has_more=true`일 때만 `next_cursor`를 다음 요청에 전달합니다. 필터가 변경되면 cursor를 버리고 첫 페이지부터 다시 조회합니다. 잘못된 cursor와 200자를 넘는 검색어는 `422 Unprocessable Entity`로 거절합니다.

## Ordering

- Reports: `created_at DESC, report_id DESC`
- Incidents: `last_seen_at DESC, incident_id DESC`
- Analysis tasks: `created_at DESC, task_id DESC`

timestamp와 ID를 함께 사용하므로 같은 시각에 여러 row가 생성돼도 페이지 사이에 중복되거나 누락되지 않습니다. 검색의 `%`, `_`는 wildcard가 아닌 일반 문자로 처리합니다.

## Database

`V19__cursor_pagination_indexes.sql`은 다음 복합 인덱스를 추가합니다.

- `rca_reports(created_at, report_id)`
- `incidents(last_seen_at, incident_id)`
- `rca_analysis_tasks(created_at, task_id)`

SQL과 migration은 PostgreSQL과 MariaDB에서 같은 계약을 사용합니다.
