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

중앙 플랫폼을 진단 대상 클러스터에 배포해야 한다면 최소한 별도 node pool, PodDisruptionBudget, DB 백업, 외부 상태 확인 경로를 구성해야 합니다.

Platform chart는 다음 운영 옵션을 제공합니다.

- 전용 ServiceAccount와 service account token 비활성화
- PodDisruptionBudget, rolling update, graceful shutdown
- 선택적 ingress NetworkPolicy
- External Secrets Operator 연동
- PostgreSQL/MariaDB backup CronJob
- topology spread constraint와 replica 확장

중앙 플랫폼 자체가 장애 클러스터와 함께 중단되지 않도록 별도 관리 클러스터 또는 외부 VM 배포를 권장합니다.
