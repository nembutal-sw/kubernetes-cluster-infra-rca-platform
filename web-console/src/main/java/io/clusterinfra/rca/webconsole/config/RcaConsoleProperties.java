package io.clusterinfra.rca.webconsole.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rca")
public class RcaConsoleProperties {
    private URI apiBaseUrl = URI.create("http://127.0.0.1:8000");
    private String publicApiBaseUrl = "";
    private String proxyPath = "/console-api";
    private int proxyTimeoutSeconds = 20;

    public URI getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(URI apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getPublicApiBaseUrl() {
        if (publicApiBaseUrl == null || publicApiBaseUrl.isBlank()) {
            return apiBaseUrl.toString();
        }
        return publicApiBaseUrl;
    }

    public void setPublicApiBaseUrl(String publicApiBaseUrl) {
        this.publicApiBaseUrl = publicApiBaseUrl;
    }

    public String getProxyPath() {
        return proxyPath;
    }

    public void setProxyPath(String proxyPath) {
        this.proxyPath = proxyPath;
    }

    public int getProxyTimeoutSeconds() {
        return proxyTimeoutSeconds;
    }

    public void setProxyTimeoutSeconds(int proxyTimeoutSeconds) {
        this.proxyTimeoutSeconds = proxyTimeoutSeconds;
    }
}
