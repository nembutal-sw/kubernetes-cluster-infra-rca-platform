package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AuditEvent save(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        AuditEvent event = new AuditEvent(
            id("audit"),
            actorType,
            actorId,
            eventType,
            resourceType,
            resourceId,
            outcome,
            details == null ? Map.of() : details,
            databaseInstant()
        );
        jdbc.update(
            """
                INSERT INTO audit_events
                    (audit_event_id, actor_type, actor_id, event_type, resource_type,
                     resource_id, outcome, details_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.auditEventId(),
            event.actorType(),
            event.actorId(),
            event.eventType(),
            event.resourceType(),
            event.resourceId(),
            event.outcome(),
            json(event.details()),
            timestamp(event.createdAt())
        );
        return event;
    }

    public List<AuditEvent> list(Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        return jdbc.query(
            "SELECT * FROM audit_events ORDER BY created_at DESC LIMIT ?",
            this::mapAuditEvent,
            safeLimit
        );
    }

    public List<AuditEvent> search(AuditSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        appendExactAuditFilter(sql, parameters, "actor_type", criteria.actorType());
        appendExactAuditFilter(sql, parameters, "actor_id", criteria.actorId());
        appendExactAuditFilter(sql, parameters, "event_type", criteria.eventType());
        appendExactAuditFilter(sql, parameters, "resource_type", criteria.resourceType());
        appendExactAuditFilter(sql, parameters, "resource_id", criteria.resourceId());
        appendExactAuditFilter(sql, parameters, "outcome", criteria.outcome());
        if (criteria.from() != null) {
            sql.append(" AND created_at >= ?");
            parameters.add(timestamp(criteria.from()));
        }
        if (criteria.to() != null) {
            sql.append(" AND created_at <= ?");
            parameters.add(timestamp(criteria.to()));
        }
        String clientIp = normalized(criteria.clientIp());
        if (clientIp != null) {
            sql.append(" AND LOWER(details_json) LIKE ?");
            parameters.add("%client_ip%" + clientIp.toLowerCase() + "%");
        }
        String query = normalized(criteria.query());
        if (query != null) {
            sql.append(
                """
                     AND (
                         LOWER(actor_type) LIKE ?
                         OR LOWER(actor_id) LIKE ?
                         OR LOWER(event_type) LIKE ?
                         OR LOWER(resource_type) LIKE ?
                         OR LOWER(COALESCE(resource_id, '')) LIKE ?
                         OR LOWER(outcome) LIKE ?
                         OR LOWER(details_json) LIKE ?
                     )
                    """
            );
            String like = "%" + query.toLowerCase() + "%";
            for (int i = 0; i < 7; i++) {
                parameters.add(like);
            }
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        parameters.add(criteria.boundedLimit(200, 5000));
        return jdbc.query(sql.toString(), this::mapAuditEvent, parameters.toArray());
    }

    public int deleteBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM audit_events WHERE created_at < ?", timestamp(cutoff));
    }

    private void appendExactAuditFilter(
        StringBuilder sql,
        List<Object> parameters,
        String column,
        String value
    ) {
        String normalized = normalized(value);
        if (normalized != null) {
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(normalized);
        }
    }

    private AuditEvent mapAuditEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEvent(
            resultSet.getString("audit_event_id"),
            resultSet.getString("actor_type"),
            resultSet.getString("actor_id"),
            resultSet.getString("event_type"),
            resultSet.getString("resource_type"),
            resultSet.getString("resource_id"),
            resultSet.getString("outcome"),
            read(resultSet.getString("details_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "created_at")
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON is invalid", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant databaseInstant() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
