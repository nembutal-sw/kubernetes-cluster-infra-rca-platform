# Incident Correlation

동일 장애의 evidence가 반복 제출될 때 보고서를 계속 생성하지 않고 incident 단위로 묶습니다.

Correlation key 구성:

- cluster id
- node name
- alert name
- Rule-based 최우선 원인
- correlation time bucket

기본 구간은 15분이며 `RCA_INCIDENT_CORRELATION_WINDOW_MINUTES`로 변경합니다. 첫 evidence는 report를 생성하고 이후 동일 key는 `occurrence_count`, `last_seen_at`, `latest_evidence_id`만 갱신합니다.

운영자는 Web Console 또는 API에서 incident를 해결 처리하거나 다시 열 수 있습니다. 해결된 incident의 correlation key는 새로운 값으로 회전되어 같은 장애가 다시 발생했을 때 새 incident와 report가 생성됩니다.

```text
GET  /api/rca/incidents
POST /api/rca/incidents/{incident_id}/resolve
POST /api/rca/incidents/{incident_id}/reopen
```
