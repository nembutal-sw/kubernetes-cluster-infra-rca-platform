package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationConfigurationInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationDeliveryResult;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationTestRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationTestResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.AuditSearchCriteria;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.IncidentNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class NotificationController {
    private final RcaConsoleProperties properties;
    private final IncidentNotificationService notifications;
    private final AuditRepository audits;
    private final AuditService audit;
    private final AccessService access;

    public NotificationController(
        RcaConsoleProperties properties,
        IncidentNotificationService notifications,
        AuditRepository audits,
        AuditService audit,
        AccessService access
    ) {
        this.properties = properties;
        this.notifications = notifications;
        this.audits = audits;
        this.audit = audit;
        this.access = access;
    }

    @GetMapping("/api/notifications/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public NotificationConfigurationInfo status() {
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

    @GetMapping("/api/notifications/history")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public List<AuditEvent> history(
        @RequestParam(name = "limit", defaultValue = "50") Integer limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        return audits.search(new AuditSearchCriteria(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "notification.",
                null,
                null,
                safeLimit
            ))
            .stream()
            .filter(event -> event.eventType() != null && event.eventType().startsWith("notification."))
            .limit(safeLimit)
            .toList();
    }

    @PostMapping("/api/notifications/test")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public NotificationTestResponse testDelivery(
        @Valid @RequestBody NotificationTestRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "notification test confirmation is required");
        }
        NotificationTestResponse response = notifications.testDelivery();
        UserAccount user = access.currentUser(authentication);
        audit.user(
            user,
            "notification.test",
            "notification",
            "delivery",
            response.outcome(),
            testAuditDetails(response),
            servletRequest
        );
        return response;
    }

    private Map<String, Object> testAuditDetails(NotificationTestResponse response) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", response.message());
        details.put("result_count", response.results().size());
        details.put("tested_at", Instant.now().toString());
        details.put("results", response.results().stream()
            .map(this::resultDetails)
            .toList());
        return details;
    }

    private Map<String, Object> resultDetails(NotificationDeliveryResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("channel", result.channel());
        values.put("outcome", result.outcome());
        values.put("attempts", result.attempts());
        values.put("status_code", result.statusCode() == null ? "" : result.statusCode());
        values.put("error", result.error() == null ? "" : result.error());
        return values;
    }
}
