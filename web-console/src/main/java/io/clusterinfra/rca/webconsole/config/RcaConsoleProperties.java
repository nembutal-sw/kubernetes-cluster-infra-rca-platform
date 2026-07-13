package io.clusterinfra.rca.webconsole.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rca")
public class RcaConsoleProperties {
    private String publicApiBaseUrl = "";
    private String defaultAdminUsername = "";
    private String defaultAdminPassword = "";
    private String webhookToken = "";
    private int sessionTtlHours = 12;
    private int agentOfflineAfterSeconds = 180;
    private final Agent agent = new Agent();
    private final Llm llm = new Llm();
    private final Incident incident = new Incident();
    private final Topology topology = new Topology();
    private final Audit audit = new Audit();
    private final Maintenance maintenance = new Maintenance();
    private final Security security = new Security();
    private final Demo demo = new Demo();
    private final Export export = new Export();
    private final Notification notification = new Notification();
    private final Pipeline pipeline = new Pipeline();
    private final Thresholds thresholds = new Thresholds();
    private final Monitoring monitoring = new Monitoring();
    private final Observability observability = new Observability();
    private final Catalog catalog = new Catalog();
    private final GitOps gitOps = new GitOps();

    public String getPublicApiBaseUrl() {
        return publicApiBaseUrl == null ? "" : publicApiBaseUrl.trim();
    }

    public void setPublicApiBaseUrl(String publicApiBaseUrl) {
        this.publicApiBaseUrl = publicApiBaseUrl;
    }

    public String getDefaultAdminUsername() {
        return defaultAdminUsername;
    }

    public void setDefaultAdminUsername(String defaultAdminUsername) {
        this.defaultAdminUsername = defaultAdminUsername;
    }

    public String getDefaultAdminPassword() {
        return defaultAdminPassword;
    }

