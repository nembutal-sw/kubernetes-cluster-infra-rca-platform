# Roadmap

## Phase 1: 문서와 MVP 스캐폴드

- 프로젝트 범위 정의
- 주요 RCA 대상 정의
- Agent 수집 항목 정의
- Alertmanager webhook payload 정의
- RCA report schema 정의

## Phase 2: Backend MVP

- 클러스터 등록 API
- Agent bootstrap token 발급
- Alertmanager webhook 수신
- RCA job 생성
- Evidence bundle 저장

## Phase 3: Node Agent MVP

- DaemonSet 배포
- heartbeat
- kubelet/containerd/systemd collector
- disk/inode/memory/network collector
- evidence request API

## Phase 4: LLM Analyzer

- evidence bundle 요약
- 원인 후보 생성
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

