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

중앙 플랫폼은 Spring Boot 단일 애플리케이션이다. API, 인증, DB 접근, RCA 분석, Policy Engine,
React/Vite Web Console을 같은 프로세스에서 제공한다.

Node Agent는 Python으로 유지한다. Agent는 각 노드에서 host evidence를 수집하고, 중앙 플랫폼으로 전송하지
못한 데이터는 로컬 spool에 보관한다.

## Analysis Order

1. 수집 데이터 크기와 불필요한 필드를 제한한다.
2. 임계값과 상태 조합으로 rule-based signal을 만든다.
3. 원인 후보, 근거, 추가 확인 명령을 구성한다.
4. LLM이 설정된 경우 전처리된 JSON만 전달한다.
5. Policy Engine이 모든 권장 조치를 다시 분류한다.
6. LLM 기반 조치는 항상 자동 실행 불가 상태로 저장한다.

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
ActionRepository
UserRepository
UserSessionRepository
AuditRepository
  -> PostgreSQL / MariaDB / H2
```

DB 호환성은 repository 단위 테스트와 PostgreSQL/MariaDB Testcontainers 테스트로 확인한다. 로컬에 Docker가
없으면 Testcontainers 테스트는 skip되고, H2 기반 repository 테스트가 기본 검증을 담당한다.

## Rule Detection

`RuleBasedRcaAnalyzer`는 보고서 orchestration을 담당한다. 실제 signal 판단은 `SignalDetectionEngine`과
`SignalDetector` 구현에서 수행한다.

각 signal에는 threshold, matched field, confidence, supporting evidence가 포함된다. UI와 report export는
이 정보를 기반으로 원인 후보와 근거를 추적한다.
