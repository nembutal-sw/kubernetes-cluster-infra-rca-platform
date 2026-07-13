package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IncidentRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IncidentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RcaJob saveCorrelated(
        RcaReport report,
        RcaJob job,
        String dedupKey,
        String matchedIncidentId,
        boolean promoteRootCause,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        EvidenceBundle evidence
    ) {
        jdbc.queryForObject(
            "SELECT cluster_id FROM clusters WHERE cluster_id = ? FOR UPDATE",
            String.class,
            evidence.clusterId()
        );
        Incident incident = Optional.ofNullable(matchedIncidentId)
            .flatMap(this::find)
            .filter(candidate -> candidate.status() == IncidentStatus.open)
            .or(() -> findByDedupKey(dedupKey))
            .orElseGet(() -> createIncident(dedupKey, recurrenceOfIncidentId, recurrenceSequence, report, evidence));

        Incident locked = optionalQuery(
            "SELECT * FROM incidents WHERE incident_id = ? FOR UPDATE",
            this::mapIncident,
            incident.incidentId()
        ).orElseThrow();
        if (locked.latestReportId() != null) {
            Instant firstSeen = evidence.collectedAt().isBefore(locked.firstSeenAt())
                ? evidence.collectedAt()
                : locked.firstSeenAt();
            Instant lastSeen = evidence.collectedAt().isAfter(locked.lastSeenAt())
                ? evidence.collectedAt()
                : locked.lastSeenAt();
            String latestEvidenceId = evidence.collectedAt().isBefore(locked.lastSeenAt())
                ? locked.latestEvidenceId()
                : evidence.evidenceId();
            List<String> nodeNames = appendNode(locked.nodeNames(), evidence.nodeName());
            if (promoteRootCause) {
                saveReport(report.withIncidentId(locked.incidentId()));
                insertJob(job);
                jdbc.update(
                    """
                        UPDATE incidents SET occurrence_count = occurrence_count + 1,
                            first_seen_at = ?, last_seen_at = ?, latest_evidence_id = ?,
                            latest_report_id = ?, alert_name = ?, root_cause = ?,
                            node_names_json = ?
                        WHERE incident_id = ?
                        """,
                    timestamp(firstSeen),
                    timestamp(lastSeen),
                    latestEvidenceId,
                    report.reportId(),
                    evidence.alertName(),
                    report.summary().mostLikelyCause(),
                    json(nodeNames),
                    locked.incidentId()
                );
                return job;
            }
            jdbc.update(
                """
                    UPDATE incidents SET occurrence_count = occurrence_count + 1,
                        first_seen_at = ?, last_seen_at = ?, latest_evidence_id = ?,
                        node_names_json = ?
                    WHERE incident_id = ?
                    """,
                timestamp(firstSeen),
                timestamp(lastSeen),
                latestEvidenceId,
                json(nodeNames),
                locked.incidentId()
            );
            return latestJob(locked.incidentId()).orElseThrow();
        }

        RcaReport correlatedReport = report.withIncidentId(locked.incidentId());
        saveReport(correlatedReport);
        insertJob(job);
        jdbc.update(
            """
                UPDATE incidents SET occurrence_count = 1, last_seen_at = ?, latest_evidence_id = ?,
                    latest_report_id = ?, node_names_json = ?
                WHERE incident_id = ?
                """,
            timestamp(evidence.collectedAt()),
            evidence.evidenceId(),
            report.reportId(),
            json(appendNode(locked.nodeNames(), evidence.nodeName())),
            locked.incidentId()
        );
        return job;
    }

    public List<Incident> list(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            return jdbc.query("SELECT * FROM incidents ORDER BY last_seen_at DESC", this::mapIncident);
        }
        return jdbc.query(
            "SELECT * FROM incidents WHERE cluster_id = ? ORDER BY last_seen_at DESC",
            this::mapIncident,
            clusterId
        );
    }

    public CursorPage<Incident> page(
        String clusterId,
        IncidentStatus status,
        String query,
        String cursor,
        Integer limit
    ) {
        int safeLimit = CursorPageSupport.safeLimit(limit);
        CursorPageSupport.Cursor decodedCursor = CursorPageSupport.decode(cursor);
        String cleanQuery = CursorPageSupport.cleanQuery(query);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> filterArguments = new ArrayList<>();
        if (clusterId != null && !clusterId.isBlank()) {
            where.append(" AND cluster_id = ?");
            filterArguments.add(clusterId.trim());
        }
        if (status != null) {
            where.append(" AND status = ?");
            filterArguments.add(status.name());
        }
        if (cleanQuery != null) {
            String pattern = CursorPageSupport.likePattern(cleanQuery);
            where.append(
                " AND (LOWER(incident_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(cluster_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(node_name) LIKE ? ESCAPE '!'"
                    + " OR LOWER(alert_name) LIKE ? ESCAPE '!'"
                    + " OR LOWER(root_cause) LIKE ? ESCAPE '!')"
            );
            for (int index = 0; index < 5; index++) {
                filterArguments.add(pattern);
            }
        }
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM incidents" + where,
            Long.class,
            filterArguments.toArray()
        );
        StringBuilder pageWhere = new StringBuilder(where);
        List<Object> pageArguments = new ArrayList<>(filterArguments);
        if (decodedCursor != null) {
            pageWhere.append(" AND (last_seen_at < ? OR (last_seen_at = ? AND incident_id < ?))");
            Timestamp cursorTime = timestamp(decodedCursor.timestamp());
            pageArguments.add(cursorTime);
            pageArguments.add(cursorTime);
            pageArguments.add(decodedCursor.id());
        }
        pageArguments.add(safeLimit + 1);
        List<Incident> rows = jdbc.query(
            "SELECT * FROM incidents" + pageWhere
                + " ORDER BY last_seen_at DESC, incident_id DESC LIMIT ?",
            this::mapIncident,
            pageArguments.toArray()
        );
        return CursorPageSupport.page(
            rows,
            safeLimit,
            count == null ? 0 : count,
            Incident::lastSeenAt,
            Incident::incidentId
        );
    }

    public Optional<Incident> find(String incidentId) {
        return optionalQuery("SELECT * FROM incidents WHERE incident_id = ?", this::mapIncident, incidentId);
    }

    public Optional<Incident> updateStatus(String incidentId, IncidentStatus status) {
        return updateStatus(incidentId, status, "manual", "", Instant.now());
    }

    public Optional<Incident> updateStatus(
        String incidentId,
        IncidentStatus status,
        String source,
        String note,
        Instant changedAt
    ) {
        Instant effectiveChangedAt = changedAt == null ? Instant.now() : changedAt;
        int updated = status == IncidentStatus.resolved
            ? jdbc.update(
                """
                    UPDATE incidents
                    SET status = ?, dedup_key = ?, resolved_at = ?,
                        resolution_source = ?, resolution_note = ?
                    WHERE incident_id = ?
                    """,
                status.name(),
                id("resolved"),
                timestamp(effectiveChangedAt),
                normalizedLifecycleValue(source, "manual"),
                note == null ? "" : note,
                incidentId
            )
            : jdbc.update(
                """
                    UPDATE incidents
                    SET status = ?, resolved_at = NULL,
                        resolution_source = NULL, resolution_note = NULL
                    WHERE incident_id = ?
                    """,
                status.name(),
                incidentId
            );
        return updated == 0 ? Optional.empty() : find(incidentId);
    }

    public Optional<Incident> findByDedupKey(String dedupKey) {
        return optionalQuery("SELECT * FROM incidents WHERE dedup_key = ?", this::mapIncident, dedupKey);
    }

    public List<Incident> findRecentOpen(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to,
        int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
            """
                SELECT * FROM incidents
                WHERE cluster_id = ? AND node_name = ? AND status = ?
                  AND last_seen_at BETWEEN ? AND ?
                ORDER BY last_seen_at DESC
                LIMIT ?
                """,
            this::mapIncident,
            clusterId,
            nodeName,
            IncidentStatus.open.name(),
            timestamp(from),
            timestamp(to),
            safeLimit
        );
    }

    public List<Incident> findRecentOpenCluster(
        String clusterId,
        Instant from,
        Instant to,
        int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
            """
                SELECT * FROM incidents
                WHERE cluster_id = ? AND status = ?
                  AND last_seen_at BETWEEN ? AND ?
                ORDER BY last_seen_at DESC
                LIMIT ?
                """,
            this::mapIncident,
            clusterId,
            IncidentStatus.open.name(),
            timestamp(from),
            timestamp(to),
            safeLimit
        );
    }

    public List<Incident> findRecentResolved(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to,
        int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
            """
                SELECT * FROM incidents
                WHERE cluster_id = ? AND node_name = ? AND status = ?
                  AND resolved_at BETWEEN ? AND ?
                ORDER BY resolved_at DESC
                LIMIT ?
                """,
            this::mapIncident,
            clusterId,
            nodeName,
            IncidentStatus.resolved.name(),
            timestamp(from),
            timestamp(to),
            safeLimit
        );
    }

    @Transactional
    public List<Incident> resolveInactive(Instant inactiveBefore, Instant resolvedAt, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<Incident> candidates = jdbc.query(
            """
                SELECT i.* FROM incidents i
                WHERE i.status = ? AND i.last_seen_at < ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rca_reports r
                      JOIN action_requests ar ON ar.report_id = r.report_id
                      WHERE r.incident_id = i.incident_id
                        AND ar.status IN ('pending_approval', 'approved_manual', 'queued', 'executing')
                  )
                ORDER BY i.last_seen_at
                LIMIT ?
                """,
            this::mapIncident,
            IncidentStatus.open.name(),
            timestamp(inactiveBefore),
            safeLimit
        );
        List<Incident> resolved = new java.util.ArrayList<>();
        for (Incident candidate : candidates) {
            int updated = jdbc.update(
                """
                    UPDATE incidents
                    SET status = ?, dedup_key = ?, resolved_at = ?,
                        resolution_source = ?, resolution_note = ?
                    WHERE incident_id = ? AND status = ? AND last_seen_at = ?
                    """,
                IncidentStatus.resolved.name(),
                id("resolved"),
                timestamp(resolvedAt),
                "automatic",
                "No correlated evidence was observed before the inactivity threshold.",
                candidate.incidentId(),
                IncidentStatus.open.name(),
                timestamp(candidate.lastSeenAt())
            );
            if (updated == 1) {
                find(candidate.incidentId()).ifPresent(resolved::add);
            }
        }
        return List.copyOf(resolved);
    }

    @Transactional
    public List<Incident> resolveBySignal(
        String clusterId,
        String nodeName,
        String alertName,
        Instant resolvedAt,
        String source,
        String note
    ) {
        List<Incident> candidates = jdbc.query(
            """
                SELECT * FROM incidents
                WHERE cluster_id = ? AND node_name = ? AND alert_name = ? AND status = ?
                ORDER BY last_seen_at DESC
                """,
            this::mapIncident,
            clusterId,
            nodeName,
            alertName,
            IncidentStatus.open.name()
        );
        List<Incident> resolved = new java.util.ArrayList<>();
        for (Incident candidate : candidates) {
            int updated = jdbc.update(
                """
                    UPDATE incidents
                    SET status = ?, dedup_key = ?, resolved_at = ?,
                        resolution_source = ?, resolution_note = ?
                    WHERE incident_id = ? AND status = ?
                    """,
                IncidentStatus.resolved.name(),
                id("resolved"),
                timestamp(resolvedAt),
                normalizedLifecycleValue(source, "external"),
                note == null ? "" : note,
                candidate.incidentId(),
                IncidentStatus.open.name()
            );
            if (updated == 1) {
                find(candidate.incidentId()).ifPresent(resolved::add);
            }
        }
        return List.copyOf(resolved);
    }

    public Optional<RcaJob> latestJob(String incidentId) {
        return optionalQuery(
            """
                SELECT j.* FROM rca_jobs j
                JOIN rca_reports r ON r.report_id = j.report_id
                WHERE r.incident_id = ?
                ORDER BY j.created_at DESC
                LIMIT 1
                """,
            this::mapJob,
            incidentId
        );
    }

    private Incident createIncident(
        String dedupKey,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        RcaReport report,
        EvidenceBundle evidence
    ) {
        Incident created = new Incident(
            id("incident"),
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            report.summary().mostLikelyCause(),
            IncidentStatus.open,
            0,
            evidence.collectedAt(),
            evidence.collectedAt(),
            evidence.evidenceId(),
            null,
            null,
            null,
            null,
            recurrenceOfIncidentId,
            Math.max(0, recurrenceSequence)
        );
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at, latest_evidence_id, latest_report_id,
                     recurrence_of_incident_id, recurrence_sequence, node_names_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            created.incidentId(),
            dedupKey,
            created.clusterId(),
            created.nodeName(),
            created.alertName(),
            created.rootCause(),
            created.status().name(),
            created.occurrenceCount(),
            timestamp(created.firstSeenAt()),
            timestamp(created.lastSeenAt()),
            created.latestEvidenceId(),
            null,
            created.recurrenceOfIncidentId(),
            created.recurrenceSequence(),
            json(created.nodeNames())
        );
        return created;
    }

    private void saveReport(RcaReport report) {
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

    private Incident mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Incident(
            resultSet.getString("incident_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            resultSet.getString("root_cause"),
            IncidentStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("occurrence_count"),
            instant(resultSet, "first_seen_at"),
            instant(resultSet, "last_seen_at"),
            resultSet.getString("latest_evidence_id"),
            resultSet.getString("latest_report_id"),
            instant(resultSet, "resolved_at"),
            resultSet.getString("resolution_source"),
            resultSet.getString("resolution_note"),
            resultSet.getString("recurrence_of_incident_id"),
            resultSet.getInt("recurrence_sequence"),
            read(
                resultSet.getString("node_names_json"),
                STRING_LIST,
                List.of(resultSet.getString("node_name"))
            )
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

    private List<String> appendNode(List<String> existing, String nodeName) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>(existing == null ? List.of() : existing);
        if (nodeName != null && !nodeName.isBlank()) {
            nodes.add(nodeName);
        }
        return List.copyOf(nodes);
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

    private static String normalizedLifecycleValue(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : limitedText(normalized, 32);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limitedText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
