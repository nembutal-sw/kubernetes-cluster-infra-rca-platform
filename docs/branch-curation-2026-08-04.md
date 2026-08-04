# Remote Branch Curation 2026-08-04

## 기준 정보

- 저장소: `nembutal-sw/kubernetes-cluster-infra-rca-platform`
- 기준 브랜치: `main`
- 기준 commit: `406a9a8143e4d235612d833bbb496389606ef2ae`
- 최초 검토 대상: `main`을 제외한 원격 브랜치 19개
- 최초 브랜치에 연결된 GitHub PR: 19개
- 1차 통합 PR: `#29` (`codex/curate-remote-branches-20260804`)
- 작업 중 추가된 Dependabot 브랜치: 4개 (`postcss`, `undici`, `CodeQL`, `Spring Boot`)
- 2차 통합 PR: `#33` (`integrate/transitive-deps-20260804`)
- 3차 통합 브랜치: `integrate/safe-patches-20260804`

브랜치 commit을 그대로 병합하지 않고 최신 `main`에 필요한 변경만 다시 적용했다. 메이저 runtime/framework 변경은 현재 지원 기준과 분리했으며, Dependabot이 같은 메이저 변경을 반복 생성하지 않도록 명시적으로 제한했다.

## 반영 또는 대체

| 원본 브랜치 | 판단 | 최신 main 반영 내용 | 대체 commit |
| --- | --- | --- | --- |
| `agent/publish-edge-images` | 수정 반영 | 성공한 동일 저장소 `main` CI SHA만 GHCR에 발행, 수동 우회 차단, 최소 권한, SBOM/provenance, 구조 검증 추가 | `b535612`, `3363516` |
| `dependabot/github_actions/actions/upload-artifact-7` | 재적용 | 남아 있던 v4/v6 사용을 모두 v7로 통일 | `5c947e2` |
| `dependabot/github_actions/docker/login-action-4.4.0` | 최신 패치로 대체 | release와 edge workflow를 `v4.6.0`으로 갱신 | `d22b2d7` |
| `dependabot/github_actions/docker/metadata-action-6.2.0` | 재적용 | release와 edge workflow를 `v6.2.0`으로 갱신 | `ff231af` |
| `dependabot/github_actions/docker/setup-buildx-action-4.2.0` | 재적용 | CI, Security, release, edge workflow를 `v4.2.0`으로 통일 | `47da4d3` |
| `dependabot/github_actions/github/codeql-action-4` | 이미 반영 | `main`의 `df037b0`에서 CodeQL v4를 사용 | `df037b0` |
| `dependabot/maven/web-console/com.google.genai-google-genai-1.63.0` | 최신 마이너로 대체 | Google Gen AI SDK `1.64.0` 적용 | `fc48ae3` |
| `dependabot/npm_and_yarn/web-console/frontend/playwright/test-1.61.1` | 최신 마이너로 대체 | Playwright `1.62.1` 적용 | `1759347` |
| `dependabot/npm_and_yarn/web-console/frontend/types/react-19.2.17` | 최신 패치로 대체 | React type `19.2.18` 적용 | `6c732b8` |
| `dependabot/npm_and_yarn/web-console/frontend/vite-8.1.4` | 최신 마이너로 대체 | Vite `8.2.0` 적용 | `e6ca9ea` |
| `dependabot/npm_and_yarn/web-console/frontend/vitejs/plugin-react-6.0.3` | 최신 패치로 대체 | React plugin `6.0.5` 적용 | `e6ca9ea` |
| `dependabot/pip/pytest-gte-8-and-lt-10` | 재적용 | pytest 9 허용 후 전체 Python test 검증 | `691aca7` |
| `dependabot/npm_and_yarn/web-console/frontend/postcss-8.5.25` | 이미 반영 | 1차 통합의 lockfile 갱신에 PostCSS `8.5.25`와 Nano ID `3.3.17`이 포함됨 | `e6ca9ea` |
| `dependabot/npm_and_yarn/web-console/frontend/undici-7.29.0` | 재적용 | 최신 `main`에 lockfile-only 패치 업데이트 | PR `#33` |
| `dependabot/github_actions/github/codeql-action-4.37.4` | 재적용 | CodeQL v4의 패치 버전을 명시적으로 고정하고 구조 검증이 v4 패치 태그를 허용하도록 보강 | 3차 통합 브랜치 |
| `dependabot/maven/web-console/org.springframework.boot-spring-boot-starter-parent-3.5.16` | 재적용 | 현재 3.5 지원선의 Spring Boot 패치 업데이트 | 3차 통합 브랜치 |

## 반영하지 않은 메이저 변경

| 원본 브랜치 | 제외 근거 | 현재 기준 | 처리 |
| --- | --- | --- | --- |
| `dependabot/docker/eclipse-temurin-25-jre` | Java runtime 지원선 변경 | Java 21 LTS | Dependabot major ignore 후 삭제 |
| `dependabot/docker/maven-3.9.15-eclipse-temurin-26` | build JDK 26 전환 | Maven 3.9 / Java 21 | Dependabot major ignore 후 삭제 |
| `dependabot/docker/python-3.14-slim` | Agent runtime 지원선 변경 | Python 3.12 | Dependabot major ignore 후 삭제 |
| `dependabot/maven/web-console/org.springframework.ai-spring-ai-bom-2.0.0` | Spring AI API migration 필요 | Spring AI 1.1.x | Dependabot major ignore 후 삭제 |
| `dependabot/maven/web-console/org.springframework.boot-spring-boot-starter-parent-4.1.0` | Spring Boot 4 migration과 회귀 검증 필요 | Spring Boot 3.5.x | Dependabot major ignore 후 삭제 |
| `dependabot/maven/web-console/com.github.eirslett-frontend-maven-plugin-2.0.2` | build plugin major migration | 안전한 `1.15.4`로 갱신 | `1582bf0`, major ignore 후 삭제 |
| `dependabot/npm_and_yarn/web-console/frontend/typescript-7.0.2` | compiler major migration | TypeScript 6.0.x | Dependabot major ignore 후 삭제 |

메이저 버전은 별도 migration branch에서 API 변경, Docker build, DB 호환성, E2E를 함께 검증할 때 다시 평가한다. Dependabot `ignore`의 `version-update:semver-major`는 version update만 제한하므로 security update 자체를 비활성화하지 않는다.

## 삭제 조건

위 원격 브랜치는 다음 조건을 모두 확인한 뒤 삭제한다.

1. 이 문서와 대체 변경을 포함한 PR이 `main`에 병합됨
2. PR의 CI와 Security 검사가 성공함
3. 원본 브랜치에 연결된 PR을 대체 관계와 사유를 남기고 닫음
4. 삭제 직전 원격 tip과 분류 결과가 달라지지 않았음

`main`과 통합 작업 브랜치는 이 정리 대상에 포함하지 않는다.
