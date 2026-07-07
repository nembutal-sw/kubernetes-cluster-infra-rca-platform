# Phase 1: Structure Stabilization

## Changed Areas

- Persistence를 도메인 repository 경계로 분리
- DB 호환 SQL과 row mapping을 각 repository가 직접 소유하도록 정리
- Rule 판단을 `SignalDetector` 구현으로 분리
- Signal explainability field 추가
- Node Agent collector를 package/registry 구조로 전환
- collector 운영 metadata 추가
- 스키마/호환성/저장소 단위 테스트 보강

## Design Intent

Controller와 Service가 DB 조건문이나 SQL mapping에 직접 의존하지 않게 한다. 이후 tenant/access scope,
detector override, Agent Health Dashboard 같은 기능을 repository 또는 service 경계에 맞춰 추가할 수 있다.

## Validation

- Java test: 통과
- React/TypeScript/Vite build: Maven lifecycle에서 검증
- PostgreSQL/MariaDB Testcontainers: Docker daemon이 있는 환경에서 실행
- 로컬 Docker가 없는 환경: H2 기반 repository/API 테스트로 기본 동작 검증

## Remaining TODO

- Controller/service를 workflow 단위로 추가 분리
- collector `_legacy.py` helper/parser를 subsystem별 common 모듈로 이동
- detector enable/disable과 cluster별 threshold override 모델 추가
- concurrent task claim을 실제 PostgreSQL/MariaDB에서 반복 검증

다음 단계는 controller/service boundary 정리와 frontend type 안정화다.
