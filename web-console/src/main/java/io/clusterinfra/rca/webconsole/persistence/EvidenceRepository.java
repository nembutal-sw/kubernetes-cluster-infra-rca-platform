package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EvidenceRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AnalysisTaskRepository analysisTasks;
    private final ClusterRepository clusters;

    public EvidenceRepository(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        AnalysisTaskRepository analysisTasks,
        ClusterRepository clusters
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.analysisTasks = analysisTasks;
        this.clusters = clusters;
    }

    public EvidenceRequest createRequest(EvidenceRequestCreateRequest request) {
        EvidenceRequest evidenceRequest = new EvidenceRequest(
            id("evidence-request"),
            request.clusterId(),
            request.nodeName().trim(),
            request.alertName().trim(),
            request.collectorsOrEmpty(),
            EvidenceRequestStatus.pending,
            request.timeRangeOrEmpty(),
            blankToNull(request.reason()),
            request.contextOrEmpty(),
            null,
            null,
            Instant.now(),
            null
        );
        jdbc.update(
            """
                INSERT INTO evidence_requests
                    (request_id, cluster_id, node_name, alert_name, requested_collectors_json, status,
                     time_range_json, reason, context_json, evidence_id, error_message, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            evidenceRequest.requestId(),
            evidenceRequest.clusterId(),
            evidenceRequest.nodeName(),
            evidenceRequest.alertName(),
            json(evidenceRequest.requestedCollectors()),
            evidenceRequest.status().name(),
            json(evidenceRequest.timeRange()),
            evidenceRequest.reason(),
            json(evidenceRequest.context()),
            null,
            null,
            timestamp(evidenceRequest.createdAt()),
            null
        );
        return evidenceRequest;
    }

    public List<EvidenceRequest> listRequests(
        String clusterId,
        String nodeName,
        EvidenceRequestStatus status,
        Integer limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT * FROM evidence_requests WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (clusterId != null) {
            sql.append(" AND cluster_id = ?");
            parameters.add(clusterId);
        }
        if (nodeName != null) {
            sql.append(" AND node_name = ?");
            parameters.add(nodeName);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        sql.append(" ORDER BY created_at ASC");
        if (limit != null) {
            sql.append(" LIMIT ?");
            parameters.add(limit);
        }
        return jdbc.query(sql.toString(), this::mapEvidenceRequest, parameters.toArray());
    }

    public List<EvidenceRequest> listRecentRequests(
        String clusterId,
        String nodeName,
        EvidenceRequestStatus status,
        Instant before,
        int limit
    ) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM evidence_requests WHERE cluster_id = ?"
        );
        List<Object> parameters = new ArrayList<>();
        parameters.add(clusterId);
        if (nodeName != null && !nodeName.isBlank()) {
            sql.append(" AND node_name = ?");
            parameters.add(nodeName);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        if (before != null) {
            sql.append(" AND created_at < ?");
            parameters.add(timestamp(before));
        }
        sql.append(" ORDER BY created_at DESC, request_id DESC LIMIT ?");
        parameters.add(Math.max(1, Math.min(200, limit)));
        return jdbc.query(sql.toString(), this::mapEvidenceRequest, parameters.toArray());
    }

    public Optional<EvidenceRequest> findRequest(String requestId) {
        return optionalQuery(
            "SELECT * FROM evidence_requests WHERE request_id = ?",
            this::mapEvidenceRequest,
            requestId
        );
    }

    public boolean hasPendingRequest(String clusterId, String nodeName) {
        Integer count = jdbc.queryForObject(
            """
                SELECT COUNT(*) FROM evidence_requests
                WHERE cluster_id = ? AND node_name = ? AND status = ?
                """,
            Integer.class,
            clusterId,
            nodeName,
            EvidenceRequestStatus.pending.name()
        );
        return count != null && count > 0;
    }

    @Transactional
    public Optional<EvidenceRequest> submitResponse(AgentEvidenceSubmitRequest request, int maxAttempts) {
        Optional<EvidenceRequest> existing = findRequestForUpdate(request.requestId());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (existing.get().status() != EvidenceRequestStatus.pending) {
            return existing;
        }
        Instant completedAt = Instant.now();
        String evidenceId = null;
        if (request.statusOrDefault() == EvidenceRequestStatus.completed) {
            evidenceId = id("evidence");
            EvidenceBundle evidence = new EvidenceBundle(
                evidenceId,
                request.clusterId(),
                request.nodeName(),
                existing.get().alertName(),
                completedAt,
                request.collectorsOrEmpty()
            );
            save(evidence);
            analysisTasks.enqueue(
                evidence,
                "agent_evidence",
                "scheduled_monitoring".equals(existing.get().context().get("trigger")),
                maxAttempts
            );
        }
        jdbc.update(
            """
                UPDATE evidence_requests
                SET status = ?, evidence_id = ?, error_message = ?, completed_at = ?
                WHERE request_id = ? AND status = ?
                """,
            request.statusOrDefault().name(),
            evidenceId,
            request.statusOrDefault() == EvidenceRequestStatus.failed
                ? SensitiveDataRedactor.redactText(request.errorMessage())
                : null,
            timestamp(completedAt),
            request.requestId(),
            EvidenceRequestStatus.pending.name()
        );
        clusters.markActive(request.clusterId());
        return findRequest(request.requestId());
    }

    @Transactional
    public AnalysisTask saveAndEnqueue(
        EvidenceBundle evidence,
        String source,
        boolean skipIfHealthy,
        int maxAttempts
    ) {
        EvidenceBundle saved = save(evidence);
        return analysisTasks.enqueue(saved, source, skipIfHealthy, maxAttempts);
    }

    public EvidenceBundle save(EvidenceBundle evidence) {
        String evidenceId = evidence.evidenceId() == null ? id("evidence") : evidence.evidenceId();
        Map<String, Object> redactedCollectors = SensitiveDataRedactor.redactMap(evidence.collectors());
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            evidenceId,
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            json(redactedCollectors),
            timestamp(evidence.collectedAt())
        );
        return new EvidenceBundle(
            evidenceId,
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            evidence.collectedAt(),
            redactedCollectors
        );
    }

    public Optional<EvidenceBundle> find(String evidenceId) {
        return optionalQuery(
            "SELECT * FROM evidence_bundles WHERE evidence_id = ?",
            this::mapEvidence,
            evidenceId
        );
    }

    public List<EvidenceBundle> listForNodeWindow(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return jdbc.query(
            """
                SELECT * FROM evidence_bundles
                WHERE cluster_id = ? AND node_name = ? AND collected_at BETWEEN ? AND ?
                ORDER BY collected_at, evidence_id
                """,
            this::mapEvidence,
            clusterId,
            nodeName,
            timestamp(from),
            timestamp(to)
        );
    }

    public RealtimeEvent saveRealtimeEvent(RealtimeEvent event) {
        jdbc.update(
            """
                INSERT INTO realtime_events
                    (event_id, evidence_id, cluster_id, node_name, event_type, component,
                     severity, observed_at, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.eventId(),
            event.evidenceId(),
            event.clusterId(),
            event.nodeName(),
            event.eventType(),
            event.component(),
            event.severity(),
            timestamp(event.observedAt()),
            json(event.payload()),
            timestamp(event.createdAt())
        );
        return event;
    }

    public List<RealtimeEvent> listRealtimeEvents(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return jdbc.query(
            """
                SELECT * FROM realtime_events
                WHERE cluster_id = ? AND node_name = ? AND observed_at BETWEEN ? AND ?
                ORDER BY observed_at, event_id
                """,
            this::mapRealtimeEvent,
            clusterId,
            nodeName,
            timestamp(from),
            timestamp(to)
        );
    }

    private Optional<EvidenceRequest> findRequestForUpdate(String requestId) {
        return optionalQuery(
            "SELECT * FROM evidence_requests WHERE request_id = ? FOR UPDATE",
            this::mapEvidenceRequest,
            requestId
        );
    }

    private EvidenceRequest mapEvidenceRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EvidenceRequest(
            resultSet.getString("request_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            read(resultSet.getString("requested_collectors_json"), STRING_LIST, List.of()),
            EvidenceRequestStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("time_range_json"), OBJECT_MAP, Map.of()),
            resultSet.getString("reason"),
            read(resultSet.getString("context_json"), OBJECT_MAP, Map.of()),
            resultSet.getString("evidence_id"),
            resultSet.getString("error_message"),
            instant(resultSet, "created_at"),
            instant(resultSet, "completed_at")
        );
    }

    private EvidenceBundle mapEvidence(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EvidenceBundle(
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            instant(resultSet, "collected_at"),
            read(resultSet.getString("collectors_json"), OBJECT_MAP, Map.of())
        );
    }

    private RealtimeEvent mapRealtimeEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RealtimeEvent(
            resultSet.getString("event_id"),
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("event_type"),
            resultSet.getString("component"),
            resultSet.getString("severity"),
            instant(resultSet, "observed_at"),
            read(resultSet.getString("payload_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "created_at")
        );
    }

    private <T> Optional<T> optionalQuery(
        String sql,
        org.springframework.jdbc.core.RowMapper<T> rowMapper,
        Object... parameters
    ) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, rowMapper, parameters));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
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

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
