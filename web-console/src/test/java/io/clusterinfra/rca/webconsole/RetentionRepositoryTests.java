package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.persistence.RetentionRepository;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository.RetentionCutoffs;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetentionRepositoryTests {
    private JdbcTemplate jdbc;
    private RetentionRepository repository;
    private Instant now;
    private Instant old;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:retention-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new RetentionRepository(jdbc);
        now = Instant.now();
        old = now.minus(60, ChronoUnit.DAYS);
    }

    @Test
    void deletesExpiredResolvedIncidentChainInForeignKeySafeOrder() {
        seedIncidentChain("resolved", "completed", "completed");
        seedExpiredSessionAndAudit();

        var result = repository.cleanup(cutoffs(), 100);

        assertThat(result.totalDeleted()).isEqualTo(11);
        assertThat(result.incidents()).isEqualTo(1);
        assertThat(result.rcaReports()).isEqualTo(1);
        assertThat(result.actionRequests()).isEqualTo(1);
        assertThat(result.actionExecutions()).isEqualTo(1);
        assertThat(result.evidenceBundles()).isEqualTo(1);
        assertThat(count("incidents")).isZero();
        assertThat(count("rca_reports")).isZero();
        assertThat(count("evidence_bundles")).isZero();
        assertThat(count("audit_events")).isZero();
        assertThat(count("user_sessions")).isZero();
    }

    @Test
    void preservesResolvedIncidentWhileActionApprovalIsActive() {
        seedIncidentChain("resolved", "pending_approval", "pending_approval");

        var result = repository.cleanup(cutoffs(), 100);

        assertThat(result.incidents()).isZero();
        assertThat(result.rcaReports()).isZero();
        assertThat(result.actionRequests()).isZero();
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("rca_reports")).isEqualTo(1);
        assertThat(count("action_requests")).isEqualTo(1);
        assertThat(count("evidence_bundles")).isEqualTo(1);
    }

    @Test
    void neverDeletesOpenIncidentHistory() {
        seedIncidentChain("open", "completed", "completed");

        var result = repository.cleanup(cutoffs(), 100);

        assertThat(result.incidents()).isZero();
        assertThat(result.rcaReports()).isZero();
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("rca_reports")).isEqualTo(1);
    }

    @Test
    void preservesResolvedIncidentReferencedByRecurrence() {
        seedIncidentChain("resolved", "completed", "completed");
        Timestamp timestamp = Timestamp.from(old.plus(1, ChronoUnit.DAYS));
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at, recurrence_of_incident_id,
                     recurrence_sequence)
                VALUES ('incident-2', 'dedup-2', 'cluster-1', 'node-1', 'DiskPressure',
                        'disk full again', 'open', 1, ?, ?, 'incident-1', 1)
                """,
            timestamp,
            timestamp
        );

        var result = repository.cleanup(cutoffs(), 100);

        assertThat(result.incidents()).isZero();
        assertThat(count("incidents")).isEqualTo(2);
        assertThat(count("rca_reports")).isEqualTo(1);
    }

    private void seedIncidentChain(
        String incidentStatus,
        String actionRequestStatus,
        String actionExecutionStatus
    ) {
        Timestamp timestamp = Timestamp.from(old);
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token, created_at, last_seen_at)
                VALUES ('cluster-1', 'retention', 'test', '', 'active', 'token', ?, ?)
                """,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES ('evidence-1', 'cluster-1', 'node-1', 'DiskPressure', '{}', ?)
                """,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO evidence_requests
                    (request_id, cluster_id, node_name, alert_name, requested_collectors_json, status,
                     time_range_json, reason, context_json, evidence_id, error_message, created_at, completed_at)
                VALUES ('request-1', 'cluster-1', 'node-1', 'DiskPressure', '[]', 'completed',
                        '{}', '', '{}', 'evidence-1', NULL, ?, ?)
                """,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at, latest_evidence_id, latest_report_id)
                VALUES ('incident-1', 'dedup-1', 'cluster-1', 'node-1', 'DiskPressure', 'disk full', ?,
                        1, ?, ?, 'evidence-1', 'report-1')
                """,
            incidentStatus,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, status, trigger_json, scope_json, summary_json, evidence_json,
                     root_cause_candidates_json, recommended_actions_json, policy_decisions_json,
                     created_at, incident_id)
                VALUES ('report-1', 'cluster-1', 'completed', '{}', '{}', '{}', '{}',
                        '[]', '[]', '[]', ?, 'incident-1')
                """,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO rca_jobs
                    (job_id, cluster_id, alert_name, node_name, status, report_id, evidence_id, created_at)
                VALUES ('job-1', 'cluster-1', 'DiskPressure', 'node-1', 'completed',
                        'report-1', 'evidence-1', ?)
                """,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO rca_analysis_tasks
                    (task_id, evidence_id, cluster_id, node_name, alert_name, source, skip_if_healthy,
                     status, attempt_count, max_attempts, next_attempt_at, lease_owner, lease_expires_at,
                     last_error, report_id, job_id, created_at, started_at, completed_at)
                VALUES ('task-1', 'evidence-1', 'cluster-1', 'node-1', 'DiskPressure', 'test', 0,
                        'completed', 1, 3, ?, NULL, NULL, NULL, 'report-1', 'job-1', ?, ?, ?)
                """,
            timestamp,
            timestamp,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO action_requests
                    (action_request_id, report_id, action_index, action_key, policy, source, status,
                     requested_by, reviewed_by, request_note, decision_note, evidence_request_id,
                     created_at, reviewed_at)
                VALUES ('action-1', 'report-1', 0, 'inspect-disk', 'APPROVAL_REQUIRED', 'rule', ?,
                        'operator', 'approver', '', '', 'request-1', ?, ?)
                """,
            actionRequestStatus,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO action_executions
                    (execution_id, action_request_id, report_id, cluster_id, node_name, action_key,
                     command_key, parameters_json, preview_json, status, timeout_seconds, requested_by,
                     approved_by, lease_owner, lease_expires_at, exit_code, stdout_text, stderr_text,
                     error_message, created_at, approved_at, started_at, completed_at)
                VALUES ('execution-1', 'action-1', 'report-1', 'cluster-1', 'node-1', 'inspect-disk',
                        'inspect-disk', '{}', '{}', ?, 30, 'operator', 'approver', NULL, NULL,
                        0, '', '', NULL, ?, ?, ?, ?)
                """,
            actionExecutionStatus,
            timestamp,
            timestamp,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO realtime_events
                    (event_id, evidence_id, cluster_id, node_name, event_type, component, severity,
                     observed_at, payload_json, created_at)
                VALUES ('event-1', 'evidence-1', 'cluster-1', 'node-1', 'oom_kill', 'kernel',
                        'critical', ?, '{}', ?)
                """,
            timestamp,
            timestamp
        );
    }

    private void seedExpiredSessionAndAudit() {
        Timestamp timestamp = Timestamp.from(old);
        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES ('user-1', 'admin', 'Admin', 'hash', 'admin', 'admin', 'active',
                        '', '', 'system', ?, ?)
                """,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO user_sessions
                    (session_id, user_id, token_hash, created_at, expires_at, revoked_at)
                VALUES ('session-1', 'user-1', 'hash', ?, ?, NULL)
                """,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
                INSERT INTO audit_events
                    (audit_event_id, actor_type, actor_id, event_type, resource_type,
                     resource_id, outcome, details_json, created_at)
                VALUES ('audit-1', 'system', 'test', 'old', 'platform', 'retention',
                        'completed', '{}', ?)
                """,
            timestamp
        );
    }

    private RetentionCutoffs cutoffs() {
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);
        return new RetentionCutoffs(now, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
