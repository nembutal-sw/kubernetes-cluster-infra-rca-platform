# Database

Backend는 SQLAlchemy 기반 저장소를 사용합니다. 운영 대상 DB는 PostgreSQL과 MariaDB입니다.

MVP에서는 서버 시작 시 `Base.metadata.create_all()`로 테이블을 자동 생성합니다. 실제 운영 단계에서는 Alembic migration으로 바꿔야 합니다.

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

## 현재 테이블

- `clusters`
- `evidence_bundles`
- `rca_reports`
- `rca_jobs`

JSON 성격의 데이터는 DB 호환성을 위해 `Text` 컬럼에 JSON 문자열로 저장합니다. PostgreSQL `JSONB`나 MariaDB `JSON` 타입은 지금 단계에서 쓰지 않습니다. 두 DB를 동시에 지원하려면 초반에는 DB별 기능을 피하는 편이 안전합니다.
