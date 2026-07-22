# Phase 3: Testing and CI

Phase 3는 테스트와 배포 산출물이 서로 다른 경로를 사용하지 않도록 검증 흐름을 통합합니다.

## CI Jobs

| Job | Validation |
| --- | --- |
| `node-agent-test` | Python compile, pytest |
| `frontend-build` | locked dependency install, Frontend unit test, TypeScript, Vite build |
| `web-console-test` | npm 비의존 Spring Boot test, package, PostgreSQL/MariaDB Testcontainers |
| `helm-validate` | platform/agent lint와 주요 values 변형 렌더링 |
| `docker-build` | 선행 job 통과 후 platform/agent image build |
| `Operational Smoke` | 배포된 platform API 대상 demo RCA, evidence bundle manifest, audit, 선택적 LLM staging smoke 검증 |

`Dockerfile.web-console`은 `mvn -Pfrontend verify`로 Java 검증과 React 정적 자산 패키징을 함께
수행합니다. Frontend unit test는 CI의 독립 `frontend-build` job에서 한 번만 실행합니다. 기본
`mvn verify`는 npm registry에 접근하지 않으므로 Java 변경 검증과 Frontend 공급망 장애가 분리됩니다.

`Operational Smoke`는 push마다 자동 실행하지 않고 `workflow_dispatch` 또는 `workflow_call`로 실행합니다.
Tailscale 내부 서버를 검증할 때는 `TAILSCALE_AUTHKEY` secret을 사용하고, platform 비밀번호는
`RCA_SMOKE_PASSWORD` secret으로만 전달합니다. Signed bundle 검증이 필요한 환경에서는
`RCA_BUNDLE_SIGNATURE_SECRET` secret과 `RCA_BUNDLE_SIGNATURE_KEY_ID` variable을 추가합니다.
LLM provider가 staging 환경에 설정된 경우 `run_llm_smoke=true`로 실행하면 같은 API endpoint에서
`scripts/llm-staging-smoke.py`를 추가 실행합니다. LLM API key는 workflow secret으로 넘기지 않고,
검증 대상 Platform의 환경 변수 또는 Kubernetes Secret에 미리 주입합니다.

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
