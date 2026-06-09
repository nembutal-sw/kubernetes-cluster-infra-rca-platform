# AI 기반 Kubernetes Cluster Infra RCA 플랫폼

Kubernetes에서 보이는 장애 증상 뒤에 숨어 있는 노드, 런타임, 커널, 네트워크, 디스크, systemd 수준의 실제 원인을 자동 수집·분석하는 AI 기반 클러스터 인프라 RCA 플랫폼입니다.

이 프로젝트는 일반적인 애플리케이션 장애 분석 도구가 아닙니다. `NodeNotReady`, `Pod Pending`, `CoreDNS 불안정`, `API Server 응답 지연`, `CNI 통신 실패`처럼 Kubernetes 표면에서 드러나는 증상을 시작점으로 삼되, 실제 원인이 Linux 시스템과 클러스터 인프라 계층에 있는지 자동으로 확인하는 것을 목표로 합니다.

## 핵심 원칙

- LLM은 진단과 설명만 담당합니다.
- LLM은 직접 수정, 재시작, 삭제, 스케일 조정, 설정 변경을 수행하지 않습니다.
- 조치 가능성은 Policy Engine이 분류합니다.
- 실제 실행은 안전 등급, 승인 흐름, GitOps/PR 흐름에 따라 제어합니다.
- 운영자는 노드에 직접 접속하지 않아도 장애 당시의 핵심 증거를 보고서로 확인할 수 있어야 합니다.

## 주요 RCA 대상

이 플랫폼의 메인 분석 대상은 Kubernetes 애플리케이션 자체보다 클러스터 노드와 시스템 계층입니다.

- `NodeNotReady`
- `DiskPressure`
- `MemoryPressure`
- `PIDPressure`
- `NetworkUnavailable`
- kubelet 장애
- containerd 장애
- CNI 장애
- CoreDNS 장애
- etcd latency 증가
- API Server 지연
- 디스크 I/O 문제
- 커널 로그 에러
- systemd 서비스 장애
- 노드 네트워크 문제
- conntrack table 고갈
- inode 고갈

## 보조 신호

아래 항목은 메인 RCA 대상이라기보다 노드/시스템 장애의 결과로 나타날 수 있는 보조 증상으로 다룹니다.

- `CrashLoopBackOff`
- `ImagePullBackOff`
- Pod `OOMKilled`
- Deployment rollout 실패
- HTTP 5xx 증가
- Service endpoint 없음
- Ingress 설정 오류

## 전체 흐름

1. 사용자가 Web UI에서 클러스터를 등록합니다.
2. Backend가 해당 클러스터용 Agent 설치 명령어를 제공합니다.
3. 운영자는 각 클러스터에 DaemonSet 형태로 Node Agent를 배포합니다.
4. Node Agent는 각 노드의 로그, systemd, 커널, 디스크, 메모리, 네트워크, container runtime, kubelet 상태를 수집합니다.
5. Prometheus 또는 Alertmanager가 장애를 감지하면 Backend webhook으로 이벤트를 보냅니다.
6. RCA Backend는 관련 노드와 시간대를 식별하고 Agent에서 증거를 수집합니다.
7. LLM Analyzer는 수집된 증거를 바탕으로 원인 후보, 근거, 영향 범위, 추가 확인 사항을 분석합니다.
8. Policy Engine은 권장 조치를 안전 등급별로 분류합니다.
9. Report Generator가 운영자용 RCA 보고서를 생성합니다.

## 주요 컴포넌트

- Web UI: 클러스터 등록, Agent 설치 명령어 제공, RCA 보고서 조회
- API Backend: 클러스터/노드/알림/RCA job 관리
- Webhook Receiver: Prometheus, Alertmanager 이벤트 수신
- Node Agent: DaemonSet으로 배포되는 노드 로컬 증거 수집기
- Evidence Collector: 장애 시간대와 대상 노드 기준으로 증거 패키지 생성
- LLM Analyzer: 증거 기반 진단과 설명 생성
- Policy Engine: 조치 안전 등급 분류
- Report Generator: RCA 보고서 생성

## 저장소 구조

```text
.
|-- README.md
|-- docs/
|   |-- architecture.md
|   |-- agent-design.md
|   |-- install-flow.md
|   |-- policy-engine.md
|   |-- rca-scope.md
|   |-- report-schema.md
|   `-- roadmap.md
|-- examples/
|   |-- alertmanager-webhook.json
|   `-- rca-report.example.json
`-- manifests/
    `-- agent-daemonset.yaml
```

## 현재 상태

이 저장소는 프로젝트 방향과 MVP 설계를 정리한 초기 문서 스캐폴드입니다. 다음 단계에서는 Backend API, Node Agent, Web UI, Policy Engine, Report Generator 구현을 순차적으로 추가합니다.
