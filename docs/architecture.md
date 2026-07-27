# Architecture

## Components

```text
Alertmanager / Platform Scheduler / Demo Scenario
  -> Evidence Request
  -> Node Agent read-only collection
  -> Spring Boot Platform API
     -> Durable Analysis Task
     -> Evidence Preprocessor
     -> Rule-based Analyzer
     -> Optional Spring AI Analyzer
     -> Policy Engine
     -> Incident / Report / Job / Notification Outbox / Task completion
        in one database transaction
  -> React Web Console
  -> PostgreSQL or MariaDB
```

중앙 플랫폼은 Spring Boot 단일 애플리케이션이다. API, 인증, DB 접근, RCA 분석, Policy Engine,
React/Vite Web Console을 같은 프로세스에서 제공한다.

Node Agent는 Python으로 유지한다. Agent는 bootstrap token 또는 Kubernetes TokenReview identity로
등록한 뒤 node-scoped Bearer token만 사용합니다. 각 노드에서 host evidence를 읽기 전용으로 수집하고,
중앙 플랫폼으로 전송하지 못한 데이터는 제한된 로컬 spool에 보관합니다.

## Analysis Order

1. 수집 데이터 크기와 불필요한 필드를 제한한다.
2. 임계값과 상태 조합으로 rule-based signal을 만든다.
3. 원인 후보, 근거, 추가 확인 명령을 구성한다.
4. LLM이 설정된 경우 전처리된 JSON만 전달한다.
5. Policy Engine이 모든 권장 조치를 다시 분류한다.
6. LLM 기반 조치는 항상 자동 실행 불가 상태로 저장한다.

분석 구현은 아래 단계로 분리되어 있으며 `RuleBasedRcaAnalyzer`는 단계 실행 순서만 조정한다.

```text
EvidencePreprocessingStage
  -> collector contract 정규화, signal 탐지, 품질 평가, 민감정보 제거
RuleAnalysisStage
  -> 규칙 기반 원인 후보, 정책 분류 조치, 초기 quality gate, LLM 입력 JSON
LlmEnrichmentStage
  -> 선택적 LLM 진단 병합, evidence 품질 penalty, 최종 quality gate
ReportAssemblyStage
  -> impact scope, topology, checklist, report evidence와 summary 조립
```

단계 사이에는 `RcaAnalysisPipelineContext`의 불변 record만 전달한다. LLM 단계는 규칙 기반 후보의
신뢰도를 올릴 수 없고, LLM 조치는 반드시 `PolicyEngine`을 다시 통과한다.

## Availability

중앙 플랫폼을 진단 대상 클러스터 내부에만 배포하면 클러스터 장애 시 접근이 끊길 수 있다. 운영 환경에서는
별도 관리 클러스터, VM, 또는 외부 Kubernetes에 배포하는 구성을 권장한다.

Prometheus 없이도 backend scheduler가 Agent evidence request를 만들 수 있다. 정상 수집 데이터는 바로
보고서를 만들지 않고, 장애 signal이 감지될 때 RCA pipeline으로 연결된다.

## Persistence Boundaries

Controller와 Service는 DB SQL을 직접 다루지 않는다. 각 도메인 repository가 자기 테이블과 row mapping을
소유한다.

```text
ClusterRepository
AgentRepository
EvidenceRepository
AnalysisTaskRepository
IncidentRepository
ReportRepository
NotificationOutboxRepository
ActionRepository
UserRepository
UserSessionRepository
AuditRepository
  -> PostgreSQL / MariaDB / H2
```

DB 호환성은 repository 단위 테스트와 PostgreSQL/MariaDB Testcontainers 테스트로 확인한다. 로컬에 Docker가
없으면 Testcontainers 테스트는 skip되고, H2 기반 repository 테스트가 기본 검증을 담당한다.

분석 task와 Incident·Report·Job·Notification Outbox 저장, task 완료 처리는 하나의 transaction
경계에 있습니다. Worker는 attempt fence와 갱신 가능한 lease를 사용해 stale worker의 commit을
거부합니다. Audit과 metric 후처리 실패는 완료된 분석 task를 재처리하지 않습니다.

## Rule Detection

`RuleBasedRcaAnalyzer`는 네 분석 stage를 순서대로 호출하는 facade다. 실제 signal 판단은
`EvidencePreprocessingStage`가 사용하는 `SignalDetectionEngine`과 `SignalDetector` 구현에서 수행한다.

각 signal에는 threshold, matched field, confidence, supporting evidence가 포함된다. UI와 report export는
이 정보를 기반으로 원인 후보와 근거를 추적한다.

## Frontend Composition

`App.tsx`는 인증 상태, 공통 data/workflow hook, shell과 전역 dialog를 조립한다. URL 정규화와 권한
redirect는 `useConsoleNavigation`, Report/Cluster 상세 선택 동기화는 `useRouteResourceSync`, 화면별
렌더링과 page props 연결은 `ConsoleViewHost`가 담당한다. 각 page는 URL이나 session 구현을 직접 알지 않는다.
