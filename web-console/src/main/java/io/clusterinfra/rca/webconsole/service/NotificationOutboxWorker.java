package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxStatus;
import io.clusterinfra.rca.webconsole.persistence.NotificationOutboxRepository;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxWorker.class);

    private final NotificationOutboxRepository outbox;
    private final IncidentNotificationService notifications;
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final RcaMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String workerId = "notification-worker-" + UUID.randomUUID().toString().substring(0, 8);

    public NotificationOutboxWorker(
        NotificationOutboxRepository outbox,
        IncidentNotificationService notifications,
        RcaConsoleProperties properties,
        AuditService audit,
        RcaMetrics metrics
    ) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Scheduled(
        fixedDelayString = "${rca.notification.poll-interval-ms:1000}",
        initialDelayString = "${rca.notification.initial-delay-ms:3000}"
    )
    public int processAvailableEvents() {
        RcaConsoleProperties.Notification config = properties.getNotification();
        if (!config.isEnabled()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NotificationOutboxEvent> claimed = outbox.claim(
            workerId,
            config.getBatchSize(),
            now,
            now.plusSeconds(Math.max(15, config.getLeaseSeconds()))
        );
        List<Future<?>> futures = new ArrayList<>();
        for (NotificationOutboxEvent event : claimed) {
            futures.add(executor.submit(() -> process(event)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception exception) {
                LOGGER.error("Notification outbox worker thread failed", exception);
            }
        }
        return claimed.size();
    }

    private void process(NotificationOutboxEvent event) {
        IncidentNotificationService.DeliveryAttempt delivery = notifications.deliver(event);
        if (delivery.succeeded()) {
            Instant deliveredAt = Instant.now();
            if (!outbox.markSent(event.eventId(), workerId, delivery.statusCode(), deliveredAt)) {
                LOGGER.warn("Notification outbox lease was lost before completion: {}", event.eventId());
                return;
            }
            metrics.notification("sent", event.severity());
            recordAudit(event, "notification.sent", "success", delivery, 0);
            return;
        }

        int retrySeconds = retryDelaySeconds(event.attemptCount());
        String error = safeError(delivery.error());
        try {
            NotificationOutboxStatus status = outbox.markFailed(
                event,
                workerId,
                delivery.statusCode(),
                error,
                Instant.now().plusSeconds(retrySeconds),
                delivery.retryable()
            );
            String result = status == NotificationOutboxStatus.retry_wait
                ? "retry_scheduled"
                : "dead_letter";
            metrics.notification(result, event.severity());
            recordAudit(
                event,
                status == NotificationOutboxStatus.retry_wait
                    ? "notification.retry_scheduled"
                    : "notification.dead_lettered",
                result,
                new IncidentNotificationService.DeliveryAttempt(
                    false,
                    delivery.retryable(),
                    delivery.statusCode(),
                    error
                ),
                status == NotificationOutboxStatus.retry_wait ? retrySeconds : 0
            );
        } catch (IllegalStateException exception) {
            LOGGER.warn(exception.getMessage());
        }
    }

    private void recordAudit(
        NotificationOutboxEvent event,
        String eventType,
        String outcome,
        IncidentNotificationService.DeliveryAttempt delivery,
        int retrySeconds
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incident_id", event.incidentId());
        details.put("report_id", event.reportId());
        details.put("channel", event.channel());
        details.put("severity", event.severity());
        details.put("attempt", event.attemptCount());
        details.put("max_attempts", event.maxAttempts());
        details.put("status_code", delivery.statusCode() == null ? "" : delivery.statusCode());
        details.put("retryable", delivery.retryable());
        details.put("retry_in_seconds", retrySeconds);
        details.put("idempotency_key", event.idempotencyKey());
        String error = delivery.error() == null ? "" : delivery.error();
        if (!error.isBlank()) {
            details.put("error", error);
        }
        try {
            audit.system(
                workerId,
                eventType,
                "notification_outbox",
                event.eventId(),
                outcome,
                details
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record notification audit event: {}", event.eventId(), exception);
        }
    }

    private int retryDelaySeconds(int attemptCount) {
        RcaConsoleProperties.Notification config = properties.getNotification();
        int base = Math.max(1, config.getRetryBaseSeconds());
        int maximum = Math.max(base, config.getRetryMaxSeconds());
        long multiplier = 1L << Math.min(Math.max(0, attemptCount - 1), 20);
        return (int) Math.min(maximum, base * multiplier);
    }

    private String safeError(String error) {
        String value = error == null || error.isBlank() ? "unknown notification failure" : error;
        String redacted = SensitiveDataRedactor.redactText(value);
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }
}
