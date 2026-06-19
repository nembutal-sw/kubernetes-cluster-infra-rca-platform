# Cluster Infra RCA Platform

Spring Boot 3.5.15와 Java 21 기반의 중앙 Platform 모듈입니다.

이 모듈 하나가 다음 기능을 제공합니다.

- JSP, React, Bootstrap 5 Web Console
- 인증과 role 기반 API 보안
- 클러스터, Agent, evidence, RCA report API
- PostgreSQL/MariaDB JDBC 저장소와 Flyway migration
- Rule-based Analyzer, Policy Engine, Spring AI 연동

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
java -jar target\cluster-infra-rca-platform-0.1.0.war
```

브라우저 API는 별도 proxy 없이 같은 Spring Boot origin의 `/api/**`를 직접 호출합니다.
