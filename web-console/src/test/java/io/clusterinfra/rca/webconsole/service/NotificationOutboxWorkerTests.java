package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxStatus;
import io.clusterinfra.rca.webconsole.persistence.NotificationOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationOutboxWorkerTests {
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");

    private NotificationOutboxRepository outbox;
    private IncidentNotificationService notifications;
    private AuditService audit;
    private SimpleMeterRegistry registry;
    private RcaConsoleProperties properties;
    private NotificationOutboxWorker worker;

    @BeforeEach
    void setUp() {
        outbox = mock(NotificationOutboxRepository.class);
        notifications = mock(IncidentNotificationService.class);
        audit = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        properties = new RcaConsoleProperties();
        properties.getNotification().setEnabled(true);
        properties.getNotification().setBatchSize(4);
        properties.getNotification().setRetryBaseSeconds(5);
        properties.getNotification().setRetryMaxSeconds(30);
        worker = new NotificationOutboxWorker(
            outbox,
            notifications,
            properties,
            audit,
            new RcaMetrics(registry)
        );
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    void successfulDeliveryMarksEventSentAndAuditsOutcome() {
        NotificationOutboxEvent event = event("event-success", 1, 3);
        when(outbox.claim(anyString(), eq(4), any(), any())).thenReturn(List.of(event));
        when(notifications.deliver(event))
            .thenReturn(new IncidentNotificationService.DeliveryAttempt(true, false, 202, ""));
        when(outbox.markSent(eq(event.eventId()), anyString(), eq(202), any())).thenReturn(true);

        assertThat(worker.processAvailableEvents()).isEqualTo(1);

        verify(outbox).markSent(eq(event.eventId()), anyString(), eq(202), any());
        verify(outbox, never()).markFailed(any(), anyString(), any(), anyString(), any(), anyBoolean());
        verify(audit).system(
            anyString(),
            eq("notification.sent"),
            eq("notification_outbox"),
            eq(event.eventId()),
            eq("success"),
            any()
        );
        assertThat(registry.get("rca.notification")
            .tag("result", "sent").tag("severity", "critical").counter().count()).isEqualTo(1);
    }

    @Test
    void retryableFailureSchedulesExponentialRetry() {
        NotificationOutboxEvent event = event("event-retry", 2, 3);
        when(outbox.claim(anyString(), eq(4), any(), any())).thenReturn(List.of(event));
        when(notifications.deliver(event))
            .thenReturn(new IncidentNotificationService.DeliveryAttempt(false, true, 503, "unavailable"));
        when(outbox.markFailed(
            eq(event),
            anyString(),
            eq(503),
            eq("unavailable"),
            any(),
            eq(true)
        )).thenReturn(NotificationOutboxStatus.retry_wait);

        assertThat(worker.processAvailableEvents()).isEqualTo(1);

        verify(outbox).markFailed(
            eq(event),
            anyString(),
            eq(503),
            eq("unavailable"),
            any(),
            eq(true)
        );
        verify(audit).system(
            anyString(),
            eq("notification.retry_scheduled"),
            eq("notification_outbox"),
            eq(event.eventId()),
            eq("retry_scheduled"),
            any()
        );
        assertThat(registry.get("rca.notification")
            .tag("result", "retry_scheduled").tag("severity", "critical").counter().count())
            .isEqualTo(1);
    }

    @Test
    void permanentFailureMovesEventToDeadLetter() {
        NotificationOutboxEvent event = event("event-dead", 1, 5);
        when(outbox.claim(anyString(), eq(4), any(), any())).thenReturn(List.of(event));
        when(notifications.deliver(event))
            .thenReturn(new IncidentNotificationService.DeliveryAttempt(false, false, 401, "unauthorized"));
        when(outbox.markFailed(
            eq(event),
            anyString(),
            eq(401),
            eq("unauthorized"),
            any(),
            eq(false)
        )).thenReturn(NotificationOutboxStatus.dead_letter);

        assertThat(worker.processAvailableEvents()).isEqualTo(1);

        verify(audit).system(
            anyString(),
            eq("notification.dead_lettered"),
            eq("notification_outbox"),
            eq(event.eventId()),
            eq("dead_letter"),
            any()
        );
        assertThat(registry.get("rca.notification")
            .tag("result", "dead_letter").tag("severity", "critical").counter().count())
            .isEqualTo(1);
    }

    @Test
    void disabledNotificationDeliveryDoesNotClaimOutboxEvents() {
        properties.getNotification().setEnabled(false);

        assertThat(worker.processAvailableEvents()).isZero();

        verifyNoInteractions(outbox, notifications, audit);
    }

    private NotificationOutboxEvent event(String eventId, int attemptCount, int maxAttempts) {
        return new NotificationOutboxEvent(
            eventId,
            "idempotency-" + eventId,
            "incident-1",
            "report-1",
            "webhook",
            "critical",
            Map.of("event_type", "rca.incident"),
            NotificationOutboxStatus.processing,
            attemptCount,
            maxAttempts,
            NOW,
            "worker",
            NOW.plusSeconds(30),
            null,
            null,
            NOW,
            NOW,
            null
        );
    }
}
