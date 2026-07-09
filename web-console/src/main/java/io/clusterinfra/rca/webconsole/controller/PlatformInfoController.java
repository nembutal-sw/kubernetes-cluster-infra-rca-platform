package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ExportSecurityInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationConfigurationInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PlatformInfo;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.service.ClusterThresholdService;
import io.clusterinfra.rca.webconsole.service.LlmConfigurationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformInfoController {
    private final RcaConsoleProperties properties;
    private final LlmConfigurationService llmConfiguration;
    private final OperationalCatalogService catalogService;
    private final ClusterThresholdService thresholdService;

    public PlatformInfoController(
        RcaConsoleProperties properties,
        LlmConfigurationService llmConfiguration,
        OperationalCatalogService catalogService,
        ClusterThresholdService thresholdService
    ) {
        this.properties = properties;
        this.llmConfiguration = llmConfiguration;
        this.catalogService = catalogService;
        this.thresholdService = thresholdService;
    }

    @GetMapping({"/api/platform/info", "/api/v1/platform/info"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public PlatformInfo info() {
        RcaConsoleProperties.Agent agent = properties.getAgent();
        RcaConsoleProperties.Export export = properties.getExport();
        boolean bundleSignatureEnabled = !export.getSignatureSecret().isBlank();
        return new PlatformInfo(
            agent.getPlatformVersion(),
            "v1",
            agent.getProtocolVersion(),
            agent.getMinimumSupportedProtocolVersion(),
            agent.getMinimumSupportedVersion(),
            new ExportSecurityInfo(
                export.getMaxBundleBytes(),
                "SHA-256",
                bundleSignatureEnabled,
                bundleSignatureEnabled ? "HMAC-SHA256" : "none",
                bundleSignatureEnabled ? export.getSignatureKeyId() : "",
                "scripts/verify_evidence_bundle.py"
            ),
            llmConfiguration.info(),
            notificationInfo(),
            catalogService.info(),
            thresholdService.info(),
            operationsInfo()
        );
    }

    private Map<String, Object> operationsInfo() {
        RcaConsoleProperties.Monitoring monitoring = properties.getMonitoring();
        RcaConsoleProperties.Pipeline pipeline = properties.getPipeline();
        RcaConsoleProperties.Observability observability = properties.getObservability();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduled_monitoring_enabled", monitoring.isEnabled());
        result.put("scheduled_monitoring_interval_ms", monitoring.getIntervalMs());
        result.put("scheduled_monitoring_initial_delay_ms", monitoring.getInitialDelayMs());
        result.put("collect_healthy_agents", monitoring.isCollectHealthyAgents());
        result.put("healthy_interval_minutes", monitoring.getHealthyIntervalMinutes());
        result.put("degraded_interval_minutes", monitoring.getDegradedIntervalMinutes());
        result.put("stale_interval_minutes", monitoring.getStaleIntervalMinutes());
        result.put("version_mismatch_interval_minutes", monitoring.getVersionMismatchIntervalMinutes());
        result.put("unauthorized_interval_minutes", monitoring.getUnauthorizedIntervalMinutes());
        result.put("analysis_pipeline_enabled", pipeline.isEnabled());
        result.put("analysis_pipeline_batch_size", pipeline.getBatchSize());
        result.put("analysis_pipeline_poll_interval_ms", pipeline.getPollIntervalMs());
        result.put("analysis_pipeline_max_attempts", pipeline.getMaxAttempts());
        result.put("observability_enabled", observability.isEnabled());
        result.put("observability_refresh_interval_ms", observability.getRefreshIntervalMs());
        result.put("metrics_token_configured", !observability.getMetricsToken().isBlank());
        return result;
    }

    private NotificationConfigurationInfo notificationInfo() {
        RcaConsoleProperties.Notification notification = properties.getNotification();
        List<String> channels = new ArrayList<>();
        if (!notification.getSlackWebhookUrl().isBlank()) {
            channels.add("slack");
        }
        if (!notification.getWebhookUrl().isBlank()) {
            channels.add("webhook");
        }
        return new NotificationConfigurationInfo(
            notification.isEnabled(),
            !notification.getSlackWebhookUrl().isBlank(),
            !notification.getWebhookUrl().isBlank(),
            !notification.getWebhookToken().isBlank(),
            notification.getMinimumSeverity(),
            notification.getMaxAttempts(),
            notification.getTimeoutSeconds(),
            List.copyOf(channels)
        );
    }

}
