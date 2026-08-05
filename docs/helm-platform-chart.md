# Platform Helm Chart

## 한국어 요약

`charts/cluster-infra-rca-platform` 차트는 Spring Boot RCA Platform을 Kubernetes에 배포하기 위한 Helm chart입니다.

현재 차트는 Platform Deployment, Service, optional Ingress, optional PostgreSQL/MariaDB, Secret, Config, 그리고 optional ServiceMonitor를 생성합니다. Node Agent가 직접 운영 환경을 변경하는 기능은 제공하지 않으며, 운영 조치는 manual approval workflow로 처리합니다.

개발은 `values-dev.yaml`, 운영은 `values-production.yaml` overlay를 사용합니다. 운영 profile은 외부 DB/Secret, digest image, 2개 이상 replica, NetworkPolicy, topology spread, read-only root filesystem이 없으면 Helm render 단계에서 실패합니다.

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
- Optional PrometheusRule for LLM latency, error, usage, circuit breaker, and cost SLOs

## Important Values

```yaml
platform:
  agentEnrollmentPreflight:
    enabled: true
    targetAudience: cluster-infra-rca-agent-enrollment
    backoffLimit: 0
  kubernetesReviewer:
    enabled: false
    rbacCreate: true
    audience: https://kubernetes.default.svc.cluster.local
    tokenExpirationSeconds: 3600
    tokenMountPath: /var/run/secrets/kubernetes.io/serviceaccount
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
    opaqueTokenKeyId: legacy
    opaqueTokenWriteVersion: v1
    opaqueTokenRehashOnAuthentication: false
    opaqueTokenKeyRingRevision: ""
    agentOfflineAfterSeconds: 180
    agentExpectedVersion: ""
    agentMinimumSupportedVersion: "0.1.0"
    agentProtocolVersion: "2"
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
    llmInputCostPerMillionTokens: 0
    llmOutputCostPerMillionTokens: 0
    gitopsEnabled: false
    gitopsProvider: github
    # Empty selects the GitHub or GitLab default. Gitea requires /api/v1.
    gitopsApiBaseUrl: ""
    gitopsRepository: ""
    gitopsBaseBranch: main
    gitopsFilePath: ops/catalog/operational-catalog.override.json
  secret:
    defaultAdminUsername: ""
    defaultAdminPassword: ""
    webhookToken: dev-webhook-token
    metricsToken: ""
    encryptionSecret: ""
    opaqueTokenPepper: development-only-opaque-token-pepper
    opaqueTokenPreviousKeys: ""
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

`platform.kubernetesReviewer.enabled=true`는 Platform과 같은 cluster의 Agent TokenReview를 검증할
때만 사용한다. 전용 projected token과 `tokenreviews.create`, `pods.get` RBAC를 Platform
ServiceAccount에 추가한다. Agent ServiceAccount에는 이 권한을 부여하지 않는다. 외부 cluster는
별도 reviewer credential을 `/var/run/secrets/cluster-infra-rca-reviewers/` 하위에 mount한다.

기본 reviewer audience는 Kubernetes 기본 issuer와 맞춘
`https://kubernetes.default.svc.cluster.local`이다. Platform container는 UID/GID `65532`로 실행되며,
기본 `platform.podSecurityContext.fsGroup=65532`가 `0400` projected token을 group-readable `0640`으로
투영한다. `podSecurityContext`를 운영 overlay에서 교체할 때 reviewer token을 Platform process가
읽을 수 있는지 반드시 확인한다.

외부 cluster Secret은 다음처럼 참조한다.

```yaml
platform:
  kubernetesReviewer:
    externalCredentials:
      - name: cluster-a-current
        secretName: reviewer-cluster-a-current
        secretKey: token
      - name: cluster-a-next
        secretName: reviewer-cluster-a-next
        secretKey: token
  config:
    reviewerCredentialExpiringSeconds: 300
    reviewerCredentialMaximumGraceSeconds: 86400
```

각 항목은 `/var/run/secrets/cluster-infra-rca-reviewers/<name>/token`에 `0400`, read-only로
mount된다. `name`은 40자 이하의 고유 DNS label이어야 하고 Secret 이름과 key를 반드시 지정한다.
만료 임박 구간은 60~86400초, 최대 grace는 60~604800초만 허용한다. Secret을 먼저 배포한 뒤
Web Console에서 rotation을 시작하며 raw token은 Helm values나 DB에 넣지 않는다.

Reviewer audience는 `platform.config.kubernetesApiAudiences`에도 포함되어야 하며 누락하면 Helm
렌더링이 실패한다. Backend는 이 목록과 동일한 audience를 Agent enrollment profile에 저장하지
못하게 한다.

