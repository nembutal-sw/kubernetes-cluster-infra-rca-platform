# Kubernetes Cluster Infra RCA Platform 개선 및 엔터프라이즈 확장 설계 요청서

## 사용 방법

이 문서는 AI 코딩 도구(Codex, Claude Code, Cursor, GPT 등)에게 프로젝트 개선 방향을 전달하기 위한 Markdown 지시서입니다.

권장 사용 방식은 다음과 같습니다.

1. 이 파일을 저장소 안에 넣는다.
   - 권장 경로: `docs/enterprise-improvement-plan.md`
   - 또는 루트 경로: `AI_IMPLEMENTATION_REQUEST.md`

2. AI에게 아래처럼 지시한다.

```text
이 저장소의 docs/enterprise-improvement-plan.md 파일을 읽고,
현재 코드 구조를 먼저 분석한 다음 Phase 1부터 순서대로 개선해줘.

한 번에 전부 구현하지 말고,
각 Phase마다 변경 파일 목록, 설계 의도, 테스트 결과, 남은 TODO를 정리해줘.
기존 테스트를 삭제하거나 보안 원칙을 약화시키는 방식으로 통과시키지 마.
```

3. 한 번에 전체를 시키기보다 Phase 단위로 나눠서 시킨다.

권장 작업 순서:

```text
Phase 1: 구조 안정화
Phase 2: 보안 강화
Phase 3: 테스트/CI 검증 강화
Phase 4: 포트폴리오 기능 추가
Phase 5: 엔터프라이즈 확장 기반 설계
Phase 6: 엔터프라이즈 기능 실제 구현 후보
```

---

# 0. 프로젝트 목표

현재 GitHub 저장소의 `kubernetes-cluster-infra-rca-platform` 프로젝트를 개선한다.

이 프로젝트는 Kubernetes 애플리케이션 장애가 아니라, **Kubernetes 클러스터 노드와 Linux 시스템 레벨 장애의 원인을 수집하고 분석하는 RCA 플랫폼**이다.

목표는 단순한 데모가 아니라, 다음을 보여줄 수 있는 실무형 포트폴리오로 발전시키는 것이다.

- Kubernetes/Linux node-level RCA 이해
- Agent 기반 evidence 수집 구조
- Spring Boot 기반 API/보안/DB 설계
- Queue 기반 비동기 분석 처리
- Rule-based diagnosis 설계
- LLM을 안전하게 보조 분석기로 사용하는 구조
- 위험 작업 자동화 방지
- 운영 profile 보안 fail-fast
- 테스트와 CI/CD 기반 검증
- Helm/Docker 기반 배포 가능성
- 장애 timeline과 evidence 기반 RCA 설명
- Demo scenario 기반 재현 가능한 시연
- Agent health dashboard 기반 운영성
- Action approval/audit 기반 안전한 운영 흐름
- 향후 엔터프라이즈 환경으로 확장 가능한 구조

단, 이 프로젝트는 현재 단계에서 완전한 상용 엔터프라이즈 제품을 목표로 하지 않는다.

대신 다음 방향을 목표로 한다.

> MVP는 현실적으로 완성 가능해야 한다.  
> 하지만 코드 구조, 보안 경계, 데이터 모델, API 설계, 배포 구조는 향후 엔터프라이즈 환경으로 확장할 수 있도록 설계한다.

---

# 1. 핵심 설계 원칙

## 1.1 RCA 범위

이 프로젝트의 핵심 범위는 다음이다.

- NodeNotReady
- DiskPressure
- MemoryPressure
- PIDPressure
- NetworkUnavailable
- kubelet 장애
- container runtime 장애
- CNI/DNS 문제
- API Server 지연
- etcd 지연
- 디스크 I/O 병목
- inode 고갈
- conntrack 고갈
- kernel error
- systemd unit 장애
- NIC link flap

다음은 보조 근거로만 본다.

- CrashLoopBackOff
- ImagePullBackOff
- Pod OOMKilled
- HTTP 5xx
- Service endpoint 없음
- Ingress 설정 오류

즉, 애플리케이션 장애 분석 플랫폼이 아니라, **클러스터/노드/OS 레벨 RCA 플랫폼**으로 유지한다.

## 1.2 LLM 사용 원칙

LLM은 다음 역할만 수행한다.

- 수집된 evidence 요약
- rule-based RCA 결과 설명 보조
- 추가 확인 항목 추천
- 운영자에게 읽기 쉬운 report 생성 보조

LLM은 다음을 절대 수행하면 안 된다.

- 직접 조치 실행
- 위험 명령 실행 제안의 자동 승인
- node reboot, kubeadm reset, kubectl delete, rm -rf 같은 destructive action 자동 실행
- 증거에 없는 내용을 사실처럼 단정
- Policy Engine을 우회한 automation 허용

LLM이 제안한 action은 항상 다음 조건을 따른다.

```text
source = llm
automation_allowed = false
requires_policy_review = true
```

## 1.3 안전한 자동화 원칙

