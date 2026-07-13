package io.clusterinfra.rca.webconsole.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RetentionRepository {
    private static final List<String> ACTIVE_ACTION_REQUEST_STATUSES = List.of(
        "pending_approval",
        "approved_manual",
        "accepted",
        "queued",
        "executing"
    );
    private static final List<String> ACTIVE_ACTION_EXECUTION_STATUSES = List.of(
        "pending_approval",
        "queued",
        "leased"
    );

    private final JdbcTemplate jdbc;

    public RetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CleanupResult cleanup(RetentionCutoffs cutoffs, int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 1000));
        MutableResult result = new MutableResult();

        cleanupResolvedIncidents(cutoffs.reportCutoff(), batchSize, result);
        int remainingReportBudget = Math.max(0, batchSize - result.rcaReports);
        if (remainingReportBudget > 0) {
            cleanupStandaloneReports(cutoffs.reportCutoff(), remainingReportBudget, result);
        }
        cleanupRealtimeEvents(cutoffs.realtimeEventCutoff(), batchSize, result);
        cleanupTopologyObservations(cutoffs.topologyObservationCutoff(), batchSize, result);
        cleanupTerminalAnalysisTasks(cutoffs.analysisTaskCutoff(), batchSize, result);
        cleanupEvidenceRequests(cutoffs.evidenceRequestCutoff(), batchSize, result);
        cleanupOrphanEvidence(cutoffs.evidenceCutoff(), batchSize, result);
        cleanupExpiredSessions(cutoffs.now(), batchSize, result);
        cleanupGitOpsWebhookDeliveries(cutoffs.auditCutoff(), batchSize, result);
        cleanupAuditEvents(cutoffs.auditCutoff(), batchSize, result);

        return result.freeze();
    }

    private void cleanupResolvedIncidents(Instant cutoff, int limit, MutableResult result) {
        List<String> incidentIds = ids(
            """
                SELECT i.incident_id
                FROM incidents i
                WHERE i.status = 'resolved'
                  AND i.last_seen_at < ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rca_reports r
                      JOIN action_requests a ON a.report_id = r.report_id
                      WHERE r.incident_id = i.incident_id
                        AND a.status IN (?, ?, ?, ?, ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rca_reports r
                      JOIN action_executions x ON x.report_id = r.report_id
                      WHERE r.incident_id = i.incident_id
                        AND x.status IN (?, ?, ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM incidents child
                      WHERE child.recurrence_of_incident_id = i.incident_id
                  )
                ORDER BY i.last_seen_at
                LIMIT ?
                """,
            timestamp(cutoff),
            ACTIVE_ACTION_REQUEST_STATUSES.get(0),
            ACTIVE_ACTION_REQUEST_STATUSES.get(1),
            ACTIVE_ACTION_REQUEST_STATUSES.get(2),
            ACTIVE_ACTION_REQUEST_STATUSES.get(3),
            ACTIVE_ACTION_REQUEST_STATUSES.get(4),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(0),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(1),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(2),
            limit
        );
        int remainingReportBudget = limit;
        for (String incidentId : incidentIds) {
            List<String> reportIds = ids(
                """
                    SELECT report_id FROM rca_reports
                    WHERE incident_id = ?
                    ORDER BY created_at
                    LIMIT ?
                    """,
                incidentId,
                remainingReportBudget + 1
            );
            if (reportIds.size() > remainingReportBudget) {
                continue;
            }
            for (String reportId : reportIds) {
                cleanupReport(reportId, result);
            }
            result.incidents += jdbc.update("DELETE FROM incidents WHERE incident_id = ?", incidentId);
            remainingReportBudget -= reportIds.size();
            if (remainingReportBudget == 0) {
                break;
            }
        }
    }

    private void cleanupStandaloneReports(Instant cutoff, int limit, MutableResult result) {
        List<String> reportIds = ids(
            """
                SELECT r.report_id
                FROM rca_reports r
                WHERE r.incident_id IS NULL
                  AND r.created_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM action_requests a
                      WHERE a.report_id = r.report_id
                        AND a.status IN (?, ?, ?, ?, ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM action_executions x
                      WHERE x.report_id = r.report_id
                        AND x.status IN (?, ?, ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM incidents i
                      WHERE i.latest_report_id = r.report_id
                  )
                ORDER BY r.created_at
                LIMIT ?
                """,
            timestamp(cutoff),
            ACTIVE_ACTION_REQUEST_STATUSES.get(0),
            ACTIVE_ACTION_REQUEST_STATUSES.get(1),
            ACTIVE_ACTION_REQUEST_STATUSES.get(2),
            ACTIVE_ACTION_REQUEST_STATUSES.get(3),
            ACTIVE_ACTION_REQUEST_STATUSES.get(4),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(0),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(1),
            ACTIVE_ACTION_EXECUTION_STATUSES.get(2),
            limit
        );
        reportIds.forEach(reportId -> cleanupReport(reportId, result));
    }

    private void cleanupReport(String reportId, MutableResult result) {
        List<String> jobIds = ids("SELECT job_id FROM rca_jobs WHERE report_id = ?", reportId);
        for (String jobId : jobIds) {
            result.analysisTasks += jdbc.update(
                "DELETE FROM rca_analysis_tasks WHERE job_id = ?",
                jobId
            );
        }
        result.analysisTasks += jdbc.update(
            "DELETE FROM rca_analysis_tasks WHERE report_id = ?",
            reportId
        );
        result.actionExecutions += jdbc.update(
            "DELETE FROM action_executions WHERE report_id = ?",
            reportId
        );
        result.actionRequests += jdbc.update(
            "DELETE FROM action_requests WHERE report_id = ?",
            reportId
        );
        result.rcaJobs += jdbc.update("DELETE FROM rca_jobs WHERE report_id = ?", reportId);
        result.rcaReports += jdbc.update("DELETE FROM rca_reports WHERE report_id = ?", reportId);
    }

    private void cleanupRealtimeEvents(Instant cutoff, int limit, MutableResult result) {
        for (String eventId : ids(
            """
                SELECT event_id FROM realtime_events
                WHERE observed_at < ?
                ORDER BY observed_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.realtimeEvents += jdbc.update(
                "DELETE FROM realtime_events WHERE event_id = ?",
                eventId
            );
        }
    }

    private void cleanupTerminalAnalysisTasks(Instant cutoff, int limit, MutableResult result) {
        for (String taskId : ids(
            """
                SELECT task_id FROM rca_analysis_tasks
                WHERE status IN ('completed', 'skipped', 'dead_letter')
                  AND COALESCE(completed_at, created_at) < ?
                  AND report_id IS NULL
                  AND job_id IS NULL
                ORDER BY created_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.analysisTasks += jdbc.update(
                "DELETE FROM rca_analysis_tasks WHERE task_id = ?",
                taskId
            );
        }
    }

    private void cleanupTopologyObservations(Instant cutoff, int limit, MutableResult result) {
        for (String observationId : ids(
            """
                SELECT observation_id FROM topology_observations
                WHERE observed_at < ?
                ORDER BY observed_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.topologyObservations += jdbc.update(
                "DELETE FROM topology_observations WHERE observation_id = ?",
                observationId
            );
        }
    }

    private void cleanupEvidenceRequests(Instant cutoff, int limit, MutableResult result) {
        for (String requestId : ids(
            """
                SELECT q.request_id
                FROM evidence_requests q
                WHERE q.status IN ('completed', 'failed')
                  AND COALESCE(q.completed_at, q.created_at) < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM action_requests a
                      WHERE a.evidence_request_id = q.request_id
                  )
                ORDER BY q.created_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.evidenceRequests += jdbc.update(
                "DELETE FROM evidence_requests WHERE request_id = ?",
                requestId
            );
        }
    }

    private void cleanupOrphanEvidence(Instant cutoff, int limit, MutableResult result) {
        for (String evidenceId : ids(
            """
                SELECT e.evidence_id
                FROM evidence_bundles e
                WHERE e.collected_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM evidence_requests q WHERE q.evidence_id = e.evidence_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM rca_jobs j WHERE j.evidence_id = e.evidence_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM rca_analysis_tasks t WHERE t.evidence_id = e.evidence_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM realtime_events v WHERE v.evidence_id = e.evidence_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM incidents i WHERE i.latest_evidence_id = e.evidence_id
                  )
                ORDER BY e.collected_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.evidenceBundles += jdbc.update(
                "DELETE FROM evidence_bundles WHERE evidence_id = ?",
                evidenceId
            );
        }
    }

    private void cleanupExpiredSessions(Instant cutoff, int limit, MutableResult result) {
        for (String sessionId : ids(
            """
                SELECT session_id FROM user_sessions
                WHERE expires_at < ?
                ORDER BY expires_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.userSessions += jdbc.update(
                "DELETE FROM user_sessions WHERE session_id = ?",
                sessionId
            );
        }
    }

    private void cleanupAuditEvents(Instant cutoff, int limit, MutableResult result) {
        for (String eventId : ids(
            """
                SELECT audit_event_id FROM audit_events
                WHERE created_at < ?
                ORDER BY created_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.auditEvents += jdbc.update(
                "DELETE FROM audit_events WHERE audit_event_id = ?",
                eventId
            );
        }
    }

    private void cleanupGitOpsWebhookDeliveries(Instant cutoff, int limit, MutableResult result) {
        for (String deliveryId : ids(
            """
                SELECT delivery_id FROM gitops_webhook_deliveries
                WHERE received_at < ?
                ORDER BY received_at
                LIMIT ?
                """,
            timestamp(cutoff),
            limit
        )) {
            result.gitOpsWebhookDeliveries += jdbc.update(
                "DELETE FROM gitops_webhook_deliveries WHERE delivery_id = ?",
                deliveryId
            );
        }
    }

    private List<String> ids(String sql, Object... arguments) {
        return jdbc.query(sql, (resultSet, rowNumber) -> resultSet.getString(1), arguments);
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record RetentionCutoffs(
        Instant now,
        Instant auditCutoff,
        Instant evidenceCutoff,
        Instant evidenceRequestCutoff,
        Instant analysisTaskCutoff,
        Instant realtimeEventCutoff,
        Instant topologyObservationCutoff,
        Instant reportCutoff
    ) {
    }

    public record CleanupResult(
        int userSessions,
        int auditEvents,
        int gitOpsWebhookDeliveries,
        int realtimeEvents,
        int topologyObservations,
        int analysisTasks,
        int evidenceRequests,
        int evidenceBundles,
        int actionExecutions,
        int actionRequests,
        int rcaJobs,
        int rcaReports,
        int incidents
    ) {
        public int totalDeleted() {
            return asMap().values().stream().mapToInt(Integer::intValue).sum();
        }

        public Map<String, Integer> asMap() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("user_sessions", userSessions);
            counts.put("audit_events", auditEvents);
            counts.put("gitops_webhook_deliveries", gitOpsWebhookDeliveries);
            counts.put("realtime_events", realtimeEvents);
            counts.put("topology_observations", topologyObservations);
            counts.put("analysis_tasks", analysisTasks);
            counts.put("evidence_requests", evidenceRequests);
            counts.put("evidence_bundles", evidenceBundles);
            counts.put("action_executions", actionExecutions);
            counts.put("action_requests", actionRequests);
            counts.put("rca_jobs", rcaJobs);
            counts.put("rca_reports", rcaReports);
            counts.put("incidents", incidents);
            return Map.copyOf(counts);
        }
    }

    private static final class MutableResult {
        private int userSessions;
        private int auditEvents;
        private int gitOpsWebhookDeliveries;
        private int realtimeEvents;
        private int topologyObservations;
        private int analysisTasks;
        private int evidenceRequests;
        private int evidenceBundles;
        private int actionExecutions;
        private int actionRequests;
        private int rcaJobs;
        private int rcaReports;
        private int incidents;

        private CleanupResult freeze() {
            return new CleanupResult(
                userSessions,
                auditEvents,
                gitOpsWebhookDeliveries,
                realtimeEvents,
                topologyObservations,
                analysisTasks,
                evidenceRequests,
                evidenceBundles,
                actionExecutions,
                actionRequests,
                rcaJobs,
                rcaReports,
                incidents
            );
        }
    }
}
