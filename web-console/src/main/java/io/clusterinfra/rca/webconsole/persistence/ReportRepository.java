package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReportRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RootCauseCandidate>> CANDIDATE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RecommendedAction>> ACTION_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReportRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RcaJob save(RcaReport report, RcaJob job) {
        saveReport(report);
        insertJob(job);
        return job;
    }

    public RcaReport saveReport(RcaReport report) {
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, incident_id, status, trigger_json, scope_json, summary_json, evidence_json,
                     root_cause_candidates_json, recommended_actions_json, policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            report.reportId(),
            report.clusterId(),
            report.incidentId(),
            report.status().name(),
            json(report.trigger()),
            json(report.scope()),
            json(report.summary()),
            json(report.evidence()),
            json(report.rootCauseCandidates()),
            json(report.recommendedActions()),
            json(report.policyDecisions()),
            timestamp(report.createdAt())
        );
        return report;
    }

    public List<RcaJob> listJobs() {
        return jdbc.query("SELECT * FROM rca_jobs ORDER BY created_at DESC", this::mapJob);
    }

    public Optional<RcaJob> findJob(String jobId) {
        return optionalQuery("SELECT * FROM rca_jobs WHERE job_id = ?", this::mapJob, jobId);
    }

    public List<RcaReport> listReports() {
        return jdbc.query("SELECT * FROM rca_reports ORDER BY created_at DESC", this::mapReport);
    }

    public Optional<RcaReport> findReport(String reportId) {
        return optionalQuery("SELECT * FROM rca_reports WHERE report_id = ?", this::mapReport, reportId);
    }

    private void insertJob(RcaJob job) {
        jdbc.update(
            """
                INSERT INTO rca_jobs
                    (job_id, cluster_id, alert_name, node_name, status, report_id, evidence_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            job.jobId(),
            job.clusterId(),
            job.alertName(),
            job.nodeName(),
            job.status().name(),
            job.reportId(),
            job.evidenceId(),
            timestamp(job.createdAt())
        );
    }

    private RcaJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RcaJob(
            resultSet.getString("job_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("alert_name"),
            resultSet.getString("node_name"),
            RcaJobStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("report_id"),
            resultSet.getString("evidence_id"),
            instant(resultSet, "created_at")
        );
    }

    private RcaReport mapReport(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RcaReport(
            resultSet.getString("report_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("incident_id"),
            RcaJobStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("trigger_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("scope_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("summary_json"), RcaModels.RcaSummary.class),
            read(resultSet.getString("evidence_json"), MAP_LIST, List.of()),
            read(resultSet.getString("root_cause_candidates_json"), CANDIDATE_LIST, List.of()),
            read(resultSet.getString("recommended_actions_json"), ACTION_LIST, List.of()),
            read(resultSet.getString("policy_decisions_json"), ACTION_LIST, List.of()),
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
}
