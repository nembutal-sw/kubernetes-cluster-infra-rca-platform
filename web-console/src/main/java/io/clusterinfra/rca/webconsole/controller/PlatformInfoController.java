package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ExportSecurityInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationConfigurationInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PlatformInfo;
import io.clusterinfra.rca.webconsole.service.LlmConfigurationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformInfoController {
    private final RcaConsoleProperties properties;
    private final LlmConfigurationService llmConfiguration;

    public PlatformInfoController(RcaConsoleProperties properties, LlmConfigurationService llmConfiguration) {
        this.properties = properties;
        this.llmConfiguration = llmConfiguration;
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
            notificationInfo()
        );
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
