# Kubernetes Cluster Infra RCA Platform

Kubernetes 장애를 보다 보면 처음에는 전부 비슷하게 보입니다. `NodeNotReady`, `Pod Pending`, `CoreDNS` 불안정, API Server 응답 지연, CNI 통신 실패 같은 식입니다.

그런데 막상 노드에 들어가 보면 원인은 Kubernetes 리소스가 아니라 Linux 시스템 쪽에 있는 경우가 많습니다. 디스크 I/O가 밀려 있거나, inode가 다 찼거나, containerd가 멈춰 있거나, kubelet이 계속 재시작되고 있거나, conntrack table이 거의 꽉 차 있는 식입니다.

이 프로젝트는 그때 운영자가 노드에 접속해서 하나씩 확인하던 과정을 최대한 자동화해보려는 시도입니다. Alertmanager에서 장애 알림이 들어오면 관련 노드와 시간대를 기준으로 증거를 모으고, 그 증거를 바탕으로 RCA 보고서를 만드는 것이 목표입니다.

## 이 프로젝트가 보려는 것

주 대상은 애플리케이션 장애가 아니라 클러스터 인프라와 노드 레벨 문제입니다.

- `NodeNotReady`
- `DiskPressure`, `MemoryPressure`, `PIDPressure`
- `NetworkUnavailable`
- kubelet 장애
- containerd 장애
- CNI 장애
- CoreDNS 장애
- etcd latency 증가
- API Server 응답 지연
- 디스크 I/O 병목
- inode 고갈
- kernel log error
- systemd unit 실패 또는 반복 재시작
- NIC link flap
- DNS 설정 문제
- CNI MTU 문제
- conntrack table 고갈

`CrashLoopBackOff`, `ImagePullBackOff`, Pod `OOMKilled`, HTTP 5xx, Ingress 설정 오류 같은 항목은 메인 분석 대상이라기보다는 보조 신호로 봅니다. 앱 자체 문제가 아니라 노드나 네트워크 문제가 위쪽에서 그렇게 보이는 경우가 있기 때문입니다.

## 기본 흐름

1. Web UI에서 클러스터를 등록한다.
2. Backend가 Agent 설치 명령어를 만들어준다.
3. 각 노드에 DaemonSet 형태로 Agent를 배포한다.
4. Agent는 노드 로컬 로그, systemd 상태, kernel log, 디스크/메모리/네트워크 상태, container runtime, kubelet 상태를 수집한다.
5. Prometheus 또는 Alertmanager가 장애를 감지하면 Backend webhook으로 보낸다.
6. Backend는 해당 노드 Agent가 등록되어 있으면 evidence request를 먼저 만든다.
7. Agent가 증거를 수집해서 Backend에 제출한다.
8. Evidence Preprocessor가 raw log와 collector 결과를 LLM 입력용 JSON으로 정리한다.
9. Analyzer가 원인 후보와 근거를 정리한다.
10. Policy Engine이 권장 조치를 안전 등급별로 나눈다.
11. 운영자가 볼 수 있는 RCA report를 만든다.

중요한 점은 LLM이 직접 조치를 실행하지 않는다는 것입니다. LLM은 진단과 설명만 맡고, 실제 조치 가능 여부는 Policy Engine과 승인 흐름에서 판단합니다.

## 현재 들어있는 것

아직 전체 플랫폼이 완성된 것은 아니고, 지금은 Backend API MVP까지 만들어둔 상태입니다.

현재 가능한 일:

- 클러스터 등록
- Agent 설치 명령어 조회
- 클러스터별 Agent manifest 생성
- Alertmanager webhook 수신
- Alertmanager 알림을 Agent evidence request로 연결
- fake evidence 생성
- evidence field 기반 RCA signal 추출과 RCA report 생성
- Policy Engine 기반 권장 조치 분류
- RCA job/report 조회
- PostgreSQL/MariaDB 호환 SQLAlchemy 저장소
- Alembic 기반 DB migration
- Node Agent 등록/heartbeat API
- Agent evidence request/response API
- Agent evidence 제출 이후 RCA job/report 자동 생성
- Node Agent MVP collector와 DaemonSet manifest
- LLM 입력용 evidence preprocessing
- provider 교체 가능한 LLM Analyzer adapter