`platform.agentEnrollmentPreflight`는 audit 전용 Helm `pre-upgrade` Job이다. 기존 DB에 Kubernetes
API audience를 사용하는 profile이 있으면 새 Platform rollout 전에 upgrade를 중단한다. Job에는
Platform Secret 전체가 아니라 DB 접속 키 세 개만 주입하며 Platform Pod와 함께
`rca.clusterinfra.io/database-client=true` label을 사용한다. Hook과 one-shot Job은 첫 rolling
upgrade의 기존 DB NetworkPolicy도 통과하도록 기존 Platform selector를 호환 label로 유지하고,
실제 역할은 `rca.clusterinfra.io/job-role`로 구분한다.

Audience 변경은 Helm values로 실행하지 않는다. `render-agent-enrollment-migration-job.py`가 생성한
one-shot Job으로 cluster별 canary를 전환하고 최종 unsafe profile 수가 0인지 확인한 뒤 upgrade한다.
cluster별 V24 이전 token 유예는 upgrade 후 Web Console에서 최대 30일로 설정한다. 전체 순서는
[Agent Enrollment Upgrade](agent-enrollment-upgrade.md)를 따른다.

opaque token pepper는 한 번에 교체하지 않습니다. 운영에서는 기존 Secret에 current/previous
key를 함께 저장하고 `opaqueTokenKeyRingRevision`을 단계마다 변경해 Pod rollout을 강제합니다.
상세 순서는 [Opaque Token Pepper Rotation](opaque-token-key-rotation.md)을 따릅니다.

`gitopsProvider`는 `github`, `gitlab`, `gitea`만 허용됩니다. GitLab subgroup을 사용할 때 `gitopsRepository`는 `group/subgroup/repository` 형식으로 지정합니다. Gitea는 `gitopsApiBaseUrl`을 반드시 지정하며, 누락하거나 지원하지 않는 provider를 입력하면 Helm template 단계에서 실패합니다.

## Production Notes

운영 배포 예시:

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --create-namespace \
  --values charts/cluster-infra-rca-platform/values-production.yaml \
  --set-string platform.image.repository=ghcr.io/<org>/cluster-infra-rca-web-console \
  --set-string platform.image.digest=sha256:<64-hex-digest>
```

`cluster-infra-rca-platform` Secret은 배포 전에 생성해야 합니다. 외부 DB 접속 정보와 초기 관리자, webhook, encryption 값을 source control 밖에서 관리합니다. `platform.image.digest`는 `sha256:` 뒤에 64자리 소문자 16진수가 와야 합니다.

운영 기준:

- enable the `prod` or `production` Spring profile
- use an absolute HTTPS public API URL
- disable demo mode
- keep audit enabled
- configure strong secret values outside source control
- configure a metrics token when observability is enabled
- review retention periods before connecting production data
- validate rendered manifests before applying

애플리케이션의 production fail-fast와 Helm render-time 검증을 함께 적용합니다. 조건이 약한 배포는 Pod 기동 전 단계에서 차단합니다.

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

## LLM PrometheusRule

`platform.prometheusRule.enabled=true`이면 LLM recording rule과 alert를 생성합니다. Prometheus Operator CRD가 없는 클러스터에서는 활성화하지 않습니다.

```yaml
platform:
  prometheusRule:
    enabled: true
    evaluationWindow: 10m
    latency:
      p95Seconds: 60
    errorRate:
      ratio: 0.10
      minimumRequests: 5
    usageMetadata:
      unavailableRatio: 0.10
      minimumRequests: 5
    costBudget:
      enabled: false
      lookback: 24h
      maxUsd: 25
```

비용 rule은 configured token price를 기반으로 한 추정값을 사용합니다. provider 청구 금액과 일치하는지 별도로 확인해야 합니다.

## Alertmanager Webhook Routing

기존 Prometheus Operator의 Alertmanager에서 Platform webhook으로 경보를 보내려면
다음 설정을 사용합니다.

```yaml
platform:
  prometheusRule:
    enabled: true
  alertmanagerConfig:
    enabled: true
    clusterId: production-a
    labels:
      release: kube-prometheus-stack
    sendResolved: true
```

`clusterId`는 Platform에 등록된 클러스터 ID와 같아야 합니다. 차트가 생성한
`PrometheusRule` alert에는 이 값이 자동으로 추가됩니다. `labels`는 대상
Alertmanager의 `alertmanagerConfigSelector`에 맞춥니다. Webhook token은
Platform Secret의 `RCA_WEBHOOK_TOKEN` key를 참조하며, 다른 Secret을 사용하려면
`tokenSecretName`과 `tokenSecretKey`를 지정합니다.

`platform.alertmanagerConfig.enabled=true`인데 자체 생성 Secret을 사용하면서
`platform.secret.webhookToken`이 비어 있거나 `clusterId`가 비어 있으면 Helm
렌더가 실패합니다. 이는 인증되지 않은 전달과 `cluster_id` 누락으로 인한 ingest
skip을 배포 전에 차단하기 위한 동작입니다.

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
python3 scripts/alertmanager_delivery_test.py \
  --helm helm \
  --prometheus prometheus \
  --alertmanager alertmanager
```
