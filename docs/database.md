# Database

Backend는 SQLAlchemy 기반 저장소와 Alembic migration을 사용합니다. 운영 대상 DB는 PostgreSQL과 MariaDB입니다.

서버 시작 시 table을 자동 생성하지 않는 것이 기본값입니다. DB schema 변경은 `alembic upgrade head`로 적용합니다.

## 지원 DB

| DB | 권장 URL |
| --- | --- |
| PostgreSQL | `postgresql+psycopg://rca:rca_password@localhost:5432/rca` |
| MariaDB | `mysql+pymysql://rca:rca_password@localhost:3306/rca` |

편의를 위해 아래 URL도 내부에서 정규화합니다.

- `postgres://...` -> `postgresql+psycopg://...`
- `postgresql://...` -> `postgresql+psycopg://...`
- `mariadb://...` -> `mysql+pymysql://...`
- `mysql://...` -> `mysql+pymysql://...`

## 로컬 DB 실행

PostgreSQL:

```powershell
docker compose up -d postgres
```

MariaDB:

```powershell
docker compose up -d mariadb
```

## 환경변수

`.env.example`을 참고해서 `RCA_DATABASE_URL`을 지정합니다.

PostgreSQL:

```powershell
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"
```

MariaDB:

```powershell
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"
```

개발용 SQLite fallback:

```powershell
$env:RCA_DATABASE_URL = "sqlite:///./data/rca-dev.db"
```

`RCA_AUTO_CREATE_TABLES=true`를 설정하면 앱 시작 시 SQLAlchemy `create_all()`을 실행할 수 있습니다. 이 옵션은 테스트나 임시 개발 용도만 가정합니다. 일반 개발/운영 흐름에서는 `false`로 두고 Alembic을 사용합니다.

## Migration

현재 revision:

- `0001_initial_schema`

적용:

```powershell
.venv\Scripts\python.exe -m alembic upgrade head
```

현재 상태 확인:

```powershell
.venv\Scripts\python.exe -m alembic current
```

새 migration 생성:

```powershell
.venv\Scripts\python.exe -m alembic revision --autogenerate -m "describe change"
```

생성된 migration은 PostgreSQL과 MariaDB 양쪽에서 동작 가능한 타입과 DDL인지 확인해야 합니다.

## 현재 테이블

- `clusters`
- `node_agents`
- `evidence_requests`
- `evidence_bundles`
- `rca_reports`
- `rca_jobs`

JSON 성격의 데이터는 DB 호환성을 위해 `Text` 컬럼에 JSON 문자열로 저장합니다. PostgreSQL `JSONB`나 MariaDB `JSON` 타입은 지금 단계에서 쓰지 않습니다. 두 DB를 동시에 지원하려면 초반에는 DB별 기능을 피하는 편이 안전합니다.
