package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisTaskRepository {
    private final JdbcTemplate jdbc;

    public AnalysisTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AnalysisTask enqueue(EvidenceBundle evidence, String source, boolean skipIfHealthy, int maxAttempts) {
        Optional<AnalysisTask> existing = findByEvidence(evidence.evidenceId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = databaseInstant();
        AnalysisTask task = new AnalysisTask(
            id("analysis"),
            evidence.evidenceId(),
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            blankToNull(source) == null ? "unknown" : source.trim(),
            skipIfHealthy,
            AnalysisTaskStatus.queued,
            0,
            Math.max(1, Math.min(maxAttempts, 20)),
            now,
            null,
            null,
            null,
            null,
            null,
            now,
            null,
            null
        );
        jdbc.update(
            """
                INSERT INTO rca_analysis_tasks
                    (task_id, evidence_id, cluster_id, node_name, alert_name, source, skip_if_healthy,
                     status, attempt_count, max_attempts, next_attempt_at, lease_owner, lease_expires_at,
                     last_error, report_id, job_id, created_at, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            task.taskId(),
            task.evidenceId(),
            task.clusterId(),
            task.nodeName(),
            task.alertName(),
            task.source(),
            task.skipIfHealthy() ? 1 : 0,
            task.status().name(),
            task.attemptCount(),
            task.maxAttempts(),
            timestamp(task.nextAttemptAt()),
            null,
            null,
            null,
            null,
            null,
            timestamp(task.createdAt()),
            null,
            null
        );
        return task;
    }

    public Optional<AnalysisTask> find(String taskId) {
        return optionalQuery(
            "SELECT * FROM rca_analysis_tasks WHERE task_id = ?",
            this::mapAnalysisTask,
            taskId
        );
    }

    public Optional<AnalysisTask> findByEvidence(String evidenceId) {
        return optionalQuery(
            "SELECT * FROM rca_analysis_tasks WHERE evidence_id = ?",
            this::mapAnalysisTask,
            evidenceId
        );
    }

    public List<AnalysisTask> list(AnalysisTaskStatus status, Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        if (status == null) {
            return jdbc.query(
                "SELECT * FROM rca_analysis_tasks ORDER BY created_at DESC LIMIT ?",
                this::mapAnalysisTask,
                safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM rca_analysis_tasks WHERE status = ? ORDER BY created_at DESC LIMIT ?",
            this::mapAnalysisTask,
            status.name(),
            safeLimit
        );
    }

    public CursorPage<AnalysisTask> page(
        String clusterId,
        AnalysisTaskStatus status,
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
                " AND (LOWER(task_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(evidence_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(cluster_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(COALESCE(node_name, '')) LIKE ? ESCAPE '!'"
                    + " OR LOWER(COALESCE(alert_name, '')) LIKE ? ESCAPE '!'"
                    + " OR LOWER(source) LIKE ? ESCAPE '!'"
                    + " OR LOWER(COALESCE(last_error, '')) LIKE ? ESCAPE '!')"
            );
            for (int index = 0; index < 7; index++) {
                filterArguments.add(pattern);
            }
        }
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_analysis_tasks" + where,
            Long.class,
            filterArguments.toArray()
        );
        StringBuilder pageWhere = new StringBuilder(where);
        List<Object> pageArguments = new ArrayList<>(filterArguments);
        if (decodedCursor != null) {
            pageWhere.append(" AND (created_at < ? OR (created_at = ? AND task_id < ?))");
            Timestamp cursorTime = timestamp(decodedCursor.timestamp());
            pageArguments.add(cursorTime);
            pageArguments.add(cursorTime);
            pageArguments.add(decodedCursor.id());
        }
        pageArguments.add(safeLimit + 1);
        List<AnalysisTask> rows = jdbc.query(
            "SELECT * FROM rca_analysis_tasks" + pageWhere
                + " ORDER BY created_at DESC, task_id DESC LIMIT ?",
            this::mapAnalysisTask,
            pageArguments.toArray()
        );
        return CursorPageSupport.page(
            rows,
            safeLimit,
            count == null ? 0 : count,
            AnalysisTask::createdAt,
            AnalysisTask::taskId
        );
    }

    public long count(AnalysisTaskStatus status) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_analysis_tasks WHERE status = ?",
            Long.class,
            status.name()
        );
        return count == null ? 0 : count;
    }

    public List<AnalysisTask> claim(
        String leaseOwner,
        int limit,
        Instant now,
        Instant leaseExpiresAt
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<String> candidates = jdbc.queryForList(
            """
                SELECT task_id FROM rca_analysis_tasks
                WHERE ((status IN (?, ?) AND next_attempt_at <= ?)
                    OR (status = ? AND lease_expires_at < ?))
                ORDER BY next_attempt_at, created_at
                LIMIT ?
                """,
            String.class,
            AnalysisTaskStatus.queued.name(),
            AnalysisTaskStatus.retry_wait.name(),
            timestamp(now),
            AnalysisTaskStatus.processing.name(),
            timestamp(now),
            safeLimit * 3
        );
        List<AnalysisTask> claimed = new ArrayList<>();
        for (String taskId : candidates) {
            int updated = jdbc.update(
                """
                    UPDATE rca_analysis_tasks
                    SET status = ?, attempt_count = attempt_count + 1, lease_owner = ?,
                        lease_expires_at = ?, started_at = COALESCE(started_at, ?), last_error = NULL
                    WHERE task_id = ? AND ((status IN (?, ?) AND next_attempt_at <= ?)
                        OR (status = ? AND lease_expires_at < ?))
                    """,
                AnalysisTaskStatus.processing.name(),
                leaseOwner,
                timestamp(leaseExpiresAt),
                timestamp(now),
                taskId,
                AnalysisTaskStatus.queued.name(),
                AnalysisTaskStatus.retry_wait.name(),
                timestamp(now),
                AnalysisTaskStatus.processing.name(),
                timestamp(now)
            );
            if (updated == 1) {
                find(taskId).ifPresent(claimed::add);
            }
            if (claimed.size() >= safeLimit) {
                break;
            }
        }
        return claimed;
    }

    public boolean complete(
        AnalysisTask task,
        String leaseOwner,
        AnalysisTaskStatus status,
        String reportId,
        String jobId,
        Instant completedAt
    ) {
        if (status != AnalysisTaskStatus.completed && status != AnalysisTaskStatus.skipped) {
            throw new IllegalArgumentException("analysis task completion status is invalid");
        }
        return jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, report_id = ?, job_id = ?, lease_owner = NULL, lease_expires_at = NULL,
                    next_attempt_at = ?, completed_at = ?
                WHERE task_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            status.name(),
            reportId,
            jobId,
            timestamp(completedAt),
            timestamp(completedAt),
            task.taskId(),
            AnalysisTaskStatus.processing.name(),
            leaseOwner,
            task.attemptCount()
        ) == 1;
    }

    public boolean renewLease(
        AnalysisTask task,
        String leaseOwner,
        Instant leaseExpiresAt
    ) {
        return jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET lease_expires_at = ?
                WHERE task_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            timestamp(leaseExpiresAt),
            task.taskId(),
            AnalysisTaskStatus.processing.name(),
            leaseOwner,
            task.attemptCount()
        ) == 1;
    }

    public boolean fail(AnalysisTask task, String leaseOwner, String error, Instant nextAttemptAt) {
        boolean exhausted = task.attemptCount() >= task.maxAttempts();
        AnalysisTaskStatus status = exhausted
            ? AnalysisTaskStatus.dead_letter
            : AnalysisTaskStatus.retry_wait;
        Instant completedAt = exhausted ? databaseInstant() : null;
        return jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                    last_error = ?, completed_at = ?
                WHERE task_id = ? AND status = ? AND lease_owner = ? AND attempt_count = ?
                """,
            status.name(),
            timestamp(nextAttemptAt),
            error,
            timestamp(completedAt),
            task.taskId(),
            AnalysisTaskStatus.processing.name(),
            leaseOwner,
            task.attemptCount()
        ) == 1;
    }

    public Optional<AnalysisTask> retry(String taskId) {
        Instant now = databaseInstant();
        int updated = jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, attempt_count = 0, next_attempt_at = ?, lease_owner = NULL,
                    lease_expires_at = NULL, last_error = NULL, started_at = NULL, completed_at = NULL
                WHERE task_id = ? AND status = ?
                """,
            AnalysisTaskStatus.queued.name(),
            timestamp(now),
            taskId,
            AnalysisTaskStatus.dead_letter.name()
        );
        return updated == 0 ? Optional.empty() : find(taskId);
    }

    private AnalysisTask mapAnalysisTask(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AnalysisTask(
            resultSet.getString("task_id"),
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            resultSet.getString("source"),
            resultSet.getInt("skip_if_healthy") != 0,
            AnalysisTaskStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("max_attempts"),
            instant(resultSet, "next_attempt_at"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_expires_at"),
            resultSet.getString("last_error"),
            resultSet.getString("report_id"),
            resultSet.getString("job_id"),
            instant(resultSet, "created_at"),
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
