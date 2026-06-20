# Durable Analysis Pipeline

Evidence 저장과 RCA 분석은 분리되어 있습니다.

1. Agent 또는 webhook이 evidence를 전송합니다.
2. 플랫폼은 evidence와 `rca_analysis_tasks` 레코드를 같은 DB 트랜잭션으로 저장합니다.
3. worker가 조건부 UPDATE로 task lease를 획득합니다.
4. Rule-based 분석과 선택적 LLM 분석을 수행합니다.
5. 성공 시 report/job을 task에 연결하고 `completed`로 종료합니다.

분석 실패 시 `retry_wait` 상태로 전환되고 지수 backoff 후 재시도합니다. 최대 시도 횟수를 초과하면 `dead_letter`로 이동합니다. Web Console의 Pipeline 화면에서 상태와 오류를 확인하고 수동으로 다시 queue에 넣을 수 있습니다.

여러 플랫폼 인스턴스가 같은 DB를 사용해도 lease owner와 만료 시각으로 중복 처리를 제한합니다. 처리 중 인스턴스가 종료되면 lease가 만료된 작업을 다른 인스턴스가 회수합니다.

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

LLM timeout과 재시도 시간을 합친 최대 분석 시간보다 `RCA_PIPELINE_LEASE_SECONDS`를 크게 설정해야 합니다.
