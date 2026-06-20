package io.clusterinfra.rca.webconsole.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rca")
public class RcaConsoleProperties {
    private String publicApiBaseUrl = "";
    private String defaultAdminUsername = "admin";
    private String defaultAdminPassword = "admin";
    private String webhookToken = "";
    private int sessionTtlHours = 12;
    private int agentOfflineAfterSeconds = 180;
    private final Agent agent = new Agent();
    private final Llm llm = new Llm();
    private final Incident incident = new Incident();
    private final Audit audit = new Audit();
    private final Security security = new Security();
    private final Demo demo = new Demo();
    private final Export export = new Export();
    private final Notification notification = new Notification();
    private final Pipeline pipeline = new Pipeline();
    private final Thresholds thresholds = new Thresholds();
    private final Monitoring monitoring = new Monitoring();
    private final Observability observability = new Observability();

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

    public Audit getAudit() {
        return audit;
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

    public static class Llm {
        private boolean enabled;
        private String provider = "none";
        private String model = "";
        private int maxOutputTokens = 1800;
        private int timeoutSeconds = 30;
        private int maxAttempts = 2;
        private int failureThreshold = 3;
        private int cooldownSeconds = 60;

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
    }

    public static class Incident {
        private int correlationWindowMinutes = 15;

        public int getCorrelationWindowMinutes() {
            return correlationWindowMinutes;
        }

        public void setCorrelationWindowMinutes(int correlationWindowMinutes) {
            this.correlationWindowMinutes = correlationWindowMinutes;
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

    public static class Security {
        private String encryptionSecret = "";

        public String getEncryptionSecret() {
            return encryptionSecret;
        }

        public void setEncryptionSecret(String encryptionSecret) {
            this.encryptionSecret = encryptionSecret;
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

        public long getMaxBundleBytes() {
            return maxBundleBytes;
        }

        public void setMaxBundleBytes(long maxBundleBytes) {
            this.maxBundleBytes = maxBundleBytes;
        }
    }

    public static class Notification {
        private boolean enabled;
        private String slackWebhookUrl = "";
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
        private double conntrackWarningPercent = 80;
        private double conntrackCriticalPercent = 95;
        private double diskAwaitWarningMs = 20;
        private double dnsLatencyWarningMs = 500;
        private double apiServerLatencyWarningMs = 1000;
        private double etcdLatencyWarningMs = 500;

        public double getDiskWarningPercent() {
            return diskWarningPercent;
        }

        public void setDiskWarningPercent(double value) {
            diskWarningPercent = value;
        }

        public double getDiskCriticalPercent() {
            return diskCriticalPercent;
        }

        public void setDiskCriticalPercent(double value) {
            diskCriticalPercent = value;
        }

        public double getInodeWarningPercent() {
            return inodeWarningPercent;
        }

        public void setInodeWarningPercent(double value) {
            inodeWarningPercent = value;
        }

        public double getInodeCriticalPercent() {
            return inodeCriticalPercent;
        }

        public void setInodeCriticalPercent(double value) {
            inodeCriticalPercent = value;
        }

        public double getMemoryCriticalPercent() {
            return memoryCriticalPercent;
        }

        public void setMemoryCriticalPercent(double value) {
            memoryCriticalPercent = value;
        }

        public double getPidWarningPercent() {
            return pidWarningPercent;
        }

        public void setPidWarningPercent(double value) {
            pidWarningPercent = value;
        }

        public double getConntrackWarningPercent() {
            return conntrackWarningPercent;
        }

        public void setConntrackWarningPercent(double value) {
            conntrackWarningPercent = value;
        }

        public double getConntrackCriticalPercent() {
            return conntrackCriticalPercent;
        }

        public void setConntrackCriticalPercent(double value) {
            conntrackCriticalPercent = value;
        }

        public double getDiskAwaitWarningMs() {
            return diskAwaitWarningMs;
        }

        public void setDiskAwaitWarningMs(double value) {
            diskAwaitWarningMs = value;
        }

        public double getDnsLatencyWarningMs() {
            return dnsLatencyWarningMs;
        }

        public void setDnsLatencyWarningMs(double value) {
            dnsLatencyWarningMs = value;
        }

        public double getApiServerLatencyWarningMs() {
            return apiServerLatencyWarningMs;
        }

        public void setApiServerLatencyWarningMs(double value) {
            apiServerLatencyWarningMs = value;
        }

        public double getEtcdLatencyWarningMs() {
            return etcdLatencyWarningMs;
        }

        public void setEtcdLatencyWarningMs(double value) {
            etcdLatencyWarningMs = value;
        }
    }

    public static class Monitoring {
        private boolean enabled;
        private long intervalMs = 60_000;
        private long initialDelayMs = 30_000;

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
    }
}
