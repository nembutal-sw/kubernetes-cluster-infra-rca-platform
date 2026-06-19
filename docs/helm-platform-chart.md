# Platform Helm Chart

경로: `charts/cluster-infra-rca-platform`

차트는 Spring Boot Platform Deployment, Service, 선택적 Ingress, PostgreSQL 또는 MariaDB StatefulSet을 생성합니다.

## Database

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.type=postgresql
```

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.type=mariadb
```

외부 DB:

```bash
helm template rca charts/cluster-infra-rca-platform \
  --set database.enabled=false \
  --set-string platform.secret.jdbcUrl='jdbc:postgresql://db.example:5432/rca' \
  --set-string platform.secret.databaseUsername='rca' \
  --set-string platform.secret.databasePassword='change-me'
```

## Existing Secret

`platform.secret.create=false`를 사용할 때 Secret에는 다음 key가 필요합니다.

External Secrets Operator를 사용할 때:

```yaml
platform:
  secret:
    create: false
  externalSecret:
    enabled: true
    secretStoreRef:
      kind: ClusterSecretStore
      name: production-vault
    data:
      - secretKey: RCA_DB_PASSWORD
        remoteRef:
          key: rca/database-password
```

`platform.networkPolicy.enabled=true`, `platform.podDisruptionBudget.enabled=true`, `backup.enabled=true`로 운영 보호 기능을 활성화할 수 있습니다.

- `RCA_JDBC_URL`
- `RCA_DB_USERNAME`
- `RCA_DB_PASSWORD`
- `RCA_DEFAULT_ADMIN_USERNAME`
- `RCA_DEFAULT_ADMIN_PASSWORD`
- `RCA_WEBHOOK_TOKEN`

Spring AI provider key는 선택 사항입니다.

## Image

`platform.image.repository`와 `platform.image.tag`는 실제 registry에 맞게 지정합니다. 저장소 기본값은 placeholder입니다.