```text
허용 가능:
- read-only evidence collection
- read-only verification command suggestion
- report generation
- notification
- action request creation
- GitOps PR suggestion

주의 필요:
- approval workflow
- GitOps PR 생성
- operator confirmation

금지:
- host destructive command 자동 실행
- node reboot 자동 실행
- kubeadm reset 자동 실행
- kubectl delete 자동 실행
- systemctl restart 자동 실행
- rm -rf 자동 실행
- iptables/firewall 변경 자동 실행
```

---

# 2. 현재 코드에서 아쉬운 점

1. `RcaRepository`에 너무 많은 책임이 몰려 있다.
2. `RuleBasedRcaAnalyzer`가 너무 큰 클래스이며, rule engine이라기보다 긴 service 클래스에 가깝다.
3. `node_agent/collectors.py`에 여러 collector가 한 파일에 모여 있다.
4. `/api/agents/**`, `/api/webhooks/**`가 `permitAll()`로 열려 있고 실제 검증은 service/controller 레벨에 의존한다.
5. 운영 환경에서 기본 비밀번호, 빈 webhook token, 개발용 secret을 막는 fail-fast 검증이 부족하다.
6. Docker build에서 테스트를 건너뛴다.
7. LLM 응답은 JSON 문자열 파싱 기반이라 structured output/schema 검증이 약하다.
8. 테스트는 존재하지만 오탐/미탐, 보안 실패, 동시성, spool 예외 상황에 대한 반례 테스트가 부족하다.
9. RCA 결과가 단순 보고서 중심이라, 장애 흐름과 근거를 한눈에 파악하기 어렵다.
10. 포트폴리오 데모를 위해 실제 장애 없이도 RCA 흐름을 보여줄 수 있는 기능이 부족하다.
11. 엔터프라이즈 확장을 고려한 tenant, RBAC, audit, retention, encryption, API versioning 설계가 아직 명확하지 않다.

---

# 3. 전체 개발 우선순위

```text
Phase 1: 구조 안정화
Phase 2: 보안 강화
Phase 3: 테스트/CI 검증 강화
Phase 4: 포트폴리오 기능 추가
Phase 5: 엔터프라이즈 확장 기반 설계
Phase 6: 엔터프라이즈 기능 실제 구현 후보
```

현재 바로 구현할 범위는 Phase 1~4를 우선한다.

Phase 5~6은 당장 전부 구현하지 않더라도, 코드 구조와 문서에 확장 가능성을 반영한다.

---

# 4. Phase 1: 구조 안정화

## 4.1 Repository 책임 분리

### 현재 문제

`RcaRepository`가 너무 많은 도메인을 한 번에 다룬다.

현재 포함된 책임:

- Cluster 생성/조회/삭제
- Node Agent 등록/heartbeat/token 검증
- Evidence request 생성/조회/응답 저장
- Evidence 저장
- Analysis task enqueue/claim/complete/retry/dead-letter
- Incident/report 저장 및 correlation
- User/session/auth 관련 처리
- Audit 관련 처리

### 개선 방향

`RcaRepository`를 도메인별 repository로 분리한다.

권장 구조:

```text
web-console/src/main/java/io/clusterinfra/rca/webconsole/persistence/

ClusterRepository.java
AgentRepository.java
EvidenceRepository.java
AnalysisTaskRepository.java
IncidentRepository.java
ReportRepository.java
UserRepository.java
UserSessionRepository.java
AuditRepository.java
```

### 엔터프라이즈 확장 고려

향후 multi-tenancy를 고려하여, 주요 테이블과 repository 메서드는 다음 개념을 수용할 수 있게 설계한다.

```text
tenant_id
organization_id
project_id
cluster_id
```

당장 multi-tenancy를 완성 구현하지 않더라도, 다음은 피한다.

- 전역 cluster 조회만 가정하는 구조
- 모든 사용자가 모든 cluster에 접근 가능한 구조
- repository 메서드에 access scope가 들어갈 수 없는 구조
- audit log가 tenant/cluster/user context 없이 저장되는 구조

### 완료 기준

- `RcaRepository`의 책임이 의미 있게 줄어든다.
- cluster, agent, evidence, analysis task, incident/report, user/session, audit 관련 코드가 분리된다.
- 기존 테스트가 통과한다.
- 향후 tenant/access scope를 추가할 수 있는 구조가 된다.

---

## 4.2 RuleBasedRcaAnalyzer를 Detector 구조로 분리

### 개선 방향

Signal detector 구조를 도입한다.

권장 구조:

```text
web-console/src/main/java/io/clusterinfra/rca/webconsole/analysis/

Signal.java
SignalDetector.java
AnalysisContext.java
EvidenceFlattener.java
ConfidenceScorer.java
RootCauseCandidateBuilder.java

detector/
  DiskPressureDetector.java
  InodePressureDetector.java
  MemoryPressureDetector.java
  PidPressureDetector.java
  ConntrackPressureDetector.java
  DnsLatencyDetector.java
  EtcdLatencyDetector.java
  ApiServerLatencyDetector.java
  KubeletFailureDetector.java
  RuntimeFailureDetector.java
  CniFailureDetector.java
  KernelLogDetector.java
  SystemdFailureDetector.java
  NetworkLinkFlapDetector.java
```

### Signal 모델 예시

