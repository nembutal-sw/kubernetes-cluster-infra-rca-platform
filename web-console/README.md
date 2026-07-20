# Cluster Infra RCA Platform

Spring Boot 3.5.15와 Java 21 기반의 중앙 Platform 모듈입니다. React Web Console을 빌드 결과에 포함하므로 Backend와 UI가 같은 origin에서 실행됩니다.

## 포함 기능

- session 인증, RBAC, audit log
- 클러스터와 Agent lifecycle 관리
- evidence 수집 요청, durable analysis queue, incident correlation
- Rule-based RCA, 선택적 Spring AI 분석, Policy Engine
- RCA report, 장애 전파 timeline, evidence bundle export
- manual-only action request와 Catalog GitOps workflow
- PostgreSQL/MariaDB JDBC repository와 Flyway migration
- Micrometer, Actuator, Prometheus metric
- React 19, TypeScript, Vite, Bootstrap 5 Web Console

## Requirements

| Tool | Version |
| --- | --- |
| Java | 21 |
| Maven | 3.9 이상 |
| Node.js | Maven build 사용 시 자동 설치 |
| Docker | DB 호환 Testcontainers 실행 시 필요 |

## Local Run

H2 file DB를 사용하는 가장 단순한 실행 방법입니다.

```bash
export RCA_DEFAULT_ADMIN_USERNAME=admin
export RCA_DEFAULT_ADMIN_PASSWORD='<strong-password>'
export RCA_WEBHOOK_TOKEN='<random-webhook-token>'
mvn spring-boot:run
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:RCA_DEFAULT_ADMIN_USERNAME = "admin"
$env:RCA_DEFAULT_ADMIN_PASSWORD = "<strong-password>"
$env:RCA_WEBHOOK_TOKEN = "<random-webhook-token>"
..\.dev-tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

```text
Web Console  http://127.0.0.1:8080
Readiness    http://127.0.0.1:8080/health/ready
```

초기 계정은 환경 변수로 생성합니다. `admin/admin`이 자동으로 만들어지지 않습니다.

## Database Options

### H2

설정을 생략하면 `./data/rca` H2 file DB를 사용합니다. 로컬 개발과 UI 확인에 적합합니다.

### PostgreSQL

```bash
export RCA_JDBC_URL='jdbc:postgresql://localhost:5432/rca'
export RCA_DB_USERNAME='rca'
export RCA_DB_PASSWORD='<database-password>'
mvn spring-boot:run
```

### MariaDB

```bash
export RCA_JDBC_URL='jdbc:mariadb://localhost:3306/rca'
export RCA_DB_USERNAME='rca'
export RCA_DB_PASSWORD='<database-password>'
mvn spring-boot:run
```

Flyway가 신규 schema에는 19개 migration을 적용하고, 기존 Python/Alembic schema는 version 1에서 baseline한 뒤 나머지 migration을 적용합니다.

## Common Options

### Demo와 자체 monitoring

```bash
export RCA_DEMO_ENABLED=true
export RCA_MONITORING_ENABLED=true
export RCA_MONITORING_INTERVAL_MS=60000
mvn spring-boot:run
```

Demo는 개발 전용입니다. 자체 monitoring은 Prometheus 없이 Agent evidence request를 주기적으로 생성합니다.

### LLM

Gemini:

```bash
export RCA_LLM_ENABLED=true
export RCA_LLM_PROVIDER=gemini
export RCA_LLM_MODEL=gemini-3.1-flash-lite
export RCA_SPRING_AI_CHAT_MODEL=google-genai
export SPRING_AI_GOOGLE_GENAI_API_KEY='<api-key>'
mvn spring-boot:run
```

OpenAI:

```bash
export RCA_LLM_ENABLED=true
export RCA_LLM_PROVIDER=openai
export RCA_LLM_MODEL='<model-name>'
export RCA_SPRING_AI_CHAT_MODEL=openai-sdk
export SPRING_AI_OPENAI_SDK_API_KEY='<api-key>'
mvn spring-boot:run
```

Ollama:

```bash
export RCA_LLM_ENABLED=true
export RCA_LLM_PROVIDER=ollama
export RCA_LLM_MODEL='<local-model-name>'
export RCA_SPRING_AI_CHAT_MODEL=ollama
export SPRING_AI_OLLAMA_BASE_URL='http://localhost:11434'
mvn spring-boot:run
```

LLM 연결 상태는 로그인 후 Settings 또는 `GET /api/llm/diagnostics`에서 확인합니다. API key 원문은 응답하지 않습니다.

### Notification

```bash
export RCA_NOTIFICATION_ENABLED=true
export RCA_NOTIFICATION_MINIMUM_SEVERITY=critical
export RCA_SLACK_WEBHOOK_URL='https://hooks.slack.com/services/...'
```

일반 webhook은 다음 변수를 사용합니다.

```bash
export RCA_NOTIFICATION_WEBHOOK_URL='https://notification.example.com/rca'
export RCA_NOTIFICATION_WEBHOOK_TOKEN='<bearer-token>'
```

### Evidence export signing

```bash
export RCA_EXPORT_MAX_BUNDLE_BYTES=10485760
export RCA_EXPORT_SIGNATURE_SECRET='<hmac-secret>'
export RCA_EXPORT_SIGNATURE_KEY_ID=production-2026
```

### Agent compatibility

```bash
export RCA_AGENT_EXPECTED_VERSION='0.1.0'
export RCA_AGENT_MINIMUM_SUPPORTED_VERSION='0.1.0'
export RCA_AGENT_PROTOCOL_VERSION='1'
export RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION='1'
```

현재 계약은 인증 후 `GET /api/v1/platform/info`에서 확인할 수 있습니다.

## Production Profile

`prod`와 `production` profile은 위험한 설정을 발견하면 startup을 중단합니다.

```bash
export SPRING_PROFILES_ACTIVE=prod
export RCA_PUBLIC_API_BASE_URL='https://rca.example.com'
export RCA_DEFAULT_ADMIN_USERNAME='platform-admin'
export RCA_DEFAULT_ADMIN_PASSWORD='<strong-admin-password>'
export RCA_WEBHOOK_TOKEN='<strong-webhook-token>'
export RCA_DB_PASSWORD='<strong-database-password>'
export RCA_ENCRYPTION_SECRET='<encryption-secret>'
export RCA_METRICS_TOKEN='<metrics-token>'
export RCA_DEMO_ENABLED=false
export RCA_AUDIT_ENABLED=true
```

추가 기능을 활성화하면 해당 secret도 필요합니다.

| 기능 | 필수 설정 |
| --- | --- |
| LLM | provider, model, chat model, credential 또는 base URL |
| Notification | HTTPS Slack URL 또는 HTTPS webhook URL |
| GitOps | provider, repository, token, webhook secret |
| Observability | `RCA_METRICS_TOKEN` |

전체 기준은 [../docs/security.md](../docs/security.md)를 참고합니다.

## Build And Test

전체 검증과 패키징:

```bash
mvn verify
```

테스트만 실행:

```bash
mvn test
```

JAR 생성과 실행:

```bash
mvn -DskipTests package
java -jar target/cluster-infra-rca-platform-0.1.0.jar
```

Frontend만 검증:

```bash
cd frontend
npm ci
npm test
npm run build
```

브라우저 E2E:

```bash
cd frontend
npx playwright install chromium
npm run e2e
```

PostgreSQL/MariaDB 호환 테스트:

```bash
mvn -Dtest=DatabaseCompatibilityTests test
cd ..
python3 scripts/verify_database_compatibility_report.py
```

두 번째 명령은 DB 테스트 4개가 Docker 미탐지로 skip된 경우 실패합니다.

## Operations Endpoints

| Endpoint | 인증 | 용도 |
| --- | --- | --- |
| `/health` | 없음 | liveness |
| `/health/ready` | 없음 | DB와 bootstrap readiness |
| `/actuator/metrics` | metrics token | metric 조회 |
| `/actuator/prometheus` | metrics token | Prometheus scrape |
| `/api/webhooks/alertmanager` | webhook token | Alertmanager ingest |
| `/api/agents/**` | cluster/node token | Agent lifecycle |
| `/api/v1/platform/info` | session | version과 기능 상태 |
| `/api/llm/diagnostics` | session | LLM 설정 진단 |

브라우저 API는 별도 proxy 없이 같은 Spring Boot origin의 `/api/**`를 호출합니다.

## Source Layout

```text
src/main/java/       Spring Boot application
src/main/resources/  application config, Flyway, Catalog
src/test/java/       unit, integration, security, DB tests
frontend/src/        React pages, components, domain hooks
frontend/e2e/        Playwright workflow tests
```
