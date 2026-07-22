package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NotificationOutboxRepositoryTests {
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");

    private NotificationOutboxRepository outbox;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:notification-outbox-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        outbox = new NotificationOutboxRepository(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @Test
    void enqueuesAndClaimsAnEventOnlyOnceAcrossConcurrentWorkers() throws Exception {
        outbox.enqueue(event("event-1", 3));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<List<NotificationOutboxEvent>>> calls = List.of(
                () -> claimAfterBarrier(barrier, "worker-a"),
                () -> claimAfterBarrier(barrier, "worker-b")
            );
            List<Future<List<NotificationOutboxEvent>>> futures = executor.invokeAll(calls);
            List<NotificationOutboxEvent> claimed = futures.stream()
                .flatMap(future -> result(future).stream())
                .toList();

            assertThat(claimed).hasSize(1);
            assertThat(claimed.getFirst().eventId()).isEqualTo("event-1");
            assertThat(claimed.getFirst().attemptCount()).isEqualTo(1);
            assertThat(outbox.count(NotificationOutboxStatus.processing)).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndCompleted() {
        outbox.enqueue(event("event-lease", 3));
        NotificationOutboxEvent first = outbox.claim(
            "worker-a",
            1,
            NOW,
            NOW.plusSeconds(30)
        ).getFirst();

        assertThat(outbox.claim("worker-b", 1, NOW.plusSeconds(10), NOW.plusSeconds(40))).isEmpty();
        NotificationOutboxEvent reclaimed = outbox.claim(
            "worker-b",
            1,
            NOW.plusSeconds(31),
            NOW.plusSeconds(61)
        ).getFirst();

        assertThat(reclaimed.attemptCount()).isEqualTo(first.attemptCount() + 1);
        assertThat(outbox.markSent("event-lease", "worker-b", 204, NOW.plusSeconds(32))).isTrue();
        NotificationOutboxEvent sent = outbox.find("event-lease").orElseThrow();
        assertThat(sent.status()).isEqualTo(NotificationOutboxStatus.sent);
        assertThat(sent.lastStatusCode()).isEqualTo(204);
        assertThat(sent.deliveredAt()).isEqualTo(NOW.plusSeconds(32));
    }

    @Test
    void retryableFailureUsesRetryWaitThenDeadLettersAtAttemptLimit() {
        outbox.enqueue(event("event-retry", 2));
        NotificationOutboxEvent first = outbox.claim(
            "worker-a",
            1,
            NOW,
            NOW.plusSeconds(30)
        ).getFirst();
        NotificationOutboxStatus firstStatus = outbox.markFailed(
            first,
            "worker-a",
            503,
            "temporary failure",
            NOW.plusSeconds(5),
            true
        );

        assertThat(firstStatus).isEqualTo(NotificationOutboxStatus.retry_wait);
        assertThat(outbox.claim("early-worker", 1, NOW.plusSeconds(4), NOW.plusSeconds(34))).isEmpty();
        NotificationOutboxEvent second = outbox.claim(
            "worker-b",
            1,
            NOW.plusSeconds(6),
            NOW.plusSeconds(36)
        ).getFirst();
        NotificationOutboxStatus finalStatus = outbox.markFailed(
            second,
            "worker-b",
            503,
            "still unavailable",
            NOW.plusSeconds(16),
            true
        );

        assertThat(finalStatus).isEqualTo(NotificationOutboxStatus.dead_letter);
        assertThat(outbox.find("event-retry").orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(outbox.retryDeadLetter("event-retry", NOW.plusSeconds(20))).isPresent();
        NotificationOutboxEvent retried = outbox.find("event-retry").orElseThrow();
        assertThat(retried.status()).isEqualTo(NotificationOutboxStatus.queued);
        assertThat(retried.attemptCount()).isZero();
        assertThat(retried.lastError()).isNull();
    }

    @Test
    void nonRetryableFailureDeadLettersImmediately() {
        outbox.enqueue(event("event-permanent", 5));
        NotificationOutboxEvent claimed = outbox.claim(
            "worker-a",
            1,
            NOW,
            NOW.plusSeconds(30)
        ).getFirst();

        NotificationOutboxStatus status = outbox.markFailed(
            claimed,
            "worker-a",
            401,
            "unauthorized",
            NOW.plusSeconds(5),
            false
        );

        assertThat(status).isEqualTo(NotificationOutboxStatus.dead_letter);
        assertThat(outbox.find("event-permanent").orElseThrow().attemptCount()).isEqualTo(1);
    }

    private List<NotificationOutboxEvent> claimAfterBarrier(CyclicBarrier barrier, String worker) {
        try {
            barrier.await();
            return outbox.claim(worker, 1, NOW, NOW.plusSeconds(30));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<NotificationOutboxEvent> result(Future<List<NotificationOutboxEvent>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private NotificationOutboxEvent event(String eventId, int maxAttempts) {
        return new NotificationOutboxEvent(
            eventId,
            "idempotency-" + eventId,
            "incident-1",
            "report-1",
            "webhook",
            "critical",
            Map.of("event_type", "rca.incident"),
            NotificationOutboxStatus.queued,
            0,
            maxAttempts,
            NOW,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            null
        );
    }
}
