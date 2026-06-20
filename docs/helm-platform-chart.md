# Platform Helm Chart

## 한국어 요약

`charts/cluster-infra-rca-platform` 차트는 Spring Boot RCA Platform을 Kubernetes에 배포하기 위한 Helm chart입니다.

현재 chart는 Platform Deployment/Service/Ingress, optional PostgreSQL/MariaDB, Secret/ConfigMap, optional ServiceMonitor를 제공합니다. 운영 환경에서는 기본 secret을 그대로 쓰면 Platform이 fail-fast로 시작되지 않도록 설계되어 있습니다.

중요한 변경점은 다음입니다.

- Agent protocol/platform version 설정을 chart values로 주입합니다.
- Observability metric 설정과 `RCA_METRICS_TOKEN`을 지원합니다.
- Prometheus Operator 환경에서는 optional ServiceMonitor를 만들 수 있습니다.
- Demo mode는 production에서 활성화하면 안 됩니다.
- Agent-side mutation execution은 Platform chart와 Agent chart 모두에서 제거되었습니다.

---

## English Reference

## Chart Path

```text
charts/cluster-infra-rca-platform
```

## Components

The chart can render:

- Platform `Deployment`
- Platform `Service`
- optional `Ingress`
- platform `Secret`
- optional PostgreSQL or MariaDB database resources
- optional `ServiceMonitor` for Prometheus Operator

## Important Values

```yaml
platform:
  image:
    repository: ghcr.io/example/cluster-infra-rca-platform
    tag: latest
  service:
    type: ClusterIP
    port: 8080
  serviceMonitor:
    enabled: false
    interval: 30s
    scrapeTimeout: 10s
    metricsTokenSecretName: ""
    metricsTokenSecretKey: RCA_METRICS_TOKEN
  config:
    springProfiles: ""
    publicApiBaseUrl: ""
    sessionTtlHours: 12
    agentOfflineAfterSeconds: 180
    agentExpectedVersion: ""
    agentMinimumSupportedVersion: "0.1.0"
    agentProtocolVersion: "1"
    agentMinimumSupportedProtocolVersion: "1"
    platformVersion: "0.1.0"
    monitoringEnabled: false
    monitoringIntervalMs: 60000
    observabilityEnabled: true
    observabilityRefreshIntervalMs: 15000
    observabilityInitialDelayMs: 5000
    llmEnabled: false
    llmProvider: none
    llmModel: ""
  secret:
    defaultAdminUsername: admin
    defaultAdminPassword: admin
    webhookToken: dev-webhook-token
    metricsToken: ""
    encryptionSecret: ""
    slackWebhookUrl: ""
```

## Production Profile Requirements

When `SPRING_PROFILES_ACTIVE=prod` or `production`, unsafe defaults are rejected at startup.

Required production-safe values include:

```text
RCA_DEFAULT_ADMIN_PASSWORD
RCA_WEBHOOK_TOKEN
RCA_DB_PASSWORD
RCA_PUBLIC_API_BASE_URL=https://...
RCA_ENCRYPTION_SECRET
RCA_AUDIT_ENABLED=true
RCA_DEMO_ENABLED=false
RCA_METRICS_TOKEN, when observability is enabled
RCA_SLACK_WEBHOOK_URL=https://..., when notification is enabled
```

## Observability

The platform exposes Micrometer/Actuator metrics:

```text
/actuator/metrics
/actuator/prometheus
```

If `platform.serviceMonitor.enabled=true`, the chart renders a `ServiceMonitor` that scrapes `/actuator/prometheus` using a bearer token from the platform secret or a configured secret.

Example:

```bash
helm upgrade --install rca-platform charts/cluster-infra-rca-platform \
  --set platform.serviceMonitor.enabled=true \
  --set platform.secret.metricsToken='replace-with-long-random-token'
```

## Agent Compatibility Settings

The platform publishes compatibility information through `/api/v1/platform/info`.

```yaml
platform:
  config:
    agentMinimumSupportedVersion: "0.1.0"
    agentProtocolVersion: "1"
    agentMinimumSupportedProtocolVersion: "1"
    platformVersion: "0.1.0"
```

Unsupported agents are classified as `version_mismatch` in the Agent Health dashboard.

## LLM Configuration

LLM is optional. Rule-based RCA works without cloud LLM access.

```yaml
platform:
  config:
    llmEnabled: true
    llmProvider: openai-sdk
    llmModel: gpt-4.1-mini
  secret:
    openaiApiKey: "..."
```

LLM output is diagnostic only. It cannot enable host mutation execution.

## Demo Mode

Demo scenarios are useful for local portfolio demos.

```yaml
platform:
  config:
    demoEnabled: true
```

Demo mode must remain disabled in production.

## Validation

Run:

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform
```

The repository CI should also run Helm validation before Docker image build.
