# Supported runtime stack migration

The image security gate identified fixable findings in the previous framework,
JSON libraries, embedded server and operating system packages. This change moves
the application to the supported Spring Boot 4.1 / Spring AI 2.0 stack without
changing database migrations, HTTP status numbers, existing Secret references or
the Agent's observation mode.

| Component | Pinned version |
| --- | --- |
| Java | 21 |
| Spring Boot / Framework | 4.1.1 / 7.0.9 |
| Spring AI | 2.0.1 |
| Jackson 2 / Jackson 3 BOMs | 2.21.8 / 3.1.8 |
| Embedded Tomcat | 11.0.27 |
| Apache HttpCore 5 | 5.4.4 |

The JSON compatibility module `spring-boot-jackson2` remains explicit, with
`spring.jackson2.*` and the MVC `jackson2` converter preference. This preserves
the existing snake_case, null omission, authentication error and stored JSON
contracts while Spring AI and Flyway can use Jackson 3 independently. Boot marks
this compatibility module deprecated; a separate Jackson 3 contract migration
will be needed before it is removed by a future Boot release.

Spring AI 2's unified OpenAI starter replaces the removed OpenAI SDK starter.
The application supplies provider-specific options builders, preserves the
output token limit, retries and timeout, and registers no tool callbacks. Legacy
OpenAI SDK selector, API key and base URL settings remain aliases; current
OpenAI properties take precedence. See [LLM Analyzer](llm-analyzer.md).

The Boot 4 module layout requires explicit Flyway and web/security test starters,
relocated test annotations and explicit TestRestTemplate configuration. HTTP 413
and 422 use the current enum names, with unchanged numeric response codes.

Both runtime Dockerfiles retain non-root UID 65532. Their refreshed Java 21 Noble
and Python 3.12 Trixie bases are pinned by digest, and their image build updates
installed OS packages before removing package index files. This is an image build
operation; it does not update Kubernetes nodes or host packages. Updated upstream
packages can change between image builds; the published final image digest and
its SBOM identify the exact artifact that was validated.

## Required release validation

Run the full Java `verify` lifecycle, the integrated frontend build, frontend unit
tests, Python tests, both database report validators and the packaged Agent
enrollment migration tests. PostgreSQL and MariaDB container tests must run with
zero skips in the real CI environment. Local machines without a container runtime
cannot establish that database gate.

Also validate every supported provider's auto-configuration, an isolated OpenAI
compatible HTTP request, runtime readiness and non-root execution. Scan the exact
resulting images and retain SBOMs; a version change alone is not a passing scan.
The compatibility tests use local fixtures, not live cloud provider credentials.

Production rollout requires a reviewed GitOps image digest change and an approved
manual sync. Preserve the old image digest and existing PostgreSQL PVC. This
source change adds no SQL migration; application rollback still requires the
normal health and data checks, rather than a Helm uninstall or database reset.

References:

- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot JSON compatibility](https://docs.spring.io/spring-boot/4.1/reference/features/json.html)
- [Spring AI upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
