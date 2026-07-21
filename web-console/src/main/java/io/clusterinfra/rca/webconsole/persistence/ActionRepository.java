package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ActionRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ActionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ActionRequest createRequest(
        String reportId,
        int actionIndex,
        String actionKey,
        PolicyLevel policy,
        String source,
        ActionRequestStatus status,
        String requestedBy,
        String requestNote,
        String evidenceRequestId
    ) {
        ActionRequest request = new ActionRequest(
            id("action-request"),
            reportId,
            actionIndex,
            actionKey,
            policy,
            source,
            status,
            requestedBy,
            null,
            blankToNull(requestNote),
            null,
            evidenceRequestId,
            databaseInstant(),
            null
        );
        jdbc.update(
            """
                INSERT INTO action_requests
                    (action_request_id, report_id, action_index, action_key, policy, source, status,
                     requested_by, reviewed_by, request_note, decision_note, evidence_request_id,
                     created_at, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            request.actionRequestId(),
            request.reportId(),
            request.actionIndex(),
            request.actionKey(),
            request.policy().name(),
            request.source(),
            request.status().name(),
            request.requestedBy(),
            null,
            request.requestNote(),
            null,
            request.evidenceRequestId(),
            timestamp(request.createdAt()),
            null
        );
        return request;
    }

    @Transactional
    public Optional<ActionRequest> decide(
        String actionRequestId,
        ActionRequestStatus status,
        String reviewedBy,
        String decisionNote
    ) {
        int updated = jdbc.update(
            """
                UPDATE action_requests SET status = ?, reviewed_by = ?, decision_note = ?, reviewed_at = ?
                WHERE action_request_id = ? AND status = ?
                """,
            status.name(),
            reviewedBy,
            blankToNull(decisionNote),
            timestamp(Instant.now()),
            actionRequestId,
            ActionRequestStatus.pending_approval.name()
        );
        return updated == 0 ? Optional.empty() : findRequest(actionRequestId);
    }

    public Optional<ActionRequest> findRequest(String actionRequestId) {
        return optionalQuery(
            "SELECT * FROM action_requests WHERE action_request_id = ?",
            this::mapActionRequest,
            actionRequestId
        );
    }

    public List<ActionRequest> listRequests(String reportId) {
        return listRequests(reportId, null);
    }

    public List<ActionRequest> listRequests(String reportId, Integer limit) {
        Integer safeLimit = limit == null ? null : Math.max(1, Math.min(limit, 200));
        if (reportId == null || reportId.isBlank()) {
            if (safeLimit != null) {
                return jdbc.query(
                    "SELECT * FROM action_requests ORDER BY created_at DESC LIMIT ?",
                    this::mapActionRequest,
                    safeLimit
                );
            }
            return jdbc.query("SELECT * FROM action_requests ORDER BY created_at DESC", this::mapActionRequest);
        }
        if (safeLimit != null) {
            return jdbc.query(
                "SELECT * FROM action_requests WHERE report_id = ? ORDER BY created_at DESC LIMIT ?",
                this::mapActionRequest,
                reportId,
                safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM action_requests WHERE report_id = ? ORDER BY created_at DESC",
            this::mapActionRequest,
            reportId
        );
    }

    public long count(ActionRequestStatus status) {
        Long count = status == null
            ? jdbc.queryForObject("SELECT COUNT(*) FROM action_requests", Long.class)
            : jdbc.queryForObject(
                "SELECT COUNT(*) FROM action_requests WHERE status = ?",
                Long.class,
                status.name()
            );
        return count == null ? 0 : count;
    }

    @Transactional
    public Optional<ActionRequest> completeManual(String actionRequestId) {
        int updated = jdbc.update(
            "UPDATE action_requests SET status = ? WHERE action_request_id = ? AND status = ?",
            ActionRequestStatus.completed.name(),
            actionRequestId,
            ActionRequestStatus.approved_manual.name()
        );
        return updated == 0 ? Optional.empty() : findRequest(actionRequestId);
    }

    public Optional<ActionExecution> findExecution(String executionId) {
        return optionalQuery(
            "SELECT * FROM action_executions WHERE execution_id = ?",
            this::mapActionExecution,
            executionId
        );
    }

    public List<ActionExecution> listExecutions(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            return jdbc.query(
                "SELECT * FROM action_executions ORDER BY created_at DESC",
                this::mapActionExecution
            );
        }
        return jdbc.query(
            "SELECT * FROM action_executions WHERE report_id = ? ORDER BY created_at DESC",
            this::mapActionExecution,
            reportId
        );
    }

    private ActionRequest mapActionRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActionRequest(
            resultSet.getString("action_request_id"),
            resultSet.getString("report_id"),
            resultSet.getInt("action_index"),
            resultSet.getString("action_key"),
            RcaModels.PolicyLevel.valueOf(resultSet.getString("policy")),
            resultSet.getString("source"),
            ActionRequestStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("requested_by"),
            resultSet.getString("reviewed_by"),
            resultSet.getString("request_note"),
            resultSet.getString("decision_note"),
            resultSet.getString("evidence_request_id"),
            instant(resultSet, "created_at"),
            instant(resultSet, "reviewed_at")
        );
    }

    private ActionExecution mapActionExecution(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActionExecution(
            resultSet.getString("execution_id"),
            resultSet.getString("action_request_id"),
            resultSet.getString("report_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("action_key"),
            resultSet.getString("command_key"),
            read(resultSet.getString("parameters_json"), STRING_MAP, Map.of()),
            read(resultSet.getString("preview_json"), ActionPlan.class),
            ActionExecutionStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("timeout_seconds"),
            resultSet.getString("requested_by"),
            resultSet.getString("approved_by"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_expires_at"),
            (Integer) resultSet.getObject("exit_code"),
            resultSet.getString("stdout_text"),
            resultSet.getString("stderr_text"),
            resultSet.getString("error_message"),
            instant(resultSet, "created_at"),
            instant(resultSet, "approved_at"),
            instant(resultSet, "started_at"),
            instant(resultSet, "completed_at")
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

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON is invalid", exception);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
