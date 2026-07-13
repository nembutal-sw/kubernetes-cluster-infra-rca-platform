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
    incidentCorrelationWindowMinutes: 15
    incidentCorrelationMinimumScore: 70
    incidentCorrelationCandidateLimit: 20
    incidentAutoResolveEnabled: true
    incidentInactivityMinutes: 60
    incidentLifecycleScanIntervalMs: 60000
    incidentLifecycleBatchSize: 100
    incidentRecurrenceLookbackHours: 168
    maintenanceEnabled: true
    maintenanceCron: "0 17 3 * * *"
    maintenanceBatchSize: 250
    evidenceRetentionDays: 30
    evidenceRequestRetentionDays: 30
    analysisTaskRetentionDays: 30
    realtimeEventRetentionDays: 14
    reportRetentionDays: 365
    exportSignatureKeyId: default
    llmEnabled: false
    llmProvider: none
    llmModel: ""
    springAiChatModel: none
    gitopsEnabled: false
    gitopsProvider: github
    # Empty selects the GitHub or GitLab default. Gitea requires /api/v1.
    gitopsApiBaseUrl: ""
    gitopsRepository: ""
    gitopsBaseBranch: main
    gitopsFilePath: ops/catalog/operational-catalog.override.json
  secret:
    defaultAdminUsername: admin
    defaultAdminPassword: admin
    webhookToken: dev-webhook-token
    metricsToken: ""
    encryptionSecret: ""
    exportSignatureSecret: ""
    slackWebhookUrl: ""
    gitopsToken: ""
    gitopsWebhookSecret: ""
    openaiApiKey: ""
    openaiBaseUrl: ""
    anthropicApiKey: ""
    geminiApiKey: ""
    ollamaBaseUrl: ""
```

`gitopsProvider`는 `github`, `gitlab`, `gitea`만 허용됩니다. GitLab subgroup을 사용할 때 `gitopsRepository`는 `group/subgroup/repository` 형식으로 지정합니다. Gitea는 `gitopsApiBaseUrl`을 반드시 지정하며, 누락하거나 지원하지 않는 provider를 입력하면 Helm template 단계에서 실패합니다.

## Production Notes

For production-like deployments:

- enable the `prod` or `production` Spring profile
- use an absolute HTTPS public API URL
- disable demo mode
- keep audit enabled
- configure strong secret values outside source control
- configure a metrics token when observability is enabled
- review retention periods before connecting production data
- validate rendered manifests before applying

The application performs production fail-fast validation. Unsafe production settings should stop startup instead of creating a weak deployment.

## LLM Configuration

LLM is disabled by default. Enable it only after provider credentials or endpoint settings are present in a Kubernetes Secret or external secret manager.

OpenAI-compatible gateway:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set platform.config.llmEnabled=true \
  --set platform.config.llmProvider=openai_compatible \
  --set platform.config.springAiChatModel=openai-sdk \
  --set-string platform.config.llmModel=provider-model-name \
  --set-string platform.secret.openaiApiKey='<secret>' \
  --set-string platform.secret.openaiBaseUrl='https://llm-gateway.example.com/v1'
```

Self-hosted OpenAI-compatible endpoint without a platform-managed API key:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set platform.config.llmEnabled=true \
  --set platform.config.llmProvider=self_hosted \
  --set platform.config.springAiChatModel=openai-sdk \
  --set-string platform.config.llmModel=local-rca-model \
  --set-string platform.secret.openaiBaseUrl='http://llm-gateway.rca-system.svc:8000/v1'
```

External Secrets Operator 사용 시 `platform.externalSecret.data`가 아래 target key를 생성해야 합니다.

```text
SPRING_AI_OPENAI_SDK_API_KEY
SPRING_AI_OPENAI_SDK_BASE_URL
SPRING_AI_ANTHROPIC_API_KEY
SPRING_AI_GOOGLE_GENAI_API_KEY
SPRING_AI_OLLAMA_BASE_URL
```

Settings 화면의 LLM diagnostics는 이 Secret이 Pod 환경 변수로 들어왔는지 확인합니다. API key 값은 표시하지 않습니다.

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
