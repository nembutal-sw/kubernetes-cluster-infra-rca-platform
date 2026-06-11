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
- Alertmanager webhook token 인증 완료
- RCA job 생성 완료
- Fake evidence 기반 RCA report 생성 완료
- RCA report/job atomic save 경계 완료
- Rule-based analyzer MVP 완료
- Policy Engine MVP 완료
- PostgreSQL/MariaDB 호환 SQLAlchemy 저장소 완료
- Alembic migration 도입 완료
- DB readiness check 완료

## Phase 3: Node Agent MVP

- Agent register API 완료
- node별 Agent token 발급 및 hash 저장 완료
- `agent_token + node_token + node_name` 검증 완료
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
- LLM 입력용 evidence preprocessing 완료
- LLM 입력용 evidence quality/focus/health/log summary 보강 완료
- provider 교체 가능한 LLM Analyzer adapter 완료
- OpenAI/Anthropic/Gemini/OpenAI-compatible request contract mock 검증 완료
- evidence 기반 RCA signal 추출 완료
- 실제 Linux 노드 검증 및 threshold 보정 예정

## Phase 4: LLM Analyzer

- preprocessed evidence JSON 입력 계약 사용
- evidence bundle 요약 완료
- rule-based signal을 입력으로 한 원인 후보 생성 완료
- 근거 기반 confidence 산정 완료
- 추가 확인 명령어 제안 완료
- 직접 실행 금지 guardrail 적용 완료
- LLM 출력 정규화 및 unsafe diagnostic command 제거 완료
- LLM provider 오류 메시지 민감정보 마스킹 완료
- staging provider 실 API 검증 예정

## Phase 5: Policy Engine

- action taxonomy 구현 완료
- 안전 등급 분류 완료
- strict guardrail 기반 정책 격상 완료
- Linux low-level read-only 진단 허용 완료
- LLM source 직접 자동화 차단 완료
- action별 automation metadata 제공 완료
- 승인 필요 액션 workflow
- GitOps PR-only 액션 분리

## Phase 6: Web UI

- 관리자 콘솔 정적 SPA 완료
- Web UI 보안 header/CSP 적용 완료
- 관리자 token query string 제거 및 header 전송 완료
- 세션 만료 시 bootstrap admin token fallback 처리 완료
- 승인 사용자 로그인/로그아웃 완료
- Bearer 세션 기반 API 인증 완료
- admin/operator/viewer 역할별 접근 제어 완료
- 회원가입 요청 화면 완료
- 관리자 승인/거절 화면 완료
- 클러스터 등록 화면 완료
- Agent 설치 명령어 화면 완료
- Alertmanager webhook 연동 안내 화면 완료
- RCA 보고서 목록 화면 완료
- Policy decision 표시 완료
- RCA 보고서 상세 drill-down 완료
