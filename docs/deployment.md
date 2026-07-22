# Deployment

## Docker Compose

로컬 또는 관리 VM에서는 Docker Compose로 Platform과 PostgreSQL을 함께 실행할 수 있습니다.

```bash
cp .env.example .env
docker compose up --build -d
```

기본 노출 주소는 다음과 같습니다.

- Platform: `127.0.0.1:8080`
- PostgreSQL: `127.0.0.1:5432`

Agent가 다른 노드에서 Platform에 접근해야 한다면 `RCA_BIND_ADDRESS`를 관리 네트워크의 명시적인 IP로 설정합니다. 데모 환경에서도 `0.0.0.0` 대신 Tailscale 또는 사내 관리망 주소를 권장합니다.

MariaDB를 사용할 때는 다음 프로필을 사용하고 JDBC URL과 DB 계정을 함께 변경합니다.

```bash
docker compose --profile mariadb up -d mariadb
```

```dotenv
RCA_JDBC_URL=jdbc:mariadb://mariadb:3306/rca
```

## Demo Deployment

`scripts/deploy-compose-demo.sh`는 데모 스택의 빌드와 배포를 한 번에 수행합니다. 실행 전에 권한이 `600`인 환경 파일을 준비해야 합니다.

```bash
chmod 600 ~/.config/cluster-infra-rca-platform/demo.env
bash scripts/deploy-compose-demo.sh \
  --env-file ~/.config/cluster-infra-rca-platform/demo.env
```

배포 스크립트는 다음 순서로 동작합니다.

1. 필수 비밀값과 명시적 bind address를 검증합니다.
2. 실행 중인 PostgreSQL의 논리 백업을 생성합니다.
3. 새 Platform 이미지를 먼저 빌드합니다.
4. PostgreSQL과 Platform의 health 상태를 확인합니다.
5. Platform이 준비되지 않으면 이전 이미지로 되돌립니다.

데모 환경 파일에는 `RCA_DEMO_ENABLED=true`가 필요합니다. LLM을 연결하지 않아도 rule-based RCA와 데모 시나리오는 동작합니다.

## Continuous Deployment

`.github/workflows/deploy-demo.yml`은 `CI`가 성공한 `main` 커밋만 `rca-demo` 라벨의 self-hosted runner에 배포합니다. 수동 실행도 지원합니다.

같은 서버의 K3s 데모 Agent까지 갱신하려면 서버 로컬 `demo.env`에 `RCA_DEMO_K3S_AGENT_ENABLED=true`를 설정합니다. 워크플로는 현재 커밋의 Agent 이미지를 빌드해 K3s containerd에 import하고, 기존 Secret을 재사용해 `node-diagnostics` 모드로 배포합니다. 연결 확인에 실패하면 이전 이미지와 Backend URL로 롤백합니다.

- runner는 배포 서버의 일반 사용자로 실행합니다.
- runner 사용자에게 Docker 권한이 필요합니다.
- 비밀값은 저장소나 Actions secret으로 복사하지 않고 서버의 `demo.env`에만 둡니다.
- pull request 워크플로에서는 배포 runner 라벨을 사용하지 않습니다.
- GitHub 환경 이름은 `suse-demo`이며 필요하면 승인 규칙을 추가합니다.

self-hosted runner는 저장소의 코드를 서버에서 실행할 수 있으므로 쓰기 권한과 branch protection을 제한해야 합니다.

## Kubernetes

Platform chart는 PostgreSQL 또는 MariaDB를 선택적으로 함께 배포할 수 있습니다.

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform
```

운영 환경에서는 장애 대상 클러스터와 분리된 관리 클러스터 또는 VM에 Platform을 배포하는 구성을 권장합니다. 같은 클러스터에 배포할 때는 별도 node pool, PodDisruptionBudget, 외부 DB 백업, 독립적인 상태 확인 경로를 준비해야 합니다.

필수 운영 항목은 다음과 같습니다.

- 기본 관리자 계정, webhook token, 암호화 키 교체
- TLS Ingress와 관리망 접근 제어
- DB 백업 및 복구 훈련
- Agent가 접근할 수 있는 `RCA_PUBLIC_API_BASE_URL` 설정
- LLM API key를 Kubernetes Secret 또는 외부 secret manager로 관리
- Pipeline의 `retry_wait`, `dead_letter`, lease 상태 모니터링

## LLM Provider

LLM은 기본적으로 비활성화되어 있습니다. OpenAI 호환 API 예시는 다음과 같습니다.

```dotenv
RCA_LLM_ENABLED=true
RCA_LLM_PROVIDER=openai_compatible
RCA_LLM_MODEL=provider-model-name
RCA_SPRING_AI_CHAT_MODEL=openai-sdk
OPENAI_API_KEY=...
OPENAI_BASE_URL=https://llm-gateway.example.com/v1
```

Kubernetes에서는 provider와 model 같은 일반 설정만 values에 두고, API key와 base URL은 Secret 또는 External Secrets Operator로 주입합니다. Settings의 LLM diagnostics는 설정 여부만 표시하며 API key 원문은 노출하지 않습니다.

## Notifications

Incident 알림은 선택 기능입니다. Slack 또는 일반 webhook을 사용할 수 있습니다.

```dotenv
RCA_NOTIFICATION_ENABLED=true
RCA_NOTIFICATION_MINIMUM_SEVERITY=critical
RCA_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
RCA_NOTIFICATION_MAX_ATTEMPTS=2
RCA_NOTIFICATION_TIMEOUT_SECONDS=5
RCA_NOTIFICATION_BATCH_SIZE=10
RCA_NOTIFICATION_POLL_INTERVAL_MS=1000
RCA_NOTIFICATION_LEASE_SECONDS=60
RCA_NOTIFICATION_RETRY_BASE_SECONDS=5
RCA_NOTIFICATION_RETRY_MAX_SECONDS=300
```

일반 webhook에서 `RCA_NOTIFICATION_WEBHOOK_TOKEN`을 설정하면 `Authorization: Bearer` 헤더가 추가됩니다. 운영 profile에서는 HTTPS endpoint를 사용해야 합니다.

Incident와 알림 event는 같은 DB transaction에 저장됩니다. 별도 worker가 lease를 획득해 전달하며,
`408`, `425`, `429`, `5xx`와 네트워크 오류는 지수 backoff로 재시도합니다. 그 외 `4xx`와
최대 시도 횟수를 소진한 event는 `dead_letter`로 이동합니다. 일반 webhook 요청에는
`Idempotency-Key` 헤더가 포함됩니다.

`ADMIN`, `OPERATOR`, `AUDITOR`는 `GET /api/notifications/outbox`에서 payload를 제외한 상태를
확인할 수 있습니다. `ADMIN` 또는 `OPERATOR`는 확인 요청과 함께
`POST /api/notifications/outbox/{eventId}/retry`를 호출해 dead-letter event를 다시 queue에 넣을 수 있습니다.

## GitOps

운영 catalog 변경을 draft PR 또는 MR로 제안하려면 GitOps 연동을 활성화합니다.

```dotenv
RCA_GITOPS_ENABLED=true
RCA_GITOPS_PROVIDER=github
RCA_GITOPS_REPOSITORY=namespace/repository
RCA_GITOPS_BASE_BRANCH=main
RCA_GITOPS_TOKEN=...
RCA_GITOPS_WEBHOOK_SECRET=...
```

token과 webhook secret은 환경 파일 또는 Secret에만 저장합니다. 자세한 흐름은 [gitops.md](gitops.md)를 참고합니다.
