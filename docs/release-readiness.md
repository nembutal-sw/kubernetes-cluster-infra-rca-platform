# Release Readiness

릴리즈 전에 확인해야 하는 운영 체크리스트다. 서버 주소, token, password 같은 접속 정보는 문서나 Git에 남기지 않는다.

## 1. Helm

확인 항목:

- `cluster-infra-rca-platform` chart `helm lint`
- `cluster-infra-rca-agent` chart `helm lint`
- platform chart `helm template`
- agent chart `helm template`
- agent manifest에 `Namespace`, `ServiceAccount`, `ClusterRole`, `ClusterRoleBinding`, `ConfigMap`, `Secret`, `DaemonSet` 포함
- LLM enabled chart variant에서 `RCA_LLM_*`, `SPRING_AI_OPENAI_SDK_API_KEY`, `SPRING_AI_OPENAI_SDK_BASE_URL` 렌더링 확인

실패 기준:

- 필수 값 누락
- 잘못된 namespace, image, backend URL
- Agent Secret 누락
- 운영 모드에서 hostPath, hostNetwork, RBAC 범위가 values와 다르게 렌더링됨

## 2. Container

확인 항목:

- platform image build
- agent image build
- Dockerfile base image digest pinning
- platform build 과정에서 Maven `verify`
- frontend `npm ci`, TypeScript check, Vite production build
- Docker Compose LLM env wiring: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OLLAMA_BASE_URL`

통과 기준:

- backend/frontend test 성공
- DB migration 검증 성공
- image healthcheck 명령 존재
- production security validator test 성공
- `scripts/verify-container-pinning.py` 성공

## 3. API Security Contract

확인 항목:

- `/api/**` endpoint 인증 누락 여부
- Agent/Webhook/Manifest 전용 인증 필터 coverage
- export, evidence bundle, token rotation, cluster delete 역할 범위
- `/api/v1/platform/info` versioned API 유지

통과 기준:

- `scripts/verify-api-contract.py` 성공
- 신규 controller 추가 시 인증 누락은 CI에서 실패
- 민감 export endpoint에 `VIEWER`, `APPROVER` 권한이 들어가지 않음

## 4. Supply Chain

확인 항목:

- Gitleaks secret scan
- Trivy filesystem scan
- Syft repository SBOM 생성
- Grype repository SBOM scan
- platform/agent image SBOM 생성
- platform/agent image Trivy scan
- release image SBOM과 scan report를 GitHub Release asset으로 업로드

통과 기준:

- high/critical fixed vulnerability가 release gate를 통과하지 않음
- SBOM과 scan report artifact가 보존됨
- `scripts/verify-supply-chain-workflows.py` 성공
- `scripts/release-readiness-check.py`의 `supply-chain-ci`, `supply-chain-static-guard`, `release-supply-chain-assets` 검사 성공

## 5. Operational Catalog

확인 항목:

- 기본 catalog JSON schema version
- 필수 collector, alert selection, action, rule 존재
- collector selection이 존재하지 않는 collector를 참조하지 않는지
- 모든 action plan이 `executable=false`인지
- catalog override는 preview/draft/handoff만 제공하고 자동 적용하지 않는지

통과 기준:

- `scripts/verify-operational-catalog.py` 성공
- `scripts/release-readiness-check.py`의 `operational-catalog-static-guard` 검사 성공
- Web Console Settings 화면에서 catalog summary와 override draft 상태 확인 가능

## 6. Smoke Deploy

기존 운영 컨테이너와 네트워크를 건드리지 않도록 고유한 이름의 DB, platform 컨테이너, Docker network를 사용한다.

확인 항목:

- PostgreSQL 또는 MariaDB 연결
- `/health/ready` 응답
- readiness 응답의 `database=reachable`, `bootstrap=completed`
- 최초 관리자 로그인
- cluster 생성 API
- cluster 목록 조회
- backend 자체 monitoring 옵션 확인

주의:

- 초기 관리자 계정 bootstrap이 끝나기 전에는 ready로 보이지 않는다.
- 검증용 credential은 서버 내부 파일에만 저장하고 `chmod 600`을 적용한다.
- 검증이 끝난 컨테이너를 남겨야 하는 경우 포트와 credential 위치를 운영자에게 별도로 공유한다.

## 7. Agent Local Collect

서버에서 직접 Node Agent를 실행해 collector 계약을 확인한다.

Safe mode:

- `node`, `kubernetes`, `dns` 중심 수집
- 위험 collector는 `disabled`로 남길 수 있음

Node diagnostics mode:

- `node`
- `disk`
- `inode`
- `memory`
- `process`
- `network`
- `conntrack`
- `runtime`
- `kubelet`
- `systemd`
- `kernel`
- `cni`
- `dns`
- `kubernetes`

통과 기준:

- 필수 collector key 존재
- 주요 collector 상태가 `failed`가 아님
- evidence JSON이 UTF-8로 저장됨
- 민감정보 redaction 적용

## 8. Kubernetes Canary

실제 Kubernetes 또는 kind 검증 클러스터에서 DaemonSet canary를 먼저 확인한다.

확인 항목:

- agent image를 대상 노드에서 pull 또는 preload
- backend URL을 Pod 또는 hostNetwork 환경에서 접근 가능
- `/api/clusters/{cluster_id}/agent-manifest`로 받은 manifest 적용
- DaemonSet rollout 성공
- backend에 node agent 2개 이상 또는 목표 canary node 등록
- agent health `healthy`
- evidence request 생성
- evidence response `completed`
- evidence collector에 `disk`, `inode`, `kernel`, `systemd`, `runtime`, `kubelet`, `network`, `conntrack` 포함

먼저 볼 실패 신호:

- Secret 누락: Pod `CreateContainerConfigError`
- backend URL 접근 실패: Agent register retry log
- image 미존재: `ImagePullBackOff`
- hostPath 또는 securityContext 거부: Pod event와 admission log
- RBAC 부족: Agent log의 Kubernetes API 403

## Release Gate

릴리즈 후보는 아래 조건을 만족해야 한다.

- Helm lint/template 통과
- `scripts/release-readiness-check.py` 통과
- `scripts/verify-api-contract.py` 통과
- `scripts/verify-container-pinning.py` 통과
- `scripts/verify-operational-catalog.py` 통과
- `scripts/verify-supply-chain-workflows.py` 통과
- platform/agent image build 성공
- Maven test 성공
- Node Agent pytest 성공
- smoke deploy에서 ready/login/cluster create 성공
- node diagnostics local collect 성공
- Kubernetes canary DaemonSet rollout 성공
- canary evidence collection 완료
- Gitleaks, Trivy, Syft SBOM, Grype scan 통과
- 검증 중 발견된 누락은 테스트로 고정
