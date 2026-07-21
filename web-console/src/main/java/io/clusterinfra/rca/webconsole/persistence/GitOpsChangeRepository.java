package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsDeploymentState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GitOpsChangeRepository {
    private final JdbcTemplate jdbc;

    public GitOpsChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PendingClaim createPending(
        String sourceType,
        String sourceId,
        String provider,
        String repository,
        String branch,
        String baseBranch,
        String filePath,
        String requestedBy
    ) {
        Instant now = now();
        GitOpsChange change = new GitOpsChange(
            id(), sourceType, sourceId, provider, repository, branch, baseBranch, filePath,
            null, null, GitOpsChangeState.creating, null, GitOpsDeploymentState.pending,
            null, null, null, 0, now, null, null, blankToNull(requestedBy), now, now, null, null
        );
        try {
            jdbc.update(
                """
                    INSERT INTO gitops_changes
                        (change_id, source_type, source_id, provider, repository, branch_name, base_branch,
                         file_path, pull_request_number, pull_request_url, pull_request_state, head_sha,
                         deployment_state, verification_result, rollback_reference, error_message,
                         retry_count, last_attempt_at, last_failure_at, last_reconciled_at,
                         requested_by, created_at, updated_at, deployment_started_at, deployment_completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                change.changeId(), change.sourceType(), change.sourceId(), change.provider(), change.repository(),
                change.branch(), change.baseBranch(), change.filePath(), null, null,
                change.pullRequestState().name(), null, change.deploymentState().name(), null, null, null,
                0, timestamp(now), null, null,
                change.requestedBy(), timestamp(now), timestamp(now), null, null
            );
            return new PendingClaim(change, true);
        } catch (DuplicateKeyException exception) {
            return new PendingClaim(
                findBySource(sourceType, sourceId, provider).orElseThrow(() -> exception),
                false
            );
        }
    }

    public Optional<GitOpsChange> find(String changeId) {
        return optional("SELECT * FROM gitops_changes WHERE change_id = ?", changeId);
    }

    public Optional<GitOpsChange> findBySource(String sourceType, String sourceId, String provider) {
        return optional(
            "SELECT * FROM gitops_changes WHERE source_type = ? AND source_id = ? AND provider = ?",
            sourceType, sourceId, provider
        );
    }

    public Optional<GitOpsChange> findByPullRequest(String provider, String repository, long pullRequestNumber) {
        return optional(
            "SELECT * FROM gitops_changes WHERE provider = ? AND repository = ? AND pull_request_number = ?",
            provider, repository, pullRequestNumber
        );
    }

    public List<GitOpsChange> list(String sourceType, String sourceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (sourceType != null && !sourceType.isBlank() && sourceId != null && !sourceId.isBlank()) {
            return jdbc.query(
                "SELECT * FROM gitops_changes WHERE source_type = ? AND source_id = ? ORDER BY created_at DESC LIMIT ?",
                this::map, sourceType, sourceId, safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM gitops_changes ORDER BY created_at DESC LIMIT ?",
            this::map, safeLimit
        );
    }

    public GitOpsChange markOpened(
        String changeId,
        long pullRequestNumber,
        String pullRequestUrl,
        String headSha,
        String state
    ) {
        GitOpsChangeState normalized = normalizePullRequestState(state, false);
        jdbc.update(
            """
                UPDATE gitops_changes
                   SET pull_request_number = ?, pull_request_url = ?, pull_request_state = ?, head_sha = ?,
                       error_message = NULL, last_reconciled_at = ?, updated_at = ?
                 WHERE change_id = ? AND pull_request_state IN (?, ?)
                """,
            pullRequestNumber, blankToNull(pullRequestUrl), normalized.name(), blankToNull(headSha),
            timestamp(now()), timestamp(now()), changeId,
            GitOpsChangeState.creating.name(), GitOpsChangeState.reconciling.name()
        );
        return find(changeId).orElseThrow();
    }

    public GitOpsChange markFailed(String changeId, String message) {
        jdbc.update(
            """
                UPDATE gitops_changes
                   SET pull_request_state = ?, error_message = ?, last_failure_at = ?, updated_at = ?
                 WHERE change_id = ? AND pull_request_state IN (?, ?)
                """,
            GitOpsChangeState.failed.name(), truncate(message, 4000), timestamp(now()), timestamp(now()), changeId,
            GitOpsChangeState.creating.name(), GitOpsChangeState.reconciling.name()
        );
        return find(changeId).orElseThrow();
    }

    public RetryClaim claimRetry(String changeId) {
        Instant current = now();
        int updated = jdbc.update(
            """
                UPDATE gitops_changes
                   SET pull_request_state = ?, retry_count = retry_count + 1,
                       last_attempt_at = ?, error_message = NULL, updated_at = ?
                 WHERE change_id = ? AND pull_request_state = ?
                """,
            GitOpsChangeState.reconciling.name(), timestamp(current), timestamp(current),
            changeId, GitOpsChangeState.failed.name()
        );
        return new RetryClaim(find(changeId).orElseThrow(), updated == 1);
    }

    public Optional<GitOpsChange> syncPullRequest(
        String provider,
        String repository,
        long pullRequestNumber,
        String state,
        boolean merged,
        String pullRequestUrl,
        String headSha
    ) {
        GitOpsChangeState normalized = normalizePullRequestState(state, merged);
        int updated = jdbc.update(
            """
                UPDATE gitops_changes
                   SET pull_request_state = ?, pull_request_url = ?, head_sha = ?, updated_at = ?
                 WHERE provider = ? AND repository = ? AND pull_request_number = ?
                """,
            normalized.name(), blankToNull(pullRequestUrl), blankToNull(headSha), timestamp(now()),
            provider, repository, pullRequestNumber
        );
        return updated == 0 ? Optional.empty() : findByPullRequest(provider, repository, pullRequestNumber);
    }

    public Optional<GitOpsChange> updateOutcome(
        String changeId,
        GitOpsDeploymentState deploymentState,
        String verificationResult,
        String rollbackReference
    ) {
        Instant current = now();
        Instant startedAt = deploymentState == GitOpsDeploymentState.in_progress ? current : null;
        Instant completedAt = switch (deploymentState) {
            case succeeded, failed, rolled_back -> current;
            default -> null;
        };
        int updated = jdbc.update(
            """
                UPDATE gitops_changes
                   SET deployment_state = ?, verification_result = ?, rollback_reference = ?,
                       deployment_started_at = COALESCE(deployment_started_at, ?),
                       deployment_completed_at = ?, updated_at = ?
                 WHERE change_id = ?
                """,
            deploymentState.name(), blankToNull(verificationResult), blankToNull(rollbackReference),
            timestamp(startedAt), timestamp(completedAt), timestamp(current), changeId
        );
        return updated == 0 ? Optional.empty() : find(changeId);
    }

    public boolean claimWebhookDelivery(String deliveryId, String provider, String eventType) {
        try {
            jdbc.update(
                "INSERT INTO gitops_webhook_deliveries (delivery_id, provider, event_type, received_at) VALUES (?, ?, ?, ?)",
                deliveryId, provider, eventType, timestamp(now())
            );
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private Optional<GitOpsChange> optional(String sql, Object... parameters) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::map, parameters));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private GitOpsChange map(ResultSet resultSet, int rowNumber) throws SQLException {
        long number = resultSet.getLong("pull_request_number");
        return new GitOpsChange(
            resultSet.getString("change_id"),
            resultSet.getString("source_type"),
            resultSet.getString("source_id"),
            resultSet.getString("provider"),
            resultSet.getString("repository"),
            resultSet.getString("branch_name"),
            resultSet.getString("base_branch"),
            resultSet.getString("file_path"),
            resultSet.wasNull() ? null : number,
            resultSet.getString("pull_request_url"),
            GitOpsChangeState.valueOf(resultSet.getString("pull_request_state")),
            resultSet.getString("head_sha"),
            GitOpsDeploymentState.valueOf(resultSet.getString("deployment_state")),
            resultSet.getString("verification_result"),
            resultSet.getString("rollback_reference"),
            resultSet.getString("error_message"),
            resultSet.getInt("retry_count"),
            instant(resultSet, "last_attempt_at"),
            instant(resultSet, "last_failure_at"),
            instant(resultSet, "last_reconciled_at"),
            resultSet.getString("requested_by"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "deployment_started_at"),
            instant(resultSet, "deployment_completed_at")
        );
    }

    private GitOpsChangeState normalizePullRequestState(String state, boolean merged) {
        if (merged || "merged".equalsIgnoreCase(state)) {
            return GitOpsChangeState.merged;
        }
        return "closed".equalsIgnoreCase(state) ? GitOpsChangeState.closed : GitOpsChangeState.open;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private String id() {
        return "gitops-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "GitOps provider request failed";
        }
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    public record PendingClaim(GitOpsChange change, boolean claimed) {
    }

    public record RetryClaim(GitOpsChange change, boolean claimed) {
    }
}
