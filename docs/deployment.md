# Deployment

## Docker Compose

```bash
docker compose up --build -d
```

기본 구성:

- Platform: `127.0.0.1:8080`
- PostgreSQL: `127.0.0.1:5432`

MariaDB를 사용할 때:

```bash
docker compose --profile mariadb up -d mariadb
```

그 후 `RCA_JDBC_URL=jdbc:mariadb://mariadb:3306/rca`를 지정합니다.

## Kubernetes

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform
```

운영 권장 사항:

- 진단 대상과 다른 관리 클러스터 또는 VM에 중앙 플랫폼 배포
- 기본 admin 비밀번호와 webhook token 교체
- 외부 TLS Ingress 사용
- DB volume 백업 및 복구 절차 준비
- Agent가 접근 가능한 `RCA_PUBLIC_API_BASE_URL` 설정
- LLM API key는 Kubernetes Secret 또는 외부 secret manager 사용
- 알림이 필요하면 `RCA_SLACK_WEBHOOK_URL` 또는 `RCA_NOTIFICATION_WEBHOOK_URL` 설정

중앙 플랫폼을 진단 대상 클러스터에 배포해야 한다면 최소한 별도 node pool, PodDisruptionBudget, DB 백업, 외부 상태 확인 경로를 구성해야 합니다.

Platform chart는 다음 운영 옵션을 제공합니다.

- 전용 ServiceAccount와 service account token 비활성화
- PodDisruptionBudget, rolling update, graceful shutdown
- 선택적 ingress NetworkPolicy
- External Secrets Operator 연동
- PostgreSQL/MariaDB backup CronJob
- topology spread constraint와 replica 확장

중앙 플랫폼 자체가 장애 클러스터와 함께 중단되지 않도록 별도 관리 클러스터 또는 외부 VM 배포를 권장합니다.

RCA 분석은 DB queue에서 비동기로 처리됩니다. 운영 중에는 Pipeline 화면에서 `retry_wait`와 `dead_letter` 작업을 확인하고, LLM 최대 처리 시간보다 task lease를 길게 설정합니다. 자세한 설정은 [durable-analysis-pipeline.md](durable-analysis-pipeline.md)를 참고합니다.

## Notification Delivery

Incident notification은 선택 기능입니다.

```bash
RCA_NOTIFICATION_ENABLED=true
RCA_NOTIFICATION_MINIMUM_SEVERITY=critical
RCA_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
```

Slack 대신 SIEM, ITSM, event router로 structured JSON을 보내려면 generic webhook을 사용합니다.

```bash
RCA_NOTIFICATION_ENABLED=true
RCA_NOTIFICATION_WEBHOOK_URL=https://siem.example.com/rca/events
RCA_NOTIFICATION_WEBHOOK_TOKEN=...
```

`RCA_NOTIFICATION_WEBHOOK_TOKEN`이 설정되면 Platform은 `Authorization: Bearer <token>` header를 추가합니다. Production profile에서는 notification이 켜져 있을 때 Slack 또는 generic webhook 중 하나 이상이 HTTPS URL이어야 합니다.
