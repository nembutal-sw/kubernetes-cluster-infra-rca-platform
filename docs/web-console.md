# Web Console

이 콘솔은 프로젝트의 단일 사용자 Web UI다. Spring Boot MVC가 JSP로 화면의 기본 shell을 렌더링하고, 데이터 변경이 잦은 영역은 React가 갱신한다. 레이아웃과 기본 UI 컴포넌트는 Bootstrap 5를 사용한다.

RCA 수집, 분석, DB, LLM, Policy Engine은 Python FastAPI backend가 담당한다. FastAPI는 API 전용 서버로 두고, 브라우저는 Spring Boot 콘솔의 `/console-api/**` 프록시를 통해 backend API를 호출한다.

## Structure

```text
web-console/
|-- pom.xml
`-- src/
    |-- main/java/io/clusterinfra/rca/webconsole/
    |   |-- WebConsoleApplication.java
    |   |-- config/
    |   `-- controller/
    |-- main/resources/
    |   |-- application.yml
    |   `-- static/assets/
    `-- main/webapp/WEB-INF/jsp/console.jsp
```

## Runtime Baseline

The web console targets Java 17+. Spring Boot 3.3 supports Java 17 as its baseline, and Java 17 is easier to find on enterprise Linux servers than Java 21.

Linux server setup details should stay in a private runbook, not in a committed repository document.

## Run

먼저 Python backend를 실행한다.

```powershell
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

그 다음 Spring Boot 콘솔을 실행한다.

```powershell
cd web-console
mvn spring-boot:run
```

기본 접속 주소는 다음과 같다.

```text
http://127.0.0.1:8080/
```

WAR로 패키징한 뒤 실행할 수도 있다.

```powershell
cd web-console
mvn package
java -jar target\cluster-infra-rca-web-console-0.1.0.war
```

## Configuration

기본 backend API 주소는 `http://127.0.0.1:8000`이다.

```powershell
$env:RCA_API_BASE_URL = "https://rca-api.example.com"
$env:RCA_PUBLIC_API_BASE_URL = "https://rca-api.example.com"
```

`RCA_API_BASE_URL`은 Spring Boot 서버가 실제로 호출할 backend 주소다. `RCA_PUBLIC_API_BASE_URL`은 agent 설치 명령과 webhook 화면에 보여줄 외부 접근용 API 주소다.

## Pages

- `Overview`: cluster, report, access, webhook 상태 요약
- `Clusters`: 클러스터 등록, agent 설치 명령, manifest 링크, node agent 상태
- `Webhooks`: Alertmanager endpoint와 receiver 예시
- `Reports`: RCA report 목록, root cause, policy, signal, checklist
- `Settings`: proxy, API, runtime 설정 확인, 비밀번호 변경

## Security

첫 화면은 로그인 페이지다. 기본 계정은 `admin/admin`이며, 운영 배포 후 Settings에서 비밀번호를 변경한다.

React 콘솔은 bearer token을 브라우저 `sessionStorage`에만 저장한다. 운영 배포에서는 HTTPS와 짧은 토큰 수명, 감사 로그, 서버 측 권한 검증이 같이 필요하다.

Spring Boot `/console-api/**` proxy는 `/api/auth/login`과 health check를 제외한 요청에 Bearer token이 없으면 backend로 전달하지 않고 `401`을 반환한다.

Spring Boot 콘솔은 기본적으로 다음 보안 헤더를 붙인다.

- `Content-Security-Policy`
- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `Permissions-Policy`

정적 자산은 WebJars와 `/assets` 경로에서 로컬로 제공한다. Bootstrap, Bootstrap Icons, React, ReactDOM은 외부 CDN을 사용하지 않는다.

## Validation

현재 검증한 항목은 다음과 같다.

- JavaScript syntax check: `node --check web-console/src/main/resources/static/assets/console-app.js`
- Spring Boot tests: `mvn test`
- JSP shell rendering with embedded Tomcat
- `/console-api/**` proxy forwarding
- unauthenticated proxy request blocking
- `Authorization` forwarding
- query string preservation
- response `Cache-Control: no-store`
- console security headers
- WAR packaging: `mvn package -DskipTests`
- Linux runtime smoke check on a private validation server:
  - user-local JDK 17 and Maven 3.9.9
  - WAR execution on a non-privileged port
  - JSP page response
  - `/console-api/health` proxy response
  - security header presence

Codex Windows shell에서는 장시간 background process를 안정적으로 유지하기 어려워 브라우저 화면 검증은 제한적이었다. 이후 Java/Spring Boot 검증은 Linux 서버에서 진행한다. 현재는 embedded Tomcat 기반 HTTP 테스트와 Linux runtime smoke check로 JSP, proxy, 보안 헤더 동작을 확인했다.
