# Durable Analysis Pipeline

Evidence 저장과 RCA 분석은 분리되어 있습니다.

1. Agent 또는 webhook이 evidence를 전송합니다.
2. 플랫폼은 evidence와 `rca_analysis_tasks` 레코드를 같은 DB 트랜잭션으로 저장합니다.
3. worker가 조건부 UPDATE로 task lease를 획득합니다.
4. Rule-based 분석과 선택적 LLM 분석을 수행합니다.
5. Incident, report, job, notification outbox와 task `completed` 전환을 같은 DB transaction으로 저장합니다.
6. commit 이후 audit과 metrics는 best-effort로 기록합니다.

분석 실패 시 `retry_wait` 상태로 전환되고 지수 backoff 후 재시도합니다. 최대 시도 횟수를 초과하면 `dead_letter`로 이동합니다. Web Console의 Pipeline 화면에서 상태와 오류를 확인하고 수동으로 다시 queue에 넣을 수 있습니다.

`rca_analysis_tasks.evidence_id` 고유 제약이 Evidence별 task를 하나로 제한합니다. 완료 UPDATE는
`lease_owner`와 `attempt_count`를 함께 확인하므로 이전 worker의 stale commit은 Incident를 포함한
transaction 전체를 rollback합니다. commit 이후 audit 저장이 실패해도 완료 task를 재시도하지 않으며,
`rca.pipeline.post.commit.failure` metric과 경고 로그로 별도 관측합니다.

여러 플랫폼 인스턴스가 같은 DB를 사용해도 lease owner, attempt fence와 만료 시각으로 중복 처리를
제한합니다. 처리 중에는 worker가 lease의 1/3 주기로 만료 시각을 연장합니다. 인스턴스가 종료되면
renewal도 중단되며, 만료된 작업을 다른 인스턴스가 회수합니다.

## Configuration

```text
RCA_PIPELINE_ENABLED=true
RCA_PIPELINE_BATCH_SIZE=4
RCA_PIPELINE_POLL_INTERVAL_MS=2000
RCA_PIPELINE_LEASE_SECONDS=300
RCA_PIPELINE_MAX_ATTEMPTS=5
RCA_PIPELINE_RETRY_BASE_SECONDS=5
RCA_PIPELINE_RETRY_MAX_SECONDS=300
```

시작 시 다음 관계를 만족하지 않으면 애플리케이션이 설정 오류로 종료됩니다.

```text
RCA_PIPELINE_LEASE_SECONDS
  > RCA_LLM_TIMEOUT_SECONDS * RCA_LLM_MAX_ATTEMPTS
    + 30 seconds database safety margin
```

LLM이 비활성화된 경우에도 lease는 30초보다 길어야 합니다. Spring AI provider retry는 각
application-level 호출의 timeout 안에서 실행되며, worker heartbeat가 긴 처리 중 lease를 계속 연장합니다.