    public void setDefaultAdminPassword(String defaultAdminPassword) {
        this.defaultAdminPassword = defaultAdminPassword;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public int getSessionTtlHours() {
        return sessionTtlHours;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public int getAgentOfflineAfterSeconds() {
        return agentOfflineAfterSeconds;
    }

    public void setAgentOfflineAfterSeconds(int agentOfflineAfterSeconds) {
        this.agentOfflineAfterSeconds = agentOfflineAfterSeconds;
    }

    public Agent getAgent() {
        return agent;
    }

    public Llm getLlm() {
        return llm;
    }

    public Incident getIncident() {
        return incident;
    }

    public Topology getTopology() {
        return topology;
    }

    public Audit getAudit() {
        return audit;
    }

    public Maintenance getMaintenance() {
        return maintenance;
    }

    public Security getSecurity() {
        return security;
    }

    public Demo getDemo() {
        return demo;
    }

    public Export getExport() {
        return export;
    }

    public Notification getNotification() {
        return notification;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public Monitoring getMonitoring() {
        return monitoring;
    }

    public Observability getObservability() {
        return observability;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public GitOps getGitOps() {
        return gitOps;
    }

    public static class Agent {
        private String image = "ghcr.io/example/cluster-infra-rca-agent:latest";
        private String namespace = "rca-system";
        private String expectedVersion = "";
        private String minimumSupportedVersion = "0.1.0";
        private String protocolVersion = "1";
        private String minimumSupportedProtocolVersion = "1";
        private String platformVersion = "0.1.0";
        private int pollIntervalSeconds = 30;
        private int httpTimeoutSeconds = 20;
        private int commandTimeoutSeconds = 10;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getExpectedVersion() {
            return expectedVersion == null ? "" : expectedVersion.trim();
        }

        public void setExpectedVersion(String expectedVersion) {
            this.expectedVersion = expectedVersion;
        }

        public String getMinimumSupportedVersion() {
            return minimumSupportedVersion == null ? "" : minimumSupportedVersion.trim();
        }

        public void setMinimumSupportedVersion(String minimumSupportedVersion) {
            this.minimumSupportedVersion = minimumSupportedVersion;
        }

        public String getProtocolVersion() {
            return protocolVersion == null ? "1" : protocolVersion.trim();
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public String getMinimumSupportedProtocolVersion() {
            return minimumSupportedProtocolVersion == null
                ? "1"
                : minimumSupportedProtocolVersion.trim();
        }

        public void setMinimumSupportedProtocolVersion(String minimumSupportedProtocolVersion) {
            this.minimumSupportedProtocolVersion = minimumSupportedProtocolVersion;
        }

        public String getPlatformVersion() {
            return platformVersion == null ? "0.1.0" : platformVersion.trim();
        }

        public void setPlatformVersion(String platformVersion) {
            this.platformVersion = platformVersion;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        public void setPollIntervalSeconds(int pollIntervalSeconds) {
            this.pollIntervalSeconds = pollIntervalSeconds;
        }

        public int getHttpTimeoutSeconds() {
            return httpTimeoutSeconds;
        }

        public void setHttpTimeoutSeconds(int httpTimeoutSeconds) {
            this.httpTimeoutSeconds = httpTimeoutSeconds;
        }

        public int getCommandTimeoutSeconds() {
            return commandTimeoutSeconds;
        }

        public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
            this.commandTimeoutSeconds = commandTimeoutSeconds;
        }
    }

    public static class Observability {
        private boolean enabled = true;
        private String metricsToken = "";
        private int refreshIntervalMs = 15000;
        private int initialDelayMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMetricsToken() {
            return metricsToken == null ? "" : metricsToken.trim();
        }

        public void setMetricsToken(String metricsToken) {
            this.metricsToken = metricsToken;
        }

        public int getRefreshIntervalMs() {
            return refreshIntervalMs;
        }

        public void setRefreshIntervalMs(int refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
        }

        public int getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(int initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }
    }

    public static class Catalog {
        private String classpathLocation = "classpath:catalog/operational-catalog.json";
        private String externalPath = "";

        public String getClasspathLocation() {
            return classpathLocation == null || classpathLocation.isBlank()
                ? "classpath:catalog/operational-catalog.json"
                : classpathLocation.trim();
        }

        public void setClasspathLocation(String classpathLocation) {
            this.classpathLocation = classpathLocation;
        }

        public String getExternalPath() {
            return externalPath == null ? "" : externalPath.trim();
        }

        public void setExternalPath(String externalPath) {
            this.externalPath = externalPath;
        }
    }

    public static class GitOps {
        private boolean enabled;
        private String provider = "github";
        private String apiBaseUrl = "";
        private String repository = "";
        private String baseBranch = "main";
        private String filePath = "ops/catalog/operational-catalog.override.json";
        private String token = "";
        private String webhookSecret = "";
        private int timeoutSeconds = 15;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return clean(provider, "github");
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiBaseUrl() {
            String defaultUrl = switch (getProvider().toLowerCase(java.util.Locale.ROOT)) {
                case "gitlab" -> "https://gitlab.com/api/v4";
                case "gitea" -> "";
                default -> "https://api.github.com";
            };
            return clean(apiBaseUrl, defaultUrl).replaceAll("/+$", "");
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getRepository() {
            return clean(repository, "");
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public String getBaseBranch() {
            return clean(baseBranch, "main");
        }

        public void setBaseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
        }

        public String getFilePath() {
            return clean(filePath, "ops/catalog/operational-catalog.override.json");
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getToken() {
            return clean(token, "");
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getWebhookSecret() {
            return clean(webhookSecret, "");
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public int getTimeoutSeconds() {
            return Math.max(3, Math.min(timeoutSeconds, 60));
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        private String clean(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public static class Llm {
        private boolean enabled;
        private String provider = "none";
        private String model = "";
        private int maxOutputTokens = 1800;
        private int timeoutSeconds = 30;
        private int maxAttempts = 2;
        private int failureThreshold = 3;
        private int cooldownSeconds = 60;
        private double inputCostPerMillionTokens;
        private double outputCostPerMillionTokens;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }

        public double getInputCostPerMillionTokens() {
            return inputCostPerMillionTokens;
        }

        public void setInputCostPerMillionTokens(double inputCostPerMillionTokens) {
            this.inputCostPerMillionTokens = inputCostPerMillionTokens;
        }

        public double getOutputCostPerMillionTokens() {
            return outputCostPerMillionTokens;
        }

        public void setOutputCostPerMillionTokens(double outputCostPerMillionTokens) {
            this.outputCostPerMillionTokens = outputCostPerMillionTokens;
        }
    }

    public static class Incident {
        private int correlationWindowMinutes = 15;
        private int minimumScore = 70;
        private int candidateLimit = 20;
        private boolean autoResolveEnabled = true;
        private int inactivityMinutes = 60;
        private long lifecycleScanIntervalMs = 60_000;
        private long lifecycleInitialDelayMs = 30_000;
        private int lifecycleBatchSize = 100;
        private int recurrenceLookbackHours = 168;

        public int getCorrelationWindowMinutes() {
            return correlationWindowMinutes;
        }

        public void setCorrelationWindowMinutes(int correlationWindowMinutes) {
            this.correlationWindowMinutes = correlationWindowMinutes;
        }

        public int getMinimumScore() {
            return minimumScore;
        }

        public void setMinimumScore(int minimumScore) {
            this.minimumScore = minimumScore;
        }

        public int getCandidateLimit() {
            return candidateLimit;
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }

        public boolean isAutoResolveEnabled() {
            return autoResolveEnabled;
        }

        public void setAutoResolveEnabled(boolean autoResolveEnabled) {
            this.autoResolveEnabled = autoResolveEnabled;
        }

        public int getInactivityMinutes() {
            return inactivityMinutes;
        }

        public void setInactivityMinutes(int inactivityMinutes) {
            this.inactivityMinutes = inactivityMinutes;
        }

        public long getLifecycleScanIntervalMs() {
            return lifecycleScanIntervalMs;
        }

        public void setLifecycleScanIntervalMs(long lifecycleScanIntervalMs) {
            this.lifecycleScanIntervalMs = lifecycleScanIntervalMs;
        }

        public long getLifecycleInitialDelayMs() {
            return lifecycleInitialDelayMs;
        }

        public void setLifecycleInitialDelayMs(long lifecycleInitialDelayMs) {
            this.lifecycleInitialDelayMs = lifecycleInitialDelayMs;
        }

        public int getLifecycleBatchSize() {
            return lifecycleBatchSize;
        }

        public void setLifecycleBatchSize(int lifecycleBatchSize) {
            this.lifecycleBatchSize = lifecycleBatchSize;
        }

        public int getRecurrenceLookbackHours() {
            return recurrenceLookbackHours;
        }

        public void setRecurrenceLookbackHours(int recurrenceLookbackHours) {
            this.recurrenceLookbackHours = recurrenceLookbackHours;
        }
    }

    public static class Topology {
        private boolean enabled = true;
        private int lookbackHours = 24;
        private int observationLimit = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLookbackHours() {
            return lookbackHours;
        }

        public void setLookbackHours(int lookbackHours) {
            this.lookbackHours = lookbackHours;
        }

        public int getObservationLimit() {
            return observationLimit;
        }

        public void setObservationLimit(int observationLimit) {
            this.observationLimit = observationLimit;
        }
    }

    public static class Audit {
        private boolean enabled = true;
        private int retentionDays = 180;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    public static class Maintenance {
        private boolean enabled = true;
        private String cron = "0 17 3 * * *";
        private int batchSize = 250;
        private int evidenceRetentionDays = 30;
        private int evidenceRequestRetentionDays = 30;
        private int analysisTaskRetentionDays = 30;
        private int realtimeEventRetentionDays = 14;
        private int topologyObservationRetentionDays = 30;
        private int reportRetentionDays = 365;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getEvidenceRetentionDays() {
            return evidenceRetentionDays;
        }

        public void setEvidenceRetentionDays(int evidenceRetentionDays) {
            this.evidenceRetentionDays = evidenceRetentionDays;
        }

        public int getEvidenceRequestRetentionDays() {
            return evidenceRequestRetentionDays;
        }

        public void setEvidenceRequestRetentionDays(int evidenceRequestRetentionDays) {
            this.evidenceRequestRetentionDays = evidenceRequestRetentionDays;
        }

        public int getAnalysisTaskRetentionDays() {
            return analysisTaskRetentionDays;
        }

        public void setAnalysisTaskRetentionDays(int analysisTaskRetentionDays) {
            this.analysisTaskRetentionDays = analysisTaskRetentionDays;
        }

        public int getRealtimeEventRetentionDays() {
            return realtimeEventRetentionDays;
        }

        public void setRealtimeEventRetentionDays(int realtimeEventRetentionDays) {
            this.realtimeEventRetentionDays = realtimeEventRetentionDays;
        }

        public int getTopologyObservationRetentionDays() {
            return topologyObservationRetentionDays;
        }

        public void setTopologyObservationRetentionDays(int topologyObservationRetentionDays) {
            this.topologyObservationRetentionDays = topologyObservationRetentionDays;
        }

        public int getReportRetentionDays() {
            return reportRetentionDays;
        }

        public void setReportRetentionDays(int reportRetentionDays) {
            this.reportRetentionDays = reportRetentionDays;
        }
    }

    public static class Security {
        private String encryptionSecret = "";
        private long standardRequestMaxBytes = 1024 * 1024;
        private long evidenceRequestMaxBytes = 10 * 1024 * 1024;
        private int manifestTokenTtlSeconds = 300;
        private boolean agentMtlsRequired;

        public String getEncryptionSecret() {
            return encryptionSecret;
        }

        public void setEncryptionSecret(String encryptionSecret) {
            this.encryptionSecret = encryptionSecret;
        }

        public long getStandardRequestMaxBytes() {
            return standardRequestMaxBytes;
        }

        public void setStandardRequestMaxBytes(long standardRequestMaxBytes) {
            this.standardRequestMaxBytes = standardRequestMaxBytes;
        }

        public long getEvidenceRequestMaxBytes() {
            return evidenceRequestMaxBytes;
        }

        public void setEvidenceRequestMaxBytes(long evidenceRequestMaxBytes) {
            this.evidenceRequestMaxBytes = evidenceRequestMaxBytes;
        }

        public int getManifestTokenTtlSeconds() {
            return manifestTokenTtlSeconds;
        }

        public void setManifestTokenTtlSeconds(int manifestTokenTtlSeconds) {
            this.manifestTokenTtlSeconds = manifestTokenTtlSeconds;
        }

        public boolean isAgentMtlsRequired() {
            return agentMtlsRequired;
        }

        public void setAgentMtlsRequired(boolean agentMtlsRequired) {
            this.agentMtlsRequired = agentMtlsRequired;
        }
    }

    public static class Demo {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Export {
        private long maxBundleBytes = 10 * 1024 * 1024;
        private String signatureSecret = "";
        private String signatureKeyId = "default";

        public long getMaxBundleBytes() {
            return maxBundleBytes;
        }

        public void setMaxBundleBytes(long maxBundleBytes) {
            this.maxBundleBytes = maxBundleBytes;
        }

        public String getSignatureSecret() {
            return signatureSecret == null ? "" : signatureSecret.trim();
        }

        public void setSignatureSecret(String signatureSecret) {
            this.signatureSecret = signatureSecret;
        }

        public String getSignatureKeyId() {
            String value = signatureKeyId == null ? "" : signatureKeyId.trim();
            return value.isEmpty() ? "default" : value;
        }

        public void setSignatureKeyId(String signatureKeyId) {
            this.signatureKeyId = signatureKeyId;
        }
    }

    public static class Notification {
        private boolean enabled;
        private String slackWebhookUrl = "";
        private String webhookUrl = "";
        private String webhookToken = "";
        private String minimumSeverity = "critical";
        private int maxAttempts = 2;
        private int timeoutSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSlackWebhookUrl() {
            return slackWebhookUrl == null ? "" : slackWebhookUrl.trim();
        }

        public void setSlackWebhookUrl(String slackWebhookUrl) {
            this.slackWebhookUrl = slackWebhookUrl;
        }

        public String getWebhookUrl() {
            return webhookUrl == null ? "" : webhookUrl.trim();
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getWebhookToken() {
            return webhookToken == null ? "" : webhookToken.trim();
        }

        public void setWebhookToken(String webhookToken) {
            this.webhookToken = webhookToken;
        }

        public String getMinimumSeverity() {
            return minimumSeverity == null ? "critical" : minimumSeverity.trim();
        }

        public void setMinimumSeverity(String minimumSeverity) {
            this.minimumSeverity = minimumSeverity;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Pipeline {
        private boolean enabled = true;
        private int batchSize = 4;
        private long pollIntervalMs = 2000;
        private long initialDelayMs = 3000;
        private int leaseSeconds = 120;
        private int maxAttempts = 5;
        private int retryBaseSeconds = 5;
        private int retryMaxSeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getRetryBaseSeconds() {
            return retryBaseSeconds;
        }

        public void setRetryBaseSeconds(int retryBaseSeconds) {
            this.retryBaseSeconds = retryBaseSeconds;
        }

        public int getRetryMaxSeconds() {
            return retryMaxSeconds;
        }

        public void setRetryMaxSeconds(int retryMaxSeconds) {
            this.retryMaxSeconds = retryMaxSeconds;
        }
    }

    public static class Thresholds {
        private double diskWarningPercent = 85;
        private double diskCriticalPercent = 90;
        private double inodeWarningPercent = 85;
        private double inodeCriticalPercent = 90;
        private double memoryCriticalPercent = 90;
        private double pidWarningPercent = 85;
        private double pidCriticalPercent = 95;
        private double conntrackWarningPercent = 80;
        private double conntrackCriticalPercent = 95;
        private double diskAwaitWarningMs = 20;
        private double dnsLatencyWarningMs = 500;
        private double apiServerLatencyWarningMs = 1000;
        private double etcdLatencyWarningMs = 500;
        private final Map<String, Double> overrides = new LinkedHashMap<>();

        public double getDiskWarningPercent() {
            return percent("disk.warning.percent", diskWarningPercent, 85);
        }

        public void setDiskWarningPercent(double value) {
            diskWarningPercent = value;
        }

        public double getDiskCriticalPercent() {
            return orderedPercent("disk.critical.percent", diskCriticalPercent, getDiskWarningPercent(), 90);
        }

        public void setDiskCriticalPercent(double value) {
            diskCriticalPercent = value;
        }

        public double getInodeWarningPercent() {
            return percent("inode.warning.percent", inodeWarningPercent, 85);
        }

        public void setInodeWarningPercent(double value) {
            inodeWarningPercent = value;
        }

        public double getInodeCriticalPercent() {
            return orderedPercent("inode.critical.percent", inodeCriticalPercent, getInodeWarningPercent(), 90);
        }

        public void setInodeCriticalPercent(double value) {
            inodeCriticalPercent = value;
        }

        public double getMemoryCriticalPercent() {
            return percent("memory.critical.percent", memoryCriticalPercent, 90);
        }

        public void setMemoryCriticalPercent(double value) {
            memoryCriticalPercent = value;
        }

        public double getPidWarningPercent() {
            return percent("pid.warning.percent", pidWarningPercent, 85);
        }

        public void setPidWarningPercent(double value) {
            pidWarningPercent = value;
        }

        public double getPidCriticalPercent() {
            return orderedPercent("pid.critical.percent", pidCriticalPercent, getPidWarningPercent(), 95);
        }

        public void setPidCriticalPercent(double value) {
            pidCriticalPercent = value;
        }

        public double getConntrackWarningPercent() {
            return percent("conntrack.warning.percent", conntrackWarningPercent, 80);
        }

        public void setConntrackWarningPercent(double value) {
            conntrackWarningPercent = value;
        }

        public double getConntrackCriticalPercent() {
            return orderedPercent("conntrack.critical.percent", conntrackCriticalPercent, getConntrackWarningPercent(), 95);
        }

        public void setConntrackCriticalPercent(double value) {
            conntrackCriticalPercent = value;
        }

        public double getDiskAwaitWarningMs() {
            return positive("disk.await.warning.ms", diskAwaitWarningMs, 20);
        }

        public void setDiskAwaitWarningMs(double value) {
            diskAwaitWarningMs = value;
        }

        public double getDnsLatencyWarningMs() {
            return positive("dns.latency.warning.ms", dnsLatencyWarningMs, 500);
        }

        public void setDnsLatencyWarningMs(double value) {
            dnsLatencyWarningMs = value;
        }

        public double getApiServerLatencyWarningMs() {
            return positive("api-server.latency.warning.ms", apiServerLatencyWarningMs, 1000);
        }

        public void setApiServerLatencyWarningMs(double value) {
            apiServerLatencyWarningMs = value;
        }

        public double getEtcdLatencyWarningMs() {
            return positive("etcd.latency.warning.ms", etcdLatencyWarningMs, 500);
        }

        public void setEtcdLatencyWarningMs(double value) {
            etcdLatencyWarningMs = value;
        }

        public Map<String, Double> getOverrides() {
            return overrides;
        }

        public void setOverrides(Map<String, Double> values) {
            overrides.clear();
            if (values == null) {
                return;
            }
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    overrides.put(normalizeKey(key), value);
                }
            });
        }

        public Map<String, Double> activeValues() {
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("disk.warning.percent", getDiskWarningPercent());
            values.put("disk.critical.percent", getDiskCriticalPercent());
            values.put("inode.warning.percent", getInodeWarningPercent());
            values.put("inode.critical.percent", getInodeCriticalPercent());
            values.put("memory.critical.percent", getMemoryCriticalPercent());
            values.put("pid.warning.percent", getPidWarningPercent());
            values.put("pid.critical.percent", getPidCriticalPercent());
            values.put("conntrack.warning.percent", getConntrackWarningPercent());
            values.put("conntrack.critical.percent", getConntrackCriticalPercent());
            values.put("disk.await.warning.ms", getDiskAwaitWarningMs());
            values.put("dns.latency.warning.ms", getDnsLatencyWarningMs());
            values.put("api-server.latency.warning.ms", getApiServerLatencyWarningMs());
            values.put("etcd.latency.warning.ms", getEtcdLatencyWarningMs());
            return values;
        }

        private double percent(String key, double configured, double fallback) {
            double value = raw(key, configured);
            if (!Double.isFinite(value) || value <= 0 || value > 100) {
                return fallback;
            }
            return value;
        }

        private double orderedPercent(String key, double configured, double minimum, double fallback) {
            double value = percent(key, configured, fallback);
            if (value < minimum) {
                return Math.max(minimum, fallback);
            }
            return value;
        }

        private double positive(String key, double configured, double fallback) {
            double value = raw(key, configured);
            if (!Double.isFinite(value) || value <= 0) {
                return fallback;
            }
            return value;
        }

        private double raw(String key, double configured) {
            return overrides.getOrDefault(normalizeKey(key), configured);
        }

        private String normalizeKey(String key) {
            return key.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '.')
                .replace('-', '.');
        }
    }

    public static class Monitoring {
        private boolean enabled;
        private long intervalMs = 60_000;
        private long initialDelayMs = 30_000;
        private boolean collectHealthyAgents = true;
        private int healthyIntervalMinutes = 15;
        private int degradedIntervalMinutes = 5;
        private int staleIntervalMinutes = 2;
        private int versionMismatchIntervalMinutes = 60;
        private int unauthorizedIntervalMinutes = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public boolean isCollectHealthyAgents() {
            return collectHealthyAgents;
        }

        public void setCollectHealthyAgents(boolean collectHealthyAgents) {
            this.collectHealthyAgents = collectHealthyAgents;
        }

        public int getHealthyIntervalMinutes() {
            return healthyIntervalMinutes;
        }

        public void setHealthyIntervalMinutes(int healthyIntervalMinutes) {
            this.healthyIntervalMinutes = healthyIntervalMinutes;
        }

        public int getDegradedIntervalMinutes() {
            return degradedIntervalMinutes;
        }

        public void setDegradedIntervalMinutes(int degradedIntervalMinutes) {
            this.degradedIntervalMinutes = degradedIntervalMinutes;
        }

        public int getStaleIntervalMinutes() {
            return staleIntervalMinutes;
        }

        public void setStaleIntervalMinutes(int staleIntervalMinutes) {
            this.staleIntervalMinutes = staleIntervalMinutes;
        }

        public int getVersionMismatchIntervalMinutes() {
            return versionMismatchIntervalMinutes;
        }

        public void setVersionMismatchIntervalMinutes(int versionMismatchIntervalMinutes) {
            this.versionMismatchIntervalMinutes = versionMismatchIntervalMinutes;
        }

        public int getUnauthorizedIntervalMinutes() {
            return unauthorizedIntervalMinutes;
        }

        public void setUnauthorizedIntervalMinutes(int unauthorizedIntervalMinutes) {
            this.unauthorizedIntervalMinutes = unauthorizedIntervalMinutes;
        }
    }
}
