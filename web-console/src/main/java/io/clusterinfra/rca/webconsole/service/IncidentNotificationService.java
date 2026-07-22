package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationDeliveryResult;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationTestResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.NotificationOutboxRepository;
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
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IncidentNotificationService {
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final NotificationOutboxRepository outbox;
    private final HttpClient httpClient;

    public IncidentNotificationService(
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        NotificationOutboxRepository outbox
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public List<NotificationOutboxEvent> enqueueIncident(RcaReport report, EvidenceBundle evidence) {
        RcaConsoleProperties.Notification config = properties.getNotification();
        String severity = severity(report);
        if (!config.isEnabled() || !meetsThreshold(severity, config.getMinimumSeverity())) {
            return List.of();
        }
        List<NotificationTarget> targets = targets(config, report, evidence, severity);
        if (targets.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        List<NotificationOutboxEvent> events = new ArrayList<>();
        for (NotificationTarget target : targets) {
            String eventId = "notification-" + UUID.randomUUID().toString().replace("-", "");
            String idempotencyKey = "rca-notification:v1:" + report.reportId() + ":" + target.channel();
            NotificationOutboxEvent event = new NotificationOutboxEvent(
                eventId,
                idempotencyKey,
                report.incidentId(),
                report.reportId(),
                target.channel(),
                severity,
                withDeliveryMetadata(target.payload(), eventId, idempotencyKey),
                NotificationOutboxStatus.queued,
                0,
                Math.max(1, Math.min(10, config.getMaxAttempts())),
                now,
                null,
                null,
                null,
                null,
                now,
                now,
                null
            );
            outbox.enqueue(event);
            events.add(event);
        }
        return List.copyOf(events);
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

    public DeliveryAttempt deliver(NotificationOutboxEvent event) {
        RcaConsoleProperties.Notification config = properties.getNotification();
        NotificationTarget target = configuredTarget(config, event);
        if (target == null) {
            return new DeliveryAttempt(
                false,
                false,
                null,
                "notification target is no longer configured"
            );
        }
        try {
            int status = send(
                target.url(),
                target.token(),
                target.payload(),
                config.getTimeoutSeconds(),
                event.idempotencyKey()
            );
            if (status >= 200 && status < 300) {
                return new DeliveryAttempt(true, false, status, "");
            }
            return new DeliveryAttempt(
                false,
                retryableStatus(status),
                status,
                "notification webhook returned HTTP " + status
            );
        } catch (Exception exception) {
            return new DeliveryAttempt(false, true, null, safeError(exception));
        }
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
                int status = send(
                    target.url(),
                    target.token(),
                    target.payload(),
                    config.getTimeoutSeconds(),
                    target.idempotencyKey()
                );
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
        int timeoutSeconds,
        String idempotencyKey
    ) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(30, timeoutSeconds)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
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
                slackPayload(report, evidence, severity),
                ""
            ));
        }
        if (!config.getWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "webhook",
                config.getWebhookUrl(),
                config.getWebhookToken(),
                webhookPayload(report, evidence, severity),
                ""
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
                testSlackPayload(),
                "rca-notification-test:" + UUID.randomUUID()
            ));
        }
        if (!config.getWebhookUrl().isBlank()) {
            result.add(new NotificationTarget(
                "webhook",
                config.getWebhookUrl(),
                config.getWebhookToken(),
                testWebhookPayload(),
                "rca-notification-test:" + UUID.randomUUID()
            ));
        }
        return List.copyOf(result);
    }

    private NotificationTarget configuredTarget(
        RcaConsoleProperties.Notification config,
        NotificationOutboxEvent event
    ) {
        return switch (event.channel()) {
            case "slack" -> config.getSlackWebhookUrl().isBlank()
                ? null
                : new NotificationTarget(
                    "slack",
                    config.getSlackWebhookUrl(),
                    "",
                    event.payload(),
                    event.idempotencyKey()
                );
            case "webhook" -> config.getWebhookUrl().isBlank()
                ? null
                : new NotificationTarget(
                    "webhook",
                    config.getWebhookUrl(),
                    config.getWebhookToken(),
                    event.payload(),
                    event.idempotencyKey()
                );
            default -> null;
        };
    }

    private Map<String, Object> withDeliveryMetadata(
        Map<String, Object> payload,
        String eventId,
        String idempotencyKey
    ) {
        if (!payload.containsKey("schema_version")) {
            return payload;
        }
        LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(payload);
        enriched.put("event_id", eventId);
        enriched.put("idempotency_key", idempotencyKey);
        return Collections.unmodifiableMap(enriched);
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

    private boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
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
        Map<String, Object> payload,
        String idempotencyKey
    ) {
    }

    public record DeliveryAttempt(
        boolean succeeded,
        boolean retryable,
        Integer statusCode,
        String error
    ) {
    }
}
