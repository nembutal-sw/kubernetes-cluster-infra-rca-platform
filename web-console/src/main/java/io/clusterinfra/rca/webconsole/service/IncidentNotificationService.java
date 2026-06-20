package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        String webhookUrl = config.getSlackWebhookUrl();
        String severity = severity(report);
        if (!config.isEnabled() || webhookUrl.isBlank() || !meetsThreshold(severity, config.getMinimumSeverity())) {
            return;
        }

        int attempts = Math.max(1, Math.min(5, config.getMaxAttempts()));
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                int status = send(webhookUrl, payload(report, evidence, severity), config.getTimeoutSeconds());
                if (status >= 200 && status < 300) {
                    metrics.notification("sent", severity);
                    audit.system(
                        "notification",
                        "notification.sent",
                        "incident",
                        report.incidentId(),
                        "success",
                        Map.of("channel", "slack", "severity", severity, "attempt", attempt)
                    );
                    return;
                }
                lastError = new IllegalStateException("Slack webhook returned HTTP " + status);
            } catch (Exception exception) {
                lastError = exception;
            }
        }
        metrics.notification("failed", severity);
        audit.system(
            "notification",
            "notification.failed",
            "incident",
            report.incidentId(),
            "failed",
            Map.of(
                "channel", "slack",
                "severity", severity,
                "attempts", attempts,
                "error", safeError(lastError)
            )
        );
    }

    private int send(String webhookUrl, Map<String, Object> payload, int timeoutSeconds) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(30, timeoutSeconds)));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Map<String, Object> payload(RcaReport report, EvidenceBundle evidence, String severity) {
        String reportUrl = properties.getPublicApiBaseUrl().isBlank()
            ? ""
            : properties.getPublicApiBaseUrl().replaceAll("/+$", "")
                + "/?report=" + report.reportId();
        String confidence = report.rootCauseCandidates() == null || report.rootCauseCandidates().isEmpty()
            ? "unavailable"
            : report.rootCauseCandidates().getFirst().confidenceScore() + "%";
        String text = String.join(
            "\n",
            "[Cluster RCA Alert]",
            "Cluster: " + report.clusterId(),
            "Node: " + evidence.nodeName(),
            "Severity: " + severity,
            "Likely Cause: " + report.summary().mostLikelyCause(),
            "Confidence: " + confidence,
            reportUrl.isBlank() ? "Report: " + report.reportId() : "Report: " + reportUrl
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", SensitiveDataRedactor.redactText(text));
        return Map.copyOf(payload);
    }

    private String severity(RcaReport report) {
        for (Map<String, Object> section : report.evidence()) {
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
        return SensitiveDataRedactor.redactText(error.getMessage());
    }
}
