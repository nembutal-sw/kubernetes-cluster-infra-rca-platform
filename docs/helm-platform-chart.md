# Platform Helm Chart

`charts/cluster-infra-rca-platform` deploys the backend API and Spring Boot web console.

The chart expects an external PostgreSQL or MariaDB database. It does not install a database by default.

## Install

```bash
helm upgrade --install rca-platform charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --create-namespace \
  --set backend.image.repository=ghcr.io/acme/cluster-infra-rca-backend \
  --set backend.image.tag=v0.1.0 \
  --set webConsole.image.repository=ghcr.io/acme/cluster-infra-rca-web-console \
  --set webConsole.image.tag=v0.1.0 \
  --set backend.secret.databaseUrl='postgresql+psycopg://rca:password@postgresql.example:5432/rca' \
  --set backend.secret.defaultAdminPassword='change-this-password' \
  --set backend.secret.webhookToken='change-this-token'
```

MariaDB example:

```bash
--set backend.secret.databaseUrl='mysql+pymysql://rca:password@mariadb.example:3306/rca'
```

## Ingress

Expose the web console:

```yaml
ingress:
  webConsole:
    enabled: true
    className: nginx
    hosts:
      - host: rca.example.com
        paths:
          - path: /
            pathType: Prefix
```

Expose the backend API for node agents and Alertmanager:

```yaml
ingress:
  backend:
    enabled: true
    className: nginx
    hosts:
      - host: rca-api.example.com
        paths:
          - path: /
            pathType: Prefix
webConsole:
  config:
    publicApiBaseUrl: https://rca-api.example.com
```

`webConsole.config.publicApiBaseUrl` is used in the UI when generating agent install commands and webhook endpoint text.

## Existing Secret

Use an existing Secret when credentials are managed outside Helm:

```yaml
backend:
  secret:
    create: false
    existingSecret: rca-backend-secret
```

The Secret must provide:

```text
RCA_DATABASE_URL
RCA_DEFAULT_ADMIN_USERNAME
RCA_DEFAULT_ADMIN_PASSWORD
RCA_WEBHOOK_TOKEN
```

Optional LLM keys:

```text
RCA_OPENAI_API_KEY
RCA_ANTHROPIC_API_KEY
RCA_GEMINI_API_KEY
```

## Notes

- Replace the default `admin/admin` credential before production use.
- Keep `backend.config.runMigrations=true` unless migrations are handled by a separate job.
- Set `backend.config.llmProvider` to `openai`, `anthropic`, `gemini`, `openai_compatible`, or `self_hosted` only after the matching endpoint and key are configured.
- The action request API only starts read-only evidence collection for rule-based `AUTO_SAFE` actions. Mutating actions remain blocked, approval-gated, or PR-only.