```json
{
  "signal": "disk_usage_critical",
  "component": "disk",
  "severity": "critical",
  "confidence": "high",
  "value": 96.0,
  "threshold": 90,
  "matched_fields": ["disk.root_usage_percent"],
  "interpretation": "Filesystem capacity is critically high.",
  "next_step": "Inspect df, mount usage, runtime image storage, and log growth.",
  "supporting_evidence": ["disk.root_usage_percent=96.0 >= threshold 90"]
}
```

### 엔터프라이즈 확장 고려

- detector는 코드로 구현하되, threshold는 설정값으로 주입
- tenant/cluster별 threshold override 가능성 고려
- detector enable/disable 가능성 고려
- rule 결과에 explainability 정보 포함
- false positive를 줄이기 위한 matched evidence 저장
- compliance 환경에서 왜 특정 결론이 나왔는지 설명 가능해야 함

### 완료 기준

- `RuleBasedRcaAnalyzer`가 orchestration 역할에 가까워진다.
- 실제 판단 로직은 detector 클래스로 분리된다.
- detector 단위 테스트가 추가된다.
- 기존 시나리오 테스트가 통과한다.
- RCA 결과의 근거 설명력이 강화된다.

---

## 4.3 Node Agent collector 파일 분리

### 개선 방향

collector를 기능별 파일로 분리한다.

권장 구조:

```text
node_agent/
  collectors/
    __init__.py
    registry.py
    common.py
    node.py
    kubernetes.py
    systemd.py
    kernel.py
    disk.py
    inode.py
    memory.py
    process.py
    network.py
    conntrack.py
    runtime.py
    kubelet.py
    cni.py
    dns.py
```

### Collector metadata 예시

```json
{
  "name": "conntrack",
  "risk_level": "read_only",
  "requires_host_network": true,
  "requires_privileged": false,
  "default_timeout_seconds": 5,
  "max_output_bytes": 1048576,
  "enabled_by_default": true
}
```

### 완료 기준

- `collectors.py`가 지나치게 큰 파일이 아니게 된다.
- collector별 파일 구조가 생긴다.
- 기존 node agent 동작이 유지된다.
- pytest가 통과한다.
- collector별 운영 상태를 dashboard에서 활용할 수 있는 기반이 생긴다.

---

# 5. Phase 2: 보안 강화

## 5.1 Agent/Webhook 인증을 Filter 계층으로 강화

### 현재 문제

Spring Security 설정에서 다음 경로가 `permitAll()`로 열려 있다.

```text
/api/agents/**
/api/webhooks/**
/api/clusters/*/agent-manifest
```

현재는 controller/service 내부에서 token을 검증하는 방식이다.

### 개선 방향

agent/webhook 인증을 filter 또는 interceptor 계층으로 올린다.

권장 추가 컴포넌트:

```text
AgentAuthenticationFilter
WebhookAuthenticationFilter
ManifestAccessFilter 또는 ManifestAccessInterceptor
```

### 엔터프라이즈 확장 고려

- agent token rotation
- node token rotation
- agent certificate 기반 mTLS 인증
- cluster별 agent policy
- token last used timestamp
- revoked token list
- suspicious agent request audit
- per-agent rate limit
- IP allowlist 또는 network policy 연동
- agent protocol version 검증

### 완료 기준

- agent/webhook 인증 누락 위험이 줄어든다.
- 관련 보안 테스트가 추가된다.
- 잘못된 token, 빈 token, 누락된 token 요청이 실패한다.
- 정상 token 요청은 기존처럼 동작한다.
- 향후 mTLS/token rotation을 추가할 수 있는 구조가 된다.

---

## 5.2 운영 환경 fail-fast 검증 추가

운영 profile에서 위험한 기본값을 감지하면 애플리케이션이 시작되지 않도록 한다.

권장 컴포넌트:

```text
ProductionSecurityValidator.java
```

다음 조건이면 production profile에서 startup fail 처리한다.

- admin password가 `admin`
- webhook token이 비어 있음
- webhook token이 `dev-webhook-token`
- DB password가 문서/compose 기본값과 동일
- session ttl이 너무 김
- public base url이 http인데 production profile임
- LLM enabled인데 provider/model/API key 설정이 불완전함
- demo scenario mode가 production에서 활성화되어 있음
- audit log가 비활성화되어 있음
- encryption secret이 기본값이거나 비어 있음

### 완료 기준

- `prod` profile 테스트 추가
- 위험 설정이면 context load 실패
- 안전 설정이면 context load 성공
- README에 운영 필수 환경변수 섹션 추가
- 운영 profile에서 demo/dev secret이 허용되지 않음

---

## 5.3 RBAC 기반 권한 구조 강화

최소한 다음 역할을 고려한 구조를 둔다.

```text
ADMIN
OPERATOR
VIEWER
AUDITOR
APPROVER
```

향후 다음으로 확장할 수 있어야 한다.

- tenant별 role
- cluster별 role
- namespace별 scope
- team별 권한
- custom role
- permission matrix
- break-glass admin
- approval quorum
- separation of duties

### 완료 기준

- mutation API는 viewer가 호출할 수 없어야 한다.
- approval API는 approver/admin만 호출할 수 있어야 한다.
- audit log 조회는 auditor/admin만 가능하게 설계한다.
- 역할 체크는 controller 단발성 if문이 아니라 annotation, service policy, authorization component 형태로 관리한다.

