# Phase 3: Testing and CI

Phase 3는 테스트와 배포 산출물이 서로 다른 경로를 사용하지 않도록 검증 흐름을 통합합니다.

## CI Jobs

| Job | Validation |
| --- | --- |
| `node-agent-test` | Python compile, pytest |
| `frontend-build` | locked dependency install, TypeScript, Vite build |
| `web-console-test` | Spring Boot test, package, PostgreSQL/MariaDB Testcontainers |
| `helm-validate` | platform/agent lint와 주요 values 변형 렌더링 |
| `docker-build` | 선행 job 통과 후 platform/agent image build |
| `Operational Smoke` | 배포된 platform API 대상 demo RCA, evidence bundle manifest, audit 검증 |

`Dockerfile.web-console`도 `mvn verify`를 실행합니다. CI 밖에서 이미지를 직접 빌드하더라도
Java, React, API 통합 테스트를 건너뛰지 않습니다.

`Operational Smoke`는 push마다 자동 실행하지 않고 `workflow_dispatch` 또는 `workflow_call`로 실행합니다.
Tailscale 내부 서버를 검증할 때는 `TAILSCALE_AUTHKEY` secret을 사용하고, platform 비밀번호는
`RCA_SMOKE_PASSWORD` secret으로만 전달합니다.

## Added Regression Coverage

- 만료된 session token 거부
- 두 worker의 analysis task 중복 claim 방지
- lease 만료 전 재할당 차단과 만료 후 재할당
- spool file/byte limit
- 손상된 spool 파일 격리
- 민감한 key와 문자열의 재귀 redaction
- DB evidence, LLM 전처리, 분석 오류, 조치 실행 결과 redaction

Docker가 없는 개발 환경에서는 Testcontainers 기반 PostgreSQL/MariaDB 테스트만
자동으로 skip됩니다. GitHub Actions의 Linux runner에서는 실제 두 DB로 실행됩니다.
