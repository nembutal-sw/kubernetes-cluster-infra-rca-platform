# Cluster Infra RCA Web Console

Spring Boot MVC + JSP shell, Bootstrap 5 layout, and React-driven console views.

This module is the only user-facing Web UI. The Python FastAPI service stays API-only.

The module targets Java 17+.

## Run

Start the Python RCA backend first:

```powershell
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

Run the console:

```powershell
cd web-console
mvn spring-boot:run
```

Open:

```text
http://127.0.0.1:8080/
```

Initial login:

```text
admin / admin
```

Change the password from Settings after first login.

The console can also be packaged and run as a WAR:

```powershell
mvn package
java -jar target\cluster-infra-rca-web-console-0.1.0.war
```

## Configuration

```powershell
$env:RCA_API_BASE_URL = "http://127.0.0.1:8000"
$env:RCA_PUBLIC_API_BASE_URL = "http://127.0.0.1:8000"
```

`RCA_API_BASE_URL` is used by the Spring Boot proxy. `RCA_PUBLIC_API_BASE_URL` is shown in install commands and webhook examples.

## Notes

- JSP renders the page shell from `/WEB-INF/jsp/console.jsp`.
- React mounts on `#rca-console-root`.
- Browser API calls go through `/console-api/**` to avoid CORS issues.
- `/console-api/**` blocks protected API calls without a Bearer session.
- Bootstrap, Bootstrap Icons, React, and ReactDOM are served from WebJars.
- Security headers are applied by `SecurityHeadersFilter`.
- HTTP tests verify JSP rendering, proxy forwarding, auth blocking, auth header forwarding, and cache control.

## Test

```powershell
mvn test
```

For Linux server validation from the repository root:

```bash
bash scripts/linux-dev-check.sh --validate
```
