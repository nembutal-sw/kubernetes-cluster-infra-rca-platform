# Database

지원 DB는 PostgreSQL과 MariaDB입니다. 로컬 기본값은 H2 파일 DB이며 운영에는 사용하지 않습니다.

## Configuration

PostgreSQL:

```text
RCA_JDBC_URL=jdbc:postgresql://localhost:5432/rca
RCA_DB_USERNAME=rca
RCA_DB_PASSWORD=change-me
```

MariaDB:

```text
RCA_JDBC_URL=jdbc:mariadb://localhost:3306/rca
RCA_DB_USERNAME=rca
RCA_DB_PASSWORD=change-me
```

## Migration

Flyway가 `web-console/src/main/resources/db/migration`의 SQL을 실행합니다.

기존 Python/Alembic DB를 연결하면 `baseline-on-migrate`가 기존 스키마를 version 1로 등록합니다. 새 DB에서는 version 1 스키마를 직접 생성합니다. 기존 데이터를 연결하기 전에 DB 백업을 권장합니다.

주요 테이블:

- `clusters`
- `node_agents`
- `evidence_requests`
- `evidence_bundles`
- `rca_reports`
- `rca_jobs`
- `rca_analysis_tasks`
- `user_accounts`
- `user_sessions`
- `incidents`
- `action_requests`
- `action_executions`
- `realtime_events`
- `audit_events`

JSON 데이터는 DB별 JSON 타입 대신 `TEXT`로 저장해 PostgreSQL과 MariaDB의 동작을 동일하게 유지합니다.

`DatabaseCompatibilityTests`는 두 DB에서 새 스키마 생성과 기존 Alembic 스키마 승계를 검증합니다. Docker가 없는 로컬 환경에서는 자동으로 건너뛰며 GitHub Actions에서는 실제 DB 컨테이너로 실행합니다.

## Retention

Platform은 기본적으로 매일 `03:17`에 만료 데이터를 제한된 selection batch로 정리합니다.

- 열린 incident는 삭제하지 않습니다.
- 승인 대기, 수동 처리 승인, read-only 수집 진행 상태의 action은 보존합니다.
- report와 연결된 action, task, job을 먼저 삭제해 FK 무결성을 유지합니다.
- evidence는 request, task, job, realtime event, incident 참조가 모두 사라진 뒤 삭제합니다.
- 전체 정리는 단일 트랜잭션으로 실행되며 실패 시 롤백됩니다.

보존 기간과 환경 변수는 [Retention Policy](retention-policy.md)를 참고합니다.

## Backup

Helm의 `backup.enabled=true`는 내장 DB를 대상으로 `pg_dump` 또는 `mariadb-dump` CronJob과 backup PVC를 생성합니다.

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set backup.enabled=true \
  --set backup.persistence.size=20Gi
```

복구 전에는 플랫폼 쓰기를 중지하고 대상 DB를 별도 인스턴스에 복구한 뒤 무결성을 검증합니다. 운영 환경에서는 chart의 단일 DB보다 관리형 DB와 provider snapshot을 우선합니다.