Node Agent collector는 MVP 수준입니다. Linux hostPath에서 읽을 수 있는 `/proc`, `/etc`, `/var/log`, `/run` 기반 정보를 수집하고, 접근 권한이나 명령어 부재로 실패한 항목은 agent를 죽이지 않고 evidence 안에 오류로 남깁니다. LLM Analyzer adapter는 들어갔지만 기본값은 비활성화이며, Web UI는 다음 단계에서 붙일 예정입니다.

Agent manifest는 `GET /api/clusters/{cluster_id}/agent-manifest`에서 생성합니다. `backend_url`, `image`, `namespace`를 query parameter로 넘기면 환경별 DaemonSet manifest를 받을 수 있습니다.

실제 Linux 노드 검증은 Backend 연결 없이 `python -m node_agent.main --collect-local --output /tmp/cluster-infra-rca-evidence.json`로 먼저 수행합니다. 절차는 [docs/linux-node-collector-validation.md](docs/linux-node-collector-validation.md)에 정리했습니다.

## Backend 실행

```powershell
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn backend.app.main:app --reload
```

서버가 뜨면 아래 주소에서 확인할 수 있습니다.

- API: `http://127.0.0.1:8000`
- Swagger: `http://127.0.0.1:8000/docs`
- Health check: `http://127.0.0.1:8000/health`

자세한 API 흐름은 [docs/backend-api.md](docs/backend-api.md)에 정리해두었습니다. Evidence preprocessing 기준은 [docs/evidence-preprocessing.md](docs/evidence-preprocessing.md), LLM 설정은 [docs/llm-analyzer.md](docs/llm-analyzer.md), RCA 분석 기준은 [docs/rca-analysis-rules.md](docs/rca-analysis-rules.md)에 따로 정리했습니다.

## DB 선택

운영 대상 DB는 PostgreSQL과 MariaDB입니다. `RCA_DATABASE_URL`만 바꿔서 선택합니다.

PostgreSQL:

```powershell
$env:RCA_DATABASE_URL = "postgresql+psycopg://rca:rca_password@localhost:5432/rca"
```

MariaDB:

```powershell
$env:RCA_DATABASE_URL = "mysql+pymysql://rca:rca_password@localhost:3306/rca"
```

로컬 테스트용으로는 SQLite fallback도 열어두었습니다. 자세한 내용은 [docs/database.md](docs/database.md)를 참고합니다.

## 테스트

```powershell
.venv\Scripts\python.exe -m pytest
```

현재 테스트는 클러스터 등록, 설치 명령어 조회, Alertmanager webhook 수신, Agent evidence 흐름, evidence preprocessing, LLM adapter, RCA signal 추출, RCA report 생성, Node Agent collector 기본 동작을 확인합니다.

## 디렉터리 구조

```text
.
|-- backend/
|   `-- app/
|-- node_agent/
|-- migrations/
|   `-- versions/
|-- docs/
|   |-- architecture.md
|   |-- agent-design.md
|   |-- agent-api.md
|   |-- agent-evidence-fields.md
|   |-- backend-api.md
|   |-- database.md
|   |-- evidence-api.md
|   |-- evidence-preprocessing.md
|   |-- install-flow.md
|   |-- linux-node-collector-validation.md
|   |-- llm-analyzer.md
|   |-- policy-engine.md
|   |-- rca-analysis-rules.md
|   |-- rca-scope.md
|   |-- report-schema.md
|   `-- roadmap.md
|-- examples/
|   |-- alertmanager-webhook.json
|   `-- rca-report.example.json
|-- manifests/
|   `-- agent-daemonset.yaml
|-- tests/
|   `-- test_api.py
|-- docker-compose.yml
|-- Dockerfile.agent
|-- alembic.ini
|-- pyproject.toml
|-- requirements.txt
`-- requirements-dev.txt
```

## 다음에 할 일

바로 다음 단계는 둘 중 하나입니다.

- Node Agent collector를 실제 Linux 노드에서 돌려보고 수집 필드와 분석 threshold 보정하기
- 실제 provider API key를 넣고 staging 환경에서 LLM Analyzer 응답 검증하기

DB 저장소, migration, Agent register/heartbeat, evidence request/response 계약, webhook 기반 evidence request 생성, evidence 제출 이후 RCA report 생성, Node Agent MVP collector, evidence preprocessing, provider 교체 가능한 LLM Analyzer, evidence 기반 RCA signal 분석까지 들어갔습니다. 이제 실제 노드에서 collector 결과를 확인하고 부족한 필드와 threshold를 보정해야 합니다.