---

# 6. Phase 3: 테스트/CI 검증 강화

## 6.1 Docker build와 CI 개선

현재 `Dockerfile.web-console`에서 테스트를 건너뛰는 구조가 있다.

```dockerfile
RUN mvn -B -ntp package -DskipTests
```

GitHub Actions에서 다음 흐름을 강제한다.

```text
lint
test
build
helm lint/template
docker image build
```

권장 job:

```yaml
jobs:
  node-agent-test:
    steps:
      - run: python -m compileall node_agent
      - run: pytest

  web-console-test:
    steps:
      - run: mvn test

  helm-validate:
    steps:
      - run: helm lint
      - run: helm template

  docker-build:
    needs:
      - node-agent-test
      - web-console-test
      - helm-validate
    steps:
      - run: docker build
```

향후 다음 job을 추가할 수 있는 구조로 만든다.

```text
dependency scan
container image scan
SBOM generation
license scan
secret scan
SAST
Helm policy validation
Kubernetes manifest security scan
image signing
provenance attestation
```

---

## 6.2 반례 테스트 추가

### 정상 evidence

- 정상 evidence에서는 actionable signal이 없어야 한다.
- scheduled monitoring 상황에서 정상 evidence는 report 생성을 skip해야 한다.

### 오탐 방지

- 단순 문자열에 `error`가 포함되어도 kernel I/O error로 오탐하지 않는다.
- 과거 로그나 unrelated message 때문에 OOM으로 오탐하지 않는다.
- `status=unknown` 같은 모호한 값은 곧바로 critical로 판단하지 않는다.

### 보안

- 잘못된 agent token은 register 실패
- 잘못된 node token은 heartbeat/poll/submit 실패
- webhook token 누락은 production에서 실패
- viewer role은 mutation API 접근 불가
- session 만료 후 API 접근 실패

### 동시성

- 여러 worker가 동시에 `claimAnalysisTasks()`를 호출해도 같은 task가 중복 claim되지 않는다.
- lease 만료 전에는 다른 worker가 가져가지 못한다.
- lease 만료 후에는 retry 가능하다.

### agent spool

- backend 실패 시 response가 spool에 저장된다.
- 재전송 성공 시 spool에서 제거된다.
- spool file limit 초과 시 명확한 error가 발생한다.
- spool byte limit 초과 시 명확한 error가 발생한다.
- invalid spool file은 `.invalid`로 이동된다.

### redaction

- evidence 또는 error message에 token/password/authorization/api key가 포함되어도 저장/LLM 입력/로그에서 redaction 된다.

---

# 7. Phase 4: 포트폴리오 기능 추가

## 7.1 Demo Scenario Mode

대표적인 장애 시나리오를 선택하면 mock evidence가 생성되고, 실제 RCA 분석 흐름과 동일하게 report가 생성된다.

예시 시나리오:

```text
1. DiskPressure
2. MemoryPressure
3. Kubelet Failure
4. Container Runtime Failure
5. CoreDNS Latency
6. CNI MTU Mismatch
7. Conntrack Exhaustion
8. Etcd Latency
9. API Server Latency
10. Systemd Restart Loop
```

요구사항:

- demo 전용 API 또는 UI 버튼을 제공한다.
- demo scenario는 운영 profile에서는 기본 비활성화한다.
- demo evidence는 실제 agent evidence와 동일한 schema를 사용한다.
- demo report는 일반 RCA report와 동일한 pipeline을 탄다.
- demo 데이터에는 `source=demo` 또는 `demo=true` 표시를 남긴다.
- demo 실행도 audit event로 기록한다.

---

## 7.2 Incident Timeline

RCA 결과를 단순 결론이 아니라, 시간순 흐름으로 보여준다.

예시:

```text
10:01 NodeReady=False 감지
10:02 DiskPressure=True 발생
10:03 kubelet image GC 실패 로그 발견
10:04 container runtime storage 사용량 92% 확인
10:05 RCA 결과: container image/cache 누적으로 인한 DiskPressure 가능성 높음
10:06 권장 조치: read-only 확인 명령 실행 후 image cleanup 승인 요청
```

timeline event 예시:

```json
{
  "timestamp": "2026-06-20T10:03:00Z",
  "type": "signal_detected",
  "severity": "critical",
  "component": "kubelet",
  "message": "kubelet image garbage collection failed",
  "evidence_id": "..."
}
```

---

## 7.3 Evidence Bundle Export

분석에 사용된 evidence를 하나의 bundle로 내보낸다.

예시:

```text
incident-20260620-node-worker-01.zip
├── summary.json
├── evidence/
│   ├── node-status.json
│   ├── kubelet.log
│   ├── kernel.log
│   ├── disk.json
│   ├── memory.json
│   ├── network.json
│   └── events.json
├── signals.json
├── timeline.json
└── rca-report.md
```

요구사항:

- incident 또는 report 단위로 evidence bundle을 다운로드할 수 있다.
- 민감정보는 반드시 redaction 후 export한다.
- export 이벤트는 audit log에 남긴다.
- 대용량 evidence는 size limit을 둔다.

---

## 7.4 RCA Confidence Score

