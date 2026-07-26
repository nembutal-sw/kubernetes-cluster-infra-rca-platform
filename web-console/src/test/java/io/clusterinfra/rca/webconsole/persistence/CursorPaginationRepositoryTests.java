package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import static io.clusterinfra.rca.webconsole.TestSecurity.clusterRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CursorPaginationRepositoryTests {
    private JdbcTemplate jdbc;
    private String clusterId;
    private ReportRepository reports;
    private IncidentRepository incidents;
    private AnalysisTaskRepository tasks;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:cursor-pagination-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        clusterId = clusterRepository(jdbc)
            .create(new ClusterCreateRequest("pagination", "test", null))
            .clusterId();
        reports = new ReportRepository(jdbc, objectMapper);
        incidents = new IncidentRepository(jdbc, objectMapper);
        tasks = new AnalysisTaskRepository(jdbc);
    }

    @Test
    void reportCursorUsesTimestampAndIdAndAppliesLiteralSearch() {
        Instant newest = Instant.parse("2026-07-13T01:00:00Z");
        insertReport("report-a", "Disk pressure 100%", newest);
        insertReport("report-b", "Inode pressure", newest);
        insertReport("report-c", "Network timeout", newest.minusSeconds(1));

        var first = reports.pageReports(clusterId, RcaJobStatus.completed, null, null, 2);
        var second = reports.pageReports(clusterId, null, null, first.nextCursor(), 2);
        var searched = reports.pageReports(clusterId, null, "100%", null, 10);

        assertThat(first.items()).extracting(item -> item.reportId()).containsExactly("report-b", "report-a");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.total()).isEqualTo(3);
        assertThat(second.items()).extracting(item -> item.reportId()).containsExactly("report-c");
        assertThat(second.hasMore()).isFalse();
        assertThat(searched.items()).extracting(item -> item.reportId()).containsExactly("report-a");
    }

    @Test
    void incidentAndTaskPagesApplyStatusClusterAndSearchFilters() {
        Instant now = Instant.parse("2026-07-13T02:00:00Z");
        insertIncident("incident-a", "Disk I/O error", IncidentStatus.open, now);
        insertIncident("incident-b", "DNS timeout", IncidentStatus.resolved, now.minusSeconds(1));
        insertTask("task-a", "evidence-a", "DiskPressure", AnalysisTaskStatus.queued, null, now);
        insertTask("task-b", "evidence-b", "CoreDNS", AnalysisTaskStatus.dead_letter, "DNS timeout", now.minusSeconds(1));

        var incidentPage = incidents.page(clusterId, IncidentStatus.resolved, "dns", null, 10);
        var taskPage = tasks.page(clusterId, AnalysisTaskStatus.dead_letter, "timeout", null, 10);

        assertThat(incidentPage.items()).extracting(item -> item.incidentId()).containsExactly("incident-b");
        assertThat(taskPage.items()).extracting(item -> item.taskId()).containsExactly("task-b");
        assertThat(taskPage.total()).isOne();
    }

    @Test
    void rejectsMalformedCursorAndOversizedQuery() {
        assertThatThrownBy(() -> reports.pageReports(clusterId, null, null, "not-a-cursor", 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("cursor is invalid");
        assertThatThrownBy(() -> incidents.page(clusterId, null, "x".repeat(201), null, 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("200 characters");
    }

    private void insertReport(String reportId, String cause, Instant createdAt) {
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, incident_id, status, trigger_json, scope_json, summary_json,
                     evidence_json, root_cause_candidates_json, recommended_actions_json,
                     policy_decisions_json, created_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            reportId,
            clusterId,
            RcaJobStatus.completed.name(),
            "{\"alert_name\":\"DiskPressure\"}",
            "{}",
            "{\"symptom\":\"pressure\",\"most_likely_cause\":\"" + cause
                + "\",\"confidence\":\"high\"}",
            "[]",
            "[]",
            "[]",
            "[]",
            Timestamp.from(createdAt)
        );
    }

    private void insertIncident(
        String incidentId,
        String cause,
        IncidentStatus status,
        Instant lastSeenAt
    ) {
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at, recurrence_sequence, node_names_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
            incidentId,
            "dedup-" + incidentId,
            clusterId,
            "worker-a",
            cause.contains("DNS") ? "CoreDNS" : "DiskPressure",
            cause,
            status.name(),
            1,
            Timestamp.from(lastSeenAt.minusSeconds(10)),
            Timestamp.from(lastSeenAt),
            "[\"worker-a\"]"
        );
    }

    private void insertTask(
        String taskId,
        String evidenceId,
        String alertName,
        AnalysisTaskStatus status,
        String error,
        Instant createdAt
    ) {
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, '{}', ?)
                """,
            evidenceId,
            clusterId,
            "worker-a",
            alertName,
            Timestamp.from(createdAt)
        );
        jdbc.update(
            """
                INSERT INTO rca_analysis_tasks
                    (task_id, evidence_id, cluster_id, node_name, alert_name, source, skip_if_healthy,
                     status, attempt_count, max_attempts, next_attempt_at, last_error, created_at)
                VALUES (?, ?, ?, 'worker-a', ?, 'test', 0, ?, 0, 3, ?, ?, ?)
                """,
            taskId,
            evidenceId,
            clusterId,
            alertName,
            status.name(),
            Timestamp.from(createdAt),
            error,
            Timestamp.from(createdAt)
        );
    }
}
