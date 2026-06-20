# Architecture

## Components

```text
Node Agent
  -> Spring Boot Platform API
     -> Evidence Preprocessor
     -> Rule-based Analyzer
     -> Optional Spring AI Analyzer
     -> Policy Engine
     -> RCA Report
  -> Web Console
  -> PostgreSQL or MariaDB
```

중앙 플랫폼은 Spring Boot 단일 애플리케이션입니다. API, 인증, DB 접근, RCA 분석,
Policy Engine과 React/Vite Web Console 정적 자산을 같은 프로세스에서 제공합니다.

Node Agent만 Python으로 유지합니다. Agent는 노드 장애 시 중앙 플랫폼과 독립적으로 host evidence를 수집하고, 전송 실패 데이터는 로컬 spool에 보관합니다.

## Analysis Order

1. 수집 데이터 크기와 불필요 필드를 제한합니다.
2. 임계값과 상태 조합으로 Rule-based signal을 생성합니다.
3. 원인 후보와 read-only 추가 확인 명령을 구성합니다.
4. Spring AI가 설정된 경우 전처리 JSON만 LLM에 전달합니다.
5. Policy Engine이 모든 권장 조치를 다시 분류합니다.
6. LLM 출처 조치는 항상 자동화 금지 상태로 저장합니다.

## Availability

중앙 플랫폼을 진단 대상 클러스터 내부에만 배포하면 해당 클러스터 전체 장애 시 접근할 수 없습니다. 운영 환경에서는 별도 관리 클러스터, VM, 또는 외부 Kubernetes에 배포하는 구성을 권장합니다.

Node Agent의 로컬 spool은 중앙 플랫폼 일시 중단 중 evidence 손실을 줄이지만, 중앙 DB와 Web Console 자체의 가용성을 대신하지는 않습니다.

Prometheus가 없는 환경에서는 선택적 scheduler가 정상 Agent에 evidence request를 생성합니다. 같은 노드에 pending request가 있거나 Agent가 offline이면 새 요청을 만들지 않습니다. 정상 수집은 보고서를 생성하지 않고 장애 signal이 있을 때만 RCA로 승격합니다.

## Persistence Boundaries

애플리케이션 계층은 단일 저장소에 의존하지 않습니다.

```text
ClusterRepository
AgentRepository
EvidenceRepository
AnalysisTaskRepository
IncidentRepository
ReportRepository
ActionRepository
UserRepository
UserSessionRepository
AuditRepository
  -> JdbcRcaStore
     -> PostgreSQL / MariaDB / H2
```

`JdbcRcaStore`는 DB 호환 SQL과 JSON mapping을 격리하는 내부 구현입니다. Controller와
Service는 도메인별 Repository만 주입받으므로 이후 tenant/access scope 조건을 각 경계에
추가할 수 있습니다.

## Rule Detection

`RuleBasedRcaAnalyzer`는 보고서 orchestration만 담당합니다. 실제 signal 판단은
`SignalDetectionEngine`과 `SignalDetector` 구현이 수행합니다. Signal에는 threshold,
matched field, confidence, supporting evidence가 포함됩니다.