RCA 결과에 신뢰도를 표시한다.

단순 scoring 예시:

```text
critical signal = +30
warning signal = +15
same component repeated = +20
direct log match = +25
threshold exceeded = +20
multiple independent evidence sources = +20
conflicting evidence = -20
stale evidence = -15
```

요구사항:

- root cause candidate마다 confidence score를 계산한다.
- score는 0~100 범위로 normalize한다.
- signal과 candidate 사이의 연결 근거를 남긴다.
- LLM이 제안한 candidate는 rule-based evidence와 연결되지 않으면 낮은 confidence를 부여한다.
- confidence score는 자동 실행 여부를 결정하는 기준으로 단독 사용하지 않는다.

---

## 7.5 Agent Health Dashboard

Agent 기반 시스템을 운영할 때 각 노드의 agent 상태를 확인할 수 있게 한다.

예시:

```text
Cluster: prod-cluster

Node              Agent Status    Last Heartbeat       Version
worker-01         Healthy         12 seconds ago        0.1.0
worker-02         Healthy         18 seconds ago        0.1.0
worker-03         Stale           7 minutes ago         0.1.0
master-01         Healthy         9 seconds ago         0.1.0
```

상태 값:

```text
Healthy
Stale
Offline
Unauthorized
VersionMismatch
CollectorDegraded
```

---

## 7.6 Action Approval Workflow

Policy Engine이 분류한 조치를 운영자가 승인/거절할 수 있게 한다.

상태 모델:

```text
PENDING
APPROVED
REJECTED
MARKED_AS_DONE
EXPIRED
CANCELLED
```

action request 예시:

```json
{
  "action_key": "container_image_cleanup",
  "policy": "APPROVAL_REQUIRED",
  "risk": "medium",
  "automation_allowed": false,
  "requested_by": "system",
  "approved_by": null,
  "status": "PENDING",
  "created_at": "...",
  "expires_at": "..."
}
```

요구사항:

- 승인/거절/수동 처리 완료는 audit log에 남긴다.
- `NEVER_AUTO_EXECUTE` action은 승인 버튼을 제공하지 않는다.
- `GITOPS_PR_ONLY` action은 직접 실행이 아니라 PR 생성 흐름으로만 연결한다.
- 실제 host 명령 실행은 구현하지 않아도 된다.

---

## 7.7 Slack / Discord / Email 알림

중요한 incident가 생성되었을 때 운영자에게 알림을 보낸다.

예시:

```text
[Cluster RCA Alert]

Cluster: prod-cluster
Node: worker-02
Severity: critical
Likely Cause: DiskPressure caused by container image storage growth
Confidence: 87%
Report: http://platform/incidents/123
```

요구사항:

- 처음에는 Slack webhook 하나만 지원해도 된다.
- 알림 대상 severity를 설정할 수 있게 한다.
- 알림 실패가 RCA report 생성을 실패시키면 안 된다.
- 알림 전송 실패는 retry 또는 audit log로 남긴다.
- 민감정보는 알림에 포함하지 않는다.

---

## 7.8 영향 범위 분석

노드 장애가 어떤 workload/service에 영향을 줄 수 있는지 보여준다.

예시:

```text
Node worker-02 장애 영향

Affected Pods:
- payment-api-7d9f9c
- order-worker-58d22
- redis-cache-0

Affected Namespaces:
- production
- payment

Affected Services:
- payment-api
- order-service
```

요구사항:

- node에 올라간 pod 목록을 수집한다.
- pod의 namespace, owner, labels, service 연결 가능성을 분석한다.
- service endpoint와 node 상태를 연결한다.
- 영향 범위는 RCA 원인과 별도로 표시한다.
- 애플리케이션 장애를 직접 진단하는 방향으로 프로젝트 범위가 흐려지지 않게 한다.

---

# 8. Phase 5: 엔터프라이즈 확장 기반 설계

이 단계는 당장 모든 기능을 완성 구현하지 않아도 된다. 하지만 코드와 문서에는 엔터프라이즈 확장 가능성을 반영한다.

## 8.1 Multi-Tenancy 설계

고려할 개념:

```text
Tenant
Organization
Team
Project
Cluster
User
Role
Permission
```

권장 데이터 관계:

```text
tenant
  └── organization
        └── project
              └── cluster
                    └── agent
                    └── incident
                    └── evidence
                    └── report
```

현재 단계 요구사항:

- 당장 완전한 multi-tenancy를 구현하지 않아도 된다.
- 하지만 DB 모델과 service 메서드가 전역 단일 사용자 구조에 갇히지 않게 한다.
- 주요 리소스에는 향후 `tenant_id`를 추가할 수 있는 여지를 둔다.
- access scope를 검사하는 component를 둘 수 있게 한다.

---

## 8.2 Enterprise RBAC

권장 역할:

```text
SYSTEM_ADMIN
TENANT_ADMIN
CLUSTER_ADMIN
OPERATOR
APPROVER
VIEWER
AUDITOR
SECURITY_ADMIN
```

권장 permission 예시:

```text
cluster:create
cluster:delete
cluster:read
agent:register
agent:read
incident:read
incident:update
evidence:read
evidence:export
report:read
action:create
action:approve
action:reject
audit:read
settings:update
user:manage
```

