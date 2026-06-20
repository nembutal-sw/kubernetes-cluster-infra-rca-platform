# Platform Helm Chart

## 한국어 요약

`charts/cluster-infra-rca-platform` 차트는 Spring Boot RCA Platform을 Kubernetes에 배포하기 위한 Helm chart입니다.

현재 차트는 Platform Deployment, Service, optional Ingress, optional PostgreSQL/MariaDB, Secret, Config, 그리고 optional ServiceMonitor를 생성합니다. Node Agent가 직접 운영 환경을 변경하는 기능은 제공하지 않으며, 운영 조치는 manual approval workflow로 처리합니다.

운영 환경에서는 기본값 그대로 배포하지 않아야 합니다. `prod` profile에서는 약한 기본 설정, demo mode, insecure public URL 등이 fail-fast 대상입니다.

---

## English Reference

## Chart Path

```text
charts/cluster-infra-rca-platform
```

## Main Resources

The chart can render:

- Platform Deployment
- Platform Service
- Optional Ingress
- Platform Secret
- Platform Config through environment variables
- Optional PostgreSQL StatefulSet
- Optional MariaDB StatefulSet
- Optional ServiceMonitor for Prometheus Operator

## Important Values

```yaml
platform:
  service:
    type: ClusterIP
    port: 8080
  serviceMonitor:
    enabled: false
    interval: 30s
    scrapeTimeout: 10s
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
    observabilityEnabled: true
  secret:
    defaultAdminUsername: admin
    defaultAdminPassword: admin
    webhookToken: dev-webhook-token
    metricsToken: ""
    encryptionSecret: ""
    slackWebhookUrl: ""
```

## Production Notes

For production-like deployments:

- enable the `prod` or `production` Spring profile
- use an absolute HTTPS public API URL
- disable demo mode
- keep audit enabled
- configure strong secret values outside source control
- configure a metrics token when observability is enabled
- validate rendered manifests before applying

The application performs production fail-fast validation. Unsafe production settings should stop startup instead of creating a weak deployment.

## ServiceMonitor

When `platform.serviceMonitor.enabled=true`, the chart creates a `ServiceMonitor` targeting:

```text
/actuator/prometheus
```

The ServiceMonitor can read the metrics token from the platform secret or another Kubernetes secret.

## Agent Compatibility Configuration

The platform exposes agent compatibility through environment variables:

```text
RCA_AGENT_MINIMUM_SUPPORTED_VERSION
RCA_AGENT_PROTOCOL_VERSION
RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION
RCA_PLATFORM_VERSION
```

These values are also visible through:

```text
GET /api/v1/platform/info
```

## What This Chart Does Not Do

The platform chart does not deploy the Node Agent DaemonSet. Use the agent chart for node-level evidence collection.

The platform chart does not enable automatic operational change execution. Action approval is manual-only.

## Local Validation

```bash
helm lint charts/cluster-infra-rca-platform
helm template rca-platform charts/cluster-infra-rca-platform
```
