# Roadmap

## Phase 1: 문서와 MVP 스캐폴드

- 프로젝트 범위 정의
- 주요 RCA 대상 정의
- Agent 수집 항목 정의
- Alertmanager webhook payload 정의
- RCA report schema 정의

## Phase 2: Backend MVP

- 클러스터 등록 API 완료
- Agent bootstrap token 발급 완료
- Agent 설치 명령어 조회 완료
- Alertmanager webhook 수신 완료
- RCA job 생성 완료
- Fake evidence 기반 RCA report 생성 완료
- Rule-based analyzer MVP 완료
- Policy Engine MVP 완료
- PostgreSQL/MariaDB 호환 SQLAlchemy 저장소 완료
- Alembic migration 도입 완료

## Phase 3: Node Agent MVP

- Agent register API 완료
- Agent heartbeat API 완료
- Agent 조회 API 완료
- Agent evidence request/response API 완료
- Alertmanager webhook 기반 evidence request 생성 완료
- completed evidence 기반 RCA job/report 생성 완료
- Node Agent runtime MVP 완료
- DaemonSet manifest Secret/env 연동 완료
- 클러스터별 Agent manifest 생성 API 완료
- kubelet/containerd/systemd collector MVP 완료
- disk/inode/memory/network collector MVP 완료
- Backend 없는 local collect 검증 모드 완료
- evidence 기반 RCA signal 추출 완료
- 실제 Linux 노드 검증 및 threshold 보정 예정

## Phase 4: LLM Analyzer

- evidence bundle 요약
- rule-based signal을 입력으로 한 원인 후보 생성
- 근거 기반 confidence 산정
- 추가 확인 명령어 제안
- 직접 실행 금지 guardrail 적용

## Phase 5: Policy Engine

- action taxonomy 구현
- 안전 등급 분류
- 승인 필요 액션 workflow
- GitOps PR-only 액션 분리

## Phase 6: Web UI

- 클러스터 등록 화면
- Agent 설치 명령어 화면
- 장애 이벤트 목록
- RCA 보고서 상세
- Policy decision 표시