---

## 8.3 SSO/OIDC/SAML 준비

향후 지원 후보:

```text
OIDC
SAML
LDAP
Active Directory
Google Workspace
Azure AD / Entra ID
Okta
Keycloak
```

현재 단계 요구사항:

- User 모델이 local user에만 강하게 묶이지 않도록 한다.
- user에는 auth provider 개념을 둘 수 있게 한다.

```text
LOCAL
OIDC
SAML
LDAP
```

---

## 8.4 Audit & Compliance

audit 대상:

```text
login success/failure
logout
password change
cluster create/update/delete
agent register
agent auth failure
webhook receive
incident create/update
report create
evidence export
action request create
action approve/reject
settings change
role change
user create/delete
LLM analysis request
LLM analysis failure
demo scenario run
```

audit event 예시:

```json
{
  "timestamp": "...",
  "tenant_id": "...",
  "actor_type": "user|agent|system",
  "actor_id": "...",
  "action": "action.approve",
  "resource_type": "action_request",
  "resource_id": "...",
  "cluster_id": "...",
  "ip_address": "...",
  "user_agent": "...",
  "result": "success|failure",
  "reason": "..."
}
```

---

## 8.5 Data Retention Policy

보관 대상:

```text
raw evidence
normalized evidence
signals
reports
incidents
timeline events
audit logs
LLM input/output
export bundles
agent heartbeat history
```

권장 정책 예시:

```text
raw evidence: 30 days
normalized evidence: 90 days
reports: 1 year
audit logs: 3 years
export bundles: 7 days
agent heartbeat history: 30 days
```

---

## 8.6 Encryption / Secret Management

고려할 항목:

```text
DB at-rest encryption
application-level encryption
token hash 저장
secret redaction
KMS 연동
External Secrets 연동
Kubernetes Secret 사용
Vault 연동 가능성
mTLS
TLS
```

현재 단계 요구사항:

- token은 평문 저장하지 않는다.
- evidence/LLM input/export에 secret이 들어가지 않도록 redaction한다.
- 운영 profile에서 encryption secret이 기본값이면 fail-fast한다.
- Helm chart에서 Secret 값을 직접 values.yaml에 넣는 방식은 피하거나 경고한다.

---

## 8.7 HA / Scalability

고려할 항목:

```text
multiple web-console replicas
stateless API
external DB
queue worker horizontal scaling
task lease
dead-letter queue
idempotency
retry policy
rate limiting
backpressure
```

현재 단계 요구사항:

- session은 DB 기반이거나 stateless token 구조를 유지한다.
- analysis task는 여러 worker가 동시에 처리해도 중복 처리되지 않게 한다.
- `claimAnalysisTasks()`는 동시성 테스트를 갖는다.
- report generation은 idempotent하게 설계한다.
- agent request에는 idempotency key를 고려한다.

---

## 8.8 Air-gapped / On-prem 지원

고려할 항목:

```text
offline Helm install
private registry
local image mirror
no external API dependency
local LLM/Ollama
disable cloud LLM
offline docs
manual license file
```

현재 단계 요구사항:

- LLM provider는 optional이어야 한다.
- cloud LLM 없이 rule-based RCA만으로 동작해야 한다.
- 외부 인터넷 연결이 없어도 platform이 기본 동작해야 한다.
- container image registry는 values로 변경 가능해야 한다.
- Helm chart는 private registry 설정을 지원한다.

---

## 8.9 API Versioning / Agent Protocol Versioning

권장 구조:

```text
/api/v1/...
agent_protocol_version
minimum_supported_agent_version
platform_version
```

현재 단계 요구사항:

- agent heartbeat에 agent version과 protocol version을 포함한다.
- platform은 unsupported version을 식별할 수 있어야 한다.
- API 경로는 향후 `/api/v1` 형태로 전환할 수 있게 한다.
- breaking change를 피하기 위한 DTO versioning을 고려한다.

---

## 8.10 Supply Chain Security

고려할 항목:

```text
SBOM
dependency scan
container image scan
license scan
secret scan
SAST
pinned base image digest
image signing
provenance attestation
GitHub Actions permissions 최소화
```

현재 단계 요구사항:

- Dockerfile base image는 가능하면 digest pinning을 고려한다.
- CI에 dependency/container scan을 붙일 수 있도록 workflow를 구조화한다.
- README에 supply chain security roadmap을 추가한다.

---

## 8.11 Observability / SLO

수집할 metric 예시:

```text
incident_created_total
evidence_requests_total
evidence_collection_failed_total
analysis_task_claimed_total
analysis_task_failed_total
analysis_task_dead_letter_total
llm_analysis_success_total
llm_analysis_failed_total
agent_heartbeat_lag_seconds
agent_offline_count
report_generation_duration_seconds
webhook_ingest_total
notification_failed_total
```

현재 단계 요구사항:

- Spring Actuator metric을 활용한다.
- 주요 비동기 작업에 metric을 추가할 수 있게 한다.
- agent health dashboard와 metric이 연결될 수 있도록 한다.

---

## 8.12 Backup / Disaster Recovery

고려할 항목:

```text
database backup
evidence export backup
audit log backup
restore procedure
backup verification
RPO/RTO 문서화
```

