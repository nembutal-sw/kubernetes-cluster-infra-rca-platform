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
- `user_accounts`
- `user_sessions`

JSON 데이터는 DB별 JSON 타입 대신 `TEXT`로 저장해 PostgreSQL과 MariaDB의 동작을 동일하게 유지합니다.

`DatabaseCompatibilityTests`는 두 DB에서 새 스키마 생성과 기존 Alembic 스키마 승계를 검증합니다. Docker가 없는 로컬 환경에서는 자동으로 건너뛰며 GitHub Actions에서는 실제 DB 컨테이너로 실행합니다.
