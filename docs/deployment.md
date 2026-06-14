# Deployment

이 문서는 backend, web-console, database를 한 번에 올리는 기본 배포 경로를 정리한다.

## Docker Compose

기본 compose 구성은 PostgreSQL을 사용한다.

```bash
cp .env.example .env
docker compose up --build -d
```

접속:

```text
Web Console: http://localhost:8080
Backend API: http://localhost:8000
Default login: admin / admin
```

최초 로그인 후 Settings에서 비밀번호를 변경한다.

상태 확인:

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f web-console
```

중지:

```bash
docker compose down
```

데이터까지 삭제:

```bash
docker compose down -v
```

## MariaDB Compatibility

MariaDB 컨테이너는 profile로 분리되어 있다.

```bash
docker compose --profile mariadb up -d mariadb
```

backend를 MariaDB로 실행하려면 `.env`에 아래 값을 지정한다.

```text
RCA_DATABASE_URL=mysql+pymysql://rca:rca_password@mariadb:3306/rca
```

그 다음 backend와 web-console을 다시 올린다.

```bash
docker compose --profile mariadb up --build -d backend web-console
```

## Images

Compose가 로컬에서 빌드하는 이미지:

```text
cluster-infra-rca-backend:local
cluster-infra-rca-web-console:local
```

backend 이미지는 시작 시 기본적으로 Alembic migration을 실행한다.

```text
RCA_RUN_MIGRATIONS=true
```

운영에서 migration을 별도 job으로 실행한다면 `false`로 둔다.

## Required Production Changes

운영 배포 전 최소 변경 항목:

- `RCA_DEFAULT_ADMIN_PASSWORD`
- `RCA_WEBHOOK_TOKEN`
- `RCA_PUBLIC_API_BASE_URL`
- DB 비밀번호와 외부 노출 포트
- HTTPS 또는 reverse proxy TLS
- image tag 고정
- backup/restore 절차

`RCA_PUBLIC_API_BASE_URL`은 agent 설치 명령에 들어간다. kubectl을 실행하는 위치와 node agent pod가 접근할 수 있는 Backend API 주소를 넣어야 한다.

## Health

backend readiness:

```text
GET /health/ready
```

web-console은 `/console-api/**`로 backend를 프록시한다. 로그인과 health check를 제외한 API 호출은 Bearer token 없이는 전달되지 않는다.
