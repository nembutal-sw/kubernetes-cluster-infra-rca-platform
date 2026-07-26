package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationOutboxRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void enqueue(NotificationOutboxEvent event) {
        jdbc.update(
            """
                INSERT INTO notification_outbox(
                    event_id, idempotency_key, incident_id, report_id, channel, severity,
                    payload_json, status, attempt_count, max_attempts, next_attempt_at,
                    lease_owner, lease_expires_at, last_status_code, last_error,
                    created_at, updated_at, delivered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.eventId(),
            event.idempotencyKey(),
            event.incidentId(),
            event.reportId(),
            event.channel(),
            event.severity(),
            json(event.payload()),
            event.status().name(),
            event.attemptCount(),
            event.maxAttempts(),
            timestamp(event.nextAttemptAt()),
            event.leaseOwner(),
            timestamp(event.leaseExpiresAt()),
            event.lastStatusCode(),
            event.lastError(),
            timestamp(event.createdAt()),
            timestamp(event.updatedAt()),
            timestamp(event.deliveredAt())
        );
    }

    public Optional<NotificationOutboxEvent> find(String eventId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT * FROM notification_outbox WHERE event_id = ?",
                this::mapEvent,
                eventId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public long count(NotificationOutboxStatus status) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_outbox WHERE status = ?",
            Long.class,
            status.name()
        );
        return count == null ? 0 : count;
    }

    public List<NotificationOutboxEvent> list(NotificationOutboxStatus status, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        if (status == null) {
            return jdbc.query(
                "SELECT * FROM notification_outbox ORDER BY created_at DESC LIMIT ?",
                this::mapEvent,
                safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM notification_outbox WHERE status = ? ORDER BY created_at DESC LIMIT ?",
            this::mapEvent,
            status.name(),
            safeLimit
        );
    }

    @Transactional
    public List<NotificationOutboxEvent> claim(
        String leaseOwner,
        int limit,
        Instant now,
        Instant leaseExpiresAt
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<String> candidates = jdbc.queryForList(
            """
                SELECT event_id FROM notification_outbox
                WHERE ((status IN (?, ?) AND next_attempt_at <= ?)
                    OR (status = ? AND lease_expires_at < ?))
                ORDER BY next_attempt_at, created_at
                LIMIT ?
                """,
            String.class,
            NotificationOutboxStatus.queued.name(),
            NotificationOutboxStatus.retry_wait.name(),
            timestamp(now),
            NotificationOutboxStatus.processing.name(),
            timestamp(now),
            safeLimit * 3
        );
        List<NotificationOutboxEvent> claimed = new ArrayList<>();
        for (String eventId : candidates) {
            int updated = jdbc.update(
                """
                    UPDATE notification_outbox
                    SET status = ?, attempt_count = attempt_count + 1, lease_owner = ?,
                        lease_expires_at = ?, updated_at = ?, last_error = NULL
                    WHERE event_id = ? AND ((status IN (?, ?) AND next_attempt_at <= ?)
                        OR (status = ? AND lease_expires_at < ?))
                    """,
                NotificationOutboxStatus.processing.name(),
                leaseOwner,
                timestamp(leaseExpiresAt),
                timestamp(now),
                eventId,
                NotificationOutboxStatus.queued.name(),
                NotificationOutboxStatus.retry_wait.name(),
                timestamp(now),
                NotificationOutboxStatus.processing.name(),
                timestamp(now)
            );
            if (updated == 1) {
                find(eventId).ifPresent(claimed::add);
            }
            if (claimed.size() >= safeLimit) {
                break;
            }
        }
        return List.copyOf(claimed);
    }

    public boolean markSent(
        NotificationOutboxEvent event,
        String leaseOwner,
        int statusCode,
        Instant deliveredAt
    ) {
        return jdbc.update(
            """
                UPDATE notification_outbox
                SET status = ?, lease_owner = NULL, lease_expires_at = NULL,
                    last_status_code = ?, last_error = NULL, delivered_at = ?,
                    next_attempt_at = ?, updated_at = ?
                WHERE event_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            NotificationOutboxStatus.sent.name(),
            statusCode,
            timestamp(deliveredAt),
            timestamp(deliveredAt),
            timestamp(deliveredAt),
            event.eventId(),
            NotificationOutboxStatus.processing.name(),
            leaseOwner,
            event.attemptCount()
        ) == 1;
    }

    public boolean renewLease(
        NotificationOutboxEvent event,
        String leaseOwner,
        Instant leaseExpiresAt
    ) {
        return jdbc.update(
            """
                UPDATE notification_outbox
                SET lease_expires_at = ?, updated_at = ?
                WHERE event_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            timestamp(leaseExpiresAt),
            timestamp(Instant.now()),
            event.eventId(),
            NotificationOutboxStatus.processing.name(),
            leaseOwner,
            event.attemptCount()
        ) == 1;
    }

    public NotificationOutboxStatus markFailed(
        NotificationOutboxEvent event,
        String leaseOwner,
        Integer statusCode,
        String error,
        Instant nextAttemptAt,
        boolean retryable
    ) {
        boolean exhausted = event.attemptCount() >= event.maxAttempts();
        NotificationOutboxStatus status = retryable && !exhausted
            ? NotificationOutboxStatus.retry_wait
            : NotificationOutboxStatus.dead_letter;
        Instant now = Instant.now();
        int updated = jdbc.update(
            """
                UPDATE notification_outbox
                SET status = ?, lease_owner = NULL, lease_expires_at = NULL,
                    last_status_code = ?, last_error = ?, next_attempt_at = ?, updated_at = ?
                WHERE event_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            status.name(),
            statusCode,
            error,
            timestamp(status == NotificationOutboxStatus.retry_wait ? nextAttemptAt : now),
            timestamp(now),
            event.eventId(),
            NotificationOutboxStatus.processing.name(),
            leaseOwner,
            event.attemptCount()
        );
        if (updated != 1) {
            throw new IllegalStateException("notification outbox lease was lost: " + event.eventId());
        }
        return status;
    }

    public Optional<NotificationOutboxEvent> retryDeadLetter(String eventId, Instant now) {
        int updated = jdbc.update(
            """
                UPDATE notification_outbox
                SET status = ?, attempt_count = 0, next_attempt_at = ?, lease_owner = NULL,
                    lease_expires_at = NULL, last_status_code = NULL, last_error = NULL,
                    updated_at = ?, delivered_at = NULL
                WHERE event_id = ? AND status = ?
                """,
            NotificationOutboxStatus.queued.name(),
            timestamp(now),
            timestamp(now),
            eventId,
            NotificationOutboxStatus.dead_letter.name()
        );
        return updated == 0 ? Optional.empty() : find(eventId);
    }

    private NotificationOutboxEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NotificationOutboxEvent(
            resultSet.getString("event_id"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("incident_id"),
            resultSet.getString("report_id"),
            resultSet.getString("channel"),
            resultSet.getString("severity"),
            map(resultSet.getString("payload_json")),
            NotificationOutboxStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("max_attempts"),
            instant(resultSet, "next_attempt_at"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_expires_at"),
            nullableInteger(resultSet, "last_status_code"),
            resultSet.getString("last_error"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "delivered_at")
        );
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize notification outbox payload", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize notification outbox payload", exception);
        }
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
