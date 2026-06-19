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
    private final Thresholds thresholds = new Thresholds();
    private final Monitoring monitoring = new Monitoring();

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

    public Thresholds getThresholds() {
        return thresholds;
    }

    public Monitoring getMonitoring() {
        return monitoring;
    }

    public static class Agent {
        private String image = "ghcr.io/example/cluster-infra-rca-agent:latest";
        private String namespace = "rca-system";
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

    public static class Llm {
        private boolean enabled;
        private String provider = "none";
        private String model = "";
        private int maxOutputTokens = 1800;

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
