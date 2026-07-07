package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationDeliveryResult;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationTestResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IncidentNotificationService {
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final RcaMetrics metrics;

    public IncidentNotificationService(
        RcaConsoleProperties properties,
        AuditService audit,
        ObjectMapper objectMapper,
        RcaMetrics metrics
    ) {
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void notifyIncident(RcaReport report, EvidenceBundle evidence) {
        RcaConsoleProperties.Notification config = properties.getNotification();
        String severity = severity(report);
        if (!config.isEnabled() || !meetsThreshold(severity, config.getMinimumSeverity())) {
            return;
        }
        List<NotificationTarget> targets = targets(config, report, evidence, severity);
        if (targets.isEmpty()) {
            return;
        }

        for (NotificationTarget target : targets) {
            deliver(report, config, severity, target);
        }
    }

    public NotificationTestResponse testDelivery() {
        RcaConsoleProperties.Notification config = properties.getNotification();
        if (!config.isEnabled()) {
            return new NotificationTestResponse(
                "skipped",
                "Notification delivery is disabled.",
                List.of()
            );
        }
        List<NotificationTarget> targets = testTargets(config);
        if (targets.isEmpty()) {
            return new NotificationTestResponse(
                "skipped",
                "No notification delivery target is configured.",
                List.of()
            );
        }
        List<NotificationDeliveryResult> results = targets.stream()
            .map(target -> attemptDelivery(target, config))
            .toList();
        boolean allSucceeded = results.stream().allMatch(result -> "success".equals(result.outcome()));
        boolean anySucceeded = results.stream().anyMatch(result -> "success".equals(result.outcome()));
        String outcome = allSucceeded ? "success" : anySucceeded ? "partial" : "failed";
        String message = allSucceeded
            ? "Notification test delivered to all configured targets."
            : anySucceeded
                ? "Notification test delivered to some configured targets."
                : "Notification test failed for all configured targets.";
        return new NotificationTestResponse(outcome, message, results);
    }

    private void deliver(
        RcaReport report,
        RcaConsoleProperties.Notification config,
        String severity,
        NotificationTarget target
    ) {
        NotificationDeliveryResult result = attemptDelivery(target, config);
        if ("success".equals(result.outcome())) {
            metrics.notification("sent", severity);
            audit.system(
                "notification",
                "notification.sent",
                "incident",
                report.incidentId(),
                "success",
                Map.of(
                    "channel", target.channel(),
                    "severity", severity,
                    "attempt", result.attempts(),
                    "status_code", result.statusCode() == null ? "" : result.statusCode()
                )
            );
            return;
        }
        metrics.notification("failed", severity);
        audit.system(
            "notification",
            "notification.failed",
            "incident",
            report.incidentId(),
            "failed",
            Map.of(
                "channel", target.channel(),
                "severity", severity,
                "attempts", result.attempts(),
                "status_code", result.statusCode() == null ? "" : result.statusCode(),
                "error", result.error()
            )
        );
    }

    private NotificationDeliveryResult attemptDelivery(
        NotificationTarget target,
        RcaConsoleProperties.Notification config
    ) {
        int attempts = Math.max(1, Math.min(5, config.getMaxAttempts()));
        Exception lastError = null;
        Integer lastStatusCode = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                int status = send(target.url(), target.token(), target.payload(), config.getTimeoutSeconds());
                lastStatusCode = status;
                if (status >= 200 && status < 300) {
                    return new NotificationDeliveryResult(target.channel(), "success", attempt, status, "");
                }
                lastError = new IllegalStateException(target.channel() + " webhook returned HTTP " + status);
            } catch (Exception exception) {
                lastError = exception;
            }
        }
        return new NotificationDeliveryResult(
            target.channel(),
            "failed",
            attempts,
            lastStatusCode,
            safeError(lastError)
        );
    }

    private int send(
        String webhookUrl,
        String token,
        Map<String, Object> payload,
        int timeoutSeconds
    ) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(30, timeoutSeconds)));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private List<NotificationTarget> targets(
        RcaConsoleProperties.Notification config,
        RcaReport report,
        EvidenceBundle evidence,
        String severity
    ) {
        ArrayList<NotificationTarget> result = new ArrayList<>();
        if (!config.getSlackWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "slack",
                config.getSlackWebhookUrl(),
                "",
                slackPayload(report, evidence, severity)
            ));
        }
        if (!config.getWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "webhook",
                config.getWebhookUrl(),
                config.getWebhookToken(),
                webhookPayload(report, evidence, severity)
            ));
        }
        return List.copyOf(result);
    }

    private List<NotificationTarget> testTargets(RcaConsoleProperties.Notification config) {
        ArrayList<NotificationTarget> result = new ArrayList<>();
        if (!config.getSlackWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "slack",
                config.getSlackWebhookUrl(),
                "",
                testSlackPayload()
            ));
        }
        if (!config.getWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "webhook",
                config.getWebhookUrl(),
                config.getWebhookToken(),
                testWebhookPayload()
            ));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> slackPayload(RcaReport report, EvidenceBundle evidence, String severity) {
        String reportUrl = properties.getPublicApiBaseUrl().isBlank() ? "" : reportUrl(report);
        Integer confidenceScore = confidenceScore(report);
        String confidence = confidenceScore == null ? "unavailable" : confidenceScore + "%";
        String text = String.join(
            "\n",
            "[Cluster RCA Alert]",
            "Cluster: " + report.clusterId(),
            "Node: " + evidenceNodeName(evidence),
            "Severity: " + severity,
            "Likely Cause: " + mostLikelyCause(report),
            "Confidence: " + confidence,
            reportUrl.isBlank() ? "Report: " + report.reportId() : "Report: " + reportUrl
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", SensitiveDataRedactor.redactText(text));
        return Map.copyOf(payload);
    }

    private Map<String, Object> testSlackPayload() {
        String text = String.join(
            "\n",
            "[Cluster RCA Console Test]",
            "Event: notification.test",
            "Generated: " + Instant.now()
        );
        return Map.of("text", text);
    }

    private Map<String, Object> webhookPayload(RcaReport report, EvidenceBundle evidence, String severity) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "rca-notification/v1");
        payload.put("event_type", "rca.incident");
        payload.put("severity", severity);
        payload.put("cluster_id", safe(report.clusterId()));
        payload.put("incident_id", safe(report.incidentId()));
        payload.put("report_id", safe(report.reportId()));
        payload.put("node_name", safe(evidenceNodeName(evidence)));
        payload.put("alert_name", safe(evidenceAlertName(evidence)));
        payload.put("most_likely_cause", safe(mostLikelyCause(report)));
        payload.put("confidence_score", confidenceScore(report));
        payload.put("report_url", properties.getPublicApiBaseUrl().isBlank() ? "" : reportUrl(report));
        payload.put("created_at", report.createdAt() == null ? "" : report.createdAt().toString());
        return Collections.unmodifiableMap(payload);
    }

    private Map<String, Object> testWebhookPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "rca-notification/v1");
        payload.put("event_type", "rca.notification_test");
        payload.put("severity", "info");
        payload.put("source", "cluster-rca-console");
        payload.put("test", true);
        payload.put("report_url", properties.getPublicApiBaseUrl().isBlank()
            ? ""
            : properties.getPublicApiBaseUrl().replaceAll("/+$", ""));
        payload.put("created_at", Instant.now().toString());
        return Collections.unmodifiableMap(payload);
    }

    private String reportUrl(RcaReport report) {
        String encodedReportId = URLEncoder.encode(report.reportId(), StandardCharsets.UTF_8);
        return properties.getPublicApiBaseUrl().replaceAll("/+$", "") + "/?report=" + encodedReportId;
    }

    private String mostLikelyCause(RcaReport report) {
        if (report.summary() == null || report.summary().mostLikelyCause() == null) {
            return "";
        }
        return report.summary().mostLikelyCause();
    }

    private String evidenceNodeName(EvidenceBundle evidence) {
        return evidence == null || evidence.nodeName() == null ? "" : evidence.nodeName();
    }

    private String evidenceAlertName(EvidenceBundle evidence) {
        return evidence == null || evidence.alertName() == null ? "" : evidence.alertName();
    }

    private Integer confidenceScore(RcaReport report) {
        if (report.rootCauseCandidates() == null || report.rootCauseCandidates().isEmpty()) {
            return null;
        }
        return report.rootCauseCandidates().getFirst().confidenceScore();
    }

    private String safe(String value) {
        return value == null ? "" : SensitiveDataRedactor.redactText(value);
    }

    private String severity(RcaReport report) {
        List<Map<String, Object>> sections = report.evidence() == null ? List.of() : report.evidence();
        for (Map<String, Object> section : sections) {
            if (!"derived_signals".equals(section.get("type"))) {
                continue;
            }
            Object rawSignals = section.get("signals");
            if (!(rawSignals instanceof List<?> signals)) {
                continue;
            }
            boolean warning = false;
            for (Object rawSignal : signals) {
                if (!(rawSignal instanceof Map<?, ?> signal)) {
                    continue;
                }
                String value = String.valueOf(signal.get("severity")).toLowerCase(Locale.ROOT);
                if ("critical".equals(value)) {
                    return "critical";
                }
                warning |= "warning".equals(value);
            }
            if (warning) {
                return "warning";
            }
        }
        return "info";
    }

    private boolean meetsThreshold(String severity, String minimum) {
        return rank(severity) >= rank(minimum);
    }

    private int rank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private String safeError(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown notification failure";
        }
        String redacted = SensitiveDataRedactor.redactText(error.getMessage());
        return redacted.length() > 300 ? redacted.substring(0, 300) : redacted;
    }

    private record NotificationTarget(
        String channel,
        String url,
        String token,
        Map<String, Object> payload
    ) {
    }
}