현재 단계 요구사항:

- `docs/operations.md`에 backup/restore 절차를 문서화한다.
- DB migration과 backup 순서를 설명한다.
- Helm chart에 backup job 연동 가능성을 남긴다.

---

# 9. Phase 6: 엔터프라이즈 기능 실제 구현 후보

이 단계는 현재 MVP 이후 roadmap으로 둔다.

우선순위는 다음과 같다.

```text
1. Enterprise RBAC 고도화
2. Audit log export
3. Retention policy cleanup job
4. OIDC login
5. Tenant model 도입
6. Agent token rotation
7. mTLS agent authentication
8. Notification routing policy
9. HA worker scaling
10. SBOM/image scan/signing
11. Air-gapped install guide
12. SIEM integration
```

---

# 10. 아직 하지 않아도 되는 기능

현재 단계에서는 다음 기능은 우선순위에서 제외한다.

```text
자동 복구 실행
복잡한 ML 기반 이상탐지
멀티 테넌트 SaaS 과금 구조
완전한 AIOps 플랫폼화
Grafana 대체 수준의 대시보드
대규모 로그 검색 엔진 직접 구현
상용 라이선스/과금 시스템
완전한 SAML 구현
완전한 SIEM 제품 수준의 로그 분석
```

특히 **자동 복구 실행**은 조심한다.

이 프로젝트의 강점은 다음이다.

```text
안전한 RCA
근거 기반 분석
위험 조치 자동 실행 방지
운영자 승인 흐름
audit 가능한 판단 구조
엔터프라이즈 확장 가능한 보안/운영 설계
```

---

# 11. 문서 업데이트

다음 문서를 추가하거나 갱신한다.

```text
docs/
  architecture.md
  security.md
  rule-engine.md
  node-agent.md
  operations.md
  testing.md
  roadmap.md
  enterprise-readiness.md
```

## docs/architecture.md

- 전체 구조
- evidence 수집 흐름
- analysis queue 흐름
- incident correlation 흐름
- LLM이 보조 역할만 하는 이유
- 향후 multi-tenancy 확장 가능성
- API/agent protocol versioning 방향

## docs/security.md

- 사용자 인증
- agent bootstrap token
- node token
- webhook token
- mTLS 옵션
- 운영 profile fail-fast 정책
- LLM output safety
- 자동 실행 금지 정책
- RBAC 구조
- audit log 대상

## docs/rule-engine.md

- signal detector 구조
- threshold 기준
- severity/confidence 기준
- false positive를 줄이기 위한 원칙
- detector 추가 방법
- confidence score 산정 방식
- rule versioning 방향

## docs/node-agent.md

- collector별 역할
- 필요한 host path
- read-only 수집 원칙
- spool/retry/backoff
- collector timeout
- 민감정보 redaction
- agent token/node token
- agent version/protocol version
- air-gapped 환경 고려사항

## docs/operations.md

- 운영 환경변수
- production profile 실행 방법
- PostgreSQL/MariaDB 설정
- backup/restore
- troubleshooting
- Helm install
- private registry 설정
- air-gapped 설치 방향

## docs/testing.md

- 테스트 실행 방법
- Java test
- Python pytest
- Helm validation
- Docker build
- GitHub Actions 흐름
- 보안 테스트
- 동시성 테스트
- 반례 테스트

## docs/roadmap.md

- MVP 개선 항목
- 포트폴리오 기능
- 엔터프라이즈 확장 항목
- 우선순위
- 제외할 기능

## docs/enterprise-readiness.md

- multi-tenancy
- enterprise RBAC
- SSO/OIDC/SAML
- audit/compliance
- retention policy
- encryption/KMS
- HA/scalability
- air-gapped/on-prem
- API versioning
- supply chain security
- observability/SLO
- backup/DR

---

# 12. 최종 개발 순서

## Phase 1: 구조 안정화

```text
1. Repository 책임 분리
2. RuleBasedRcaAnalyzer detector 분리
3. Node Agent collector 분리
4. 반례 테스트 추가
```

## Phase 2: 보안 강화

```text
1. Agent/Webhook Filter 인증 강화
2. 운영 profile fail-fast 추가
3. 기본 secret 차단
4. RBAC 기초 구조 정리
5. 보안 테스트 추가
```

## Phase 3: 검증 자동화

```text
1. GitHub Actions CI 추가
2. Java test
3. Python pytest
4. Helm lint/template
5. Docker build gate
6. 향후 security scan 자리 마련
```

## Phase 4: 포트폴리오 기능 추가

```text
1. Demo Scenario Mode
2. Incident Timeline
3. Evidence Bundle Export
4. RCA Confidence Score
5. Agent Health Dashboard
```

## Phase 5: 운영 기능 확장

```text
1. Action Approval Workflow
2. Slack/Discord/Email 알림
3. 영향 범위 분석
```

## Phase 6: 엔터프라이즈 확장 기반

```text
1. enterprise-readiness.md 작성
2. tenant/access scope 설계 반영
3. role/permission 구조 정리
4. retention policy 문서화
5. API versioning 방향 정리
6. agent protocol version 도입
7. audit log 범위 확대
```

