# Phase 1: Structure Stabilization

## Changed Areas

- Persistence를 10개 도메인 Repository 경계로 분리
- DB 호환 구현을 내부 `JdbcRcaStore`로 격리
- Rule 판단을 `SignalDetector` 구현으로 분리
- Signal explainability field 추가
- Node Agent collector를 package/registry 구조로 전환
- collector 운영 metadata 추가
- 오탐 반례와 metadata 테스트 추가

## Design Intent

Controller와 Service가 전역 저장소나 긴 조건문에 직접 의존하지 않게 했습니다. 이후
tenant/access scope, detector override, Agent Health Dashboard를 각 경계에 추가할 수 있습니다.

## Validation

- Java test: 통과
- Python pytest: 25개 통과
- Python compileall: 통과
- React/TypeScript/Vite build: Maven lifecycle에서 검증
- PostgreSQL/MariaDB Testcontainers: Docker daemon이 있는 환경에서 실행

## Remaining TODO

- `JdbcRcaStore`의 SQL/mapping 구현을 각 Repository로 물리적으로 이동
- collector `_legacy.py` helper/parser를 subsystem별 common 모듈로 이동
- detector enable/disable와 cluster별 threshold override 저장 모델 추가
- concurrent task claim을 실제 PostgreSQL/MariaDB에서 반복 검증

다음 단계는 Phase 2 보안 강화입니다.
