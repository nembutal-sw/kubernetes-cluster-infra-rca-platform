# Cluster Infra RCA Platform

Spring Boot 3.5.15와 Java 21 기반의 중앙 Platform 모듈입니다.

이 모듈 하나가 다음 기능을 제공합니다.

- React 19, TypeScript, Vite, Bootstrap 5 Web Console
- 인증과 role 기반 API 보안
- 클러스터, Agent, evidence, RCA report API
- PostgreSQL/MariaDB JDBC 저장소와 Flyway migration
- Rule-based Analyzer, Policy Engine, Spring AI 연동
- 장애 전파 타임라인과 승인 기반 Agent 조치 실행
- Demo Scenario Mode와 후보별 0~100 신뢰도 점수
- Agent Health 분류와 redacted Evidence ZIP export
- Kubernetes evidence 기반 workload 영향 범위와 선택적 Slack 알림
- Micrometer/Prometheus 운영 metric과 SLO histogram

## Run

```powershell
mvn spring-boot:run
```

```text
http://127.0.0.1:8080
admin / admin
```

## Database

```powershell
$env:RCA_JDBC_URL = "jdbc:postgresql://localhost:5432/rca"
$env:RCA_DB_USERNAME = "rca"
$env:RCA_DB_PASSWORD = "change-me"
```

MariaDB는 `jdbc:mariadb://localhost:3306/rca` 형식을 사용합니다.

## Build

```powershell
mvn test
mvn package
java -jar target\cluster-infra-rca-platform-0.1.0.jar
```

브라우저 API는 별도 proxy 없이 같은 Spring Boot origin의 `/api/**`를 직접 호출합니다.

개발 환경에서 Demo Mode를 활성화하려면 `RCA_DEMO_ENABLED=true`를 사용합니다. Evidence ZIP의 압축 전 최대 크기는 `RCA_EXPORT_MAX_BUNDLE_BYTES`, Agent 고정 버전은 `RCA_AGENT_EXPECTED_VERSION`, 최소 지원 버전은 `RCA_AGENT_MINIMUM_SUPPORTED_VERSION`으로 설정합니다. Agent protocol 범위는 `RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION`부터 `RCA_AGENT_PROTOCOL_VERSION`까지이며, 현재 계약은 인증 후 `GET /api/v1/platform/info`에서 확인할 수 있습니다. Slack 알림은 `RCA_NOTIFICATION_ENABLED`와 `RCA_SLACK_WEBHOOK_URL`로 활성화합니다.

Actuator metric은 `/actuator/metrics`, Prometheus 형식은 `/actuator/prometheus`에서 제공합니다.
운영 scrape에는 `RCA_METRICS_TOKEN`을 사용합니다.