## Phase 7: 엔터프라이즈 기능 실제 구현

```text
1. OIDC login
2. tenant model
3. advanced RBAC
4. audit export
5. retention cleanup job
6. agent token rotation
7. mTLS authentication
8. HA worker scaling
9. supply chain security CI
10. air-gapped install guide
```

---

# 13. 코드 수정 시 주의사항

## 절대 하지 말 것

- LLM이 제안한 action을 직접 실행하도록 만들지 말 것
- node reboot, kubeadm reset, kubectl delete, rm -rf 같은 위험 조치를 자동 실행하지 말 것
- agent가 host filesystem에 쓰기 가능한 mount를 요구하도록 바꾸지 말 것
- 운영 환경에서 기본 secret을 허용하지 말 것
- 기존 public API를 불필요하게 크게 깨지 말 것
- 테스트를 삭제해서 통과시키지 말 것
- demo 기능이 운영 환경에서 실수로 활성화되지 않게 할 것
- confidence score를 자동 실행 허용 기준으로 단독 사용하지 말 것
- multi-tenancy를 어설프게 구현해서 보안 경계가 있는 것처럼 보이게 하지 말 것
- RBAC를 UI에서만 막고 API에서 검증하지 않는 구조로 만들지 말 것

## 유지해야 할 원칙

- Read-only evidence collection 우선
- 위험 조치는 human approval 또는 GitOps PR만 허용
- LLM은 설명/추천만 담당
- Policy Engine이 최종 safety guardrail 역할을 수행
- Agent는 backend 장애 시 evidence response를 spool하고 재시도
- Queue는 durable해야 하며 중복 처리에 강해야 함
- 모든 중요한 변경은 audit event로 기록
- Report는 원인뿐 아니라 근거와 판단 과정을 보여줘야 함
- 운영 profile은 안전하지 않은 설정에서 시작되면 안 됨
- 향후 엔터프라이즈 확장을 막는 전역/하드코딩 구조를 피해야 함

---

# 14. 최종 산출물

작업 후 다음을 제공한다.

1. 변경된 파일 목록
2. 주요 구조 변경 요약
3. 보안 개선 요약
4. 테스트 추가 내역
5. 추가 기능 구현 내역
6. 엔터프라이즈 확장 고려사항 반영 내역
7. 기존 동작과 달라진 점
8. 실행 방법
9. 남은 TODO
10. 포트폴리오/면접에서 설명할 수 있는 핵심 개선 포인트

---

# 15. 포트폴리오/면접 설명 문장

```text
이 프로젝트는 Kubernetes 애플리케이션 장애가 아니라,
클러스터 노드와 Linux 시스템 레벨 장애를 대상으로 한 RCA 플랫폼입니다.

Node Agent가 kubelet, container runtime, systemd, kernel, disk, memory,
network, conntrack, DNS/CNI 관련 evidence를 수집하고,
중앙 Platform이 rule-based detector와 optional LLM 분석을 통해
root cause candidate, confidence score, timeline, recommended action을 생성합니다.

LLM은 자동 조치 실행자가 아니라 보조 분석기로 제한했고,
모든 위험 조치는 Policy Engine을 통해 approval required, GitOps PR only,
never auto execute 등으로 분류되도록 설계했습니다.

또한 운영 환경에서는 기본 secret, 빈 webhook token, 개발용 설정이 사용되면
애플리케이션이 시작되지 않도록 fail-fast 검증을 추가하고,
agent/webhook 인증, RBAC, audit log, evidence export, CI 검증을 통해
엔터프라이즈 확장 가능한 구조를 목표로 설계했습니다.
```

---

# 16. 기대하는 최종 상태

이 프로젝트는 단순한 데모가 아니라 다음을 보여주는 포트폴리오가 되어야 한다.

```text
Kubernetes/Linux node-level RCA 이해
Agent 기반 evidence 수집 구조
Spring Boot API/보안/DB 설계
Queue 기반 비동기 분석 처리
Rule-based diagnosis 설계
LLM을 안전하게 보조 분석기로 사용하는 구조
위험 작업 자동화 방지
운영 profile 보안 fail-fast
테스트와 CI/CD 기반 검증
Helm/Docker 기반 배포 가능성
장애 timeline과 evidence 기반 RCA 설명
Demo scenario 기반 재현 가능한 시연
Agent health dashboard 기반 운영성
Action approval/audit 기반 안전한 운영 흐름
Enterprise RBAC로 확장 가능한 권한 구조
Multi-tenancy로 확장 가능한 데이터 모델
SSO/OIDC/SAML 연동 가능성
Audit/compliance/retention 고려
Encryption/KMS/secret management 고려
HA/scalability 고려
Air-gapped/on-prem 설치 고려
API/agent protocol versioning 고려
Supply chain security 고려
```

가장 중요한 개선 방향은 다음 한 문장으로 요약할 수 있다.

> 기능을 더 추가하기보다, 지금 있는 기능을 실무 서비스처럼 유지보수 가능하고 안전한 구조로 분리하고 검증한 뒤, 장애의 원인과 근거를 운영자가 납득할 수 있게 보여주며, 향후 엔터프라이즈 환경으로 확장 가능한 기반을 갖추는 것이다.
