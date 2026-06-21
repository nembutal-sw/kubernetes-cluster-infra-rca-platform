package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.persistence.JdbcRcaStore;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IncidentLifecycleRepositoryTests {
    private JdbcTemplate jdbc;
    private JdbcRcaStore store;
    private Instant now;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:incident-lifecycle-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcRcaStore(jdbc, new ObjectMapper(), new TokenService());
        now = Instant.parse("2026-06-21T04:00:00Z");
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            "cluster-1",
            "Lifecycle Cluster",
            "test",
            "",
            "active",
            "token",
            Timestamp.from(now.minus(1, ChronoUnit.DAYS))
        );
    }

    @Test
    void resolvesOnlyInactiveIncidentsWithoutPendingManualWork() {
        seedIncident("incident-idle", "dedup-idle", "DiskPressure");
        seedIncident("incident-blocked", "dedup-blocked", "KubeletDown");
        seedIncident("incident-read-only", "dedup-read-only", "MemoryPressure");
        seedAction("incident-blocked", "approved_manual");
        seedAction("incident-read-only", "accepted");

        var resolved = store.resolveInactiveIncidents(
            now.minus(60, ChronoUnit.MINUTES),
            now,
            100
        );

        assertThat(resolved).extracting(incident -> incident.incidentId())
            .containsExactlyInAnyOrder("incident-idle", "incident-read-only");
        var idle = store.getIncident("incident-idle").orElseThrow();
        assertThat(idle.status()).isEqualTo(IncidentStatus.resolved);
        assertThat(idle.resolvedAt()).isEqualTo(now);
        assertThat(idle.resolutionSource()).isEqualTo("automatic");
        assertThat(store.getIncident("incident-blocked").orElseThrow().status())
            .isEqualTo(IncidentStatus.open);
    }

    @Test
    void recordsSignalResolutionAndRecurrenceMetadata() {
        seedIncident("incident-parent", "dedup-parent", "DiskPressure");

        var resolved = store.resolveOpenIncidentsBySignal(
            "cluster-1",
            "worker-a",
            "DiskPressure",
            now,
            "alertmanager",
            "upstream resolved"
        );
        assertThat(resolved).hasSize(1);
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at, recurrence_of_incident_id,
                     recurrence_sequence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "incident-recurrence",
            "dedup-recurrence",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            "Disk pressure recurred",
            "open",
            1,
            Timestamp.from(now.plusSeconds(60)),
            Timestamp.from(now.plusSeconds(60)),
            "incident-parent",
            1
        );

        var recurrence = store.getIncident("incident-recurrence").orElseThrow();
        assertThat(recurrence.recurrenceOfIncidentId()).isEqualTo("incident-parent");
        assertThat(recurrence.recurrenceSequence()).isEqualTo(1);
        assertThat(store.listRecentResolvedIncidents(
            "cluster-1",
            "worker-a",
            now.minus(1, ChronoUnit.HOURS),
            now.plus(1, ChronoUnit.HOURS),
            10
        )).extracting(incident -> incident.incidentId()).contains("incident-parent");
    }

    private void seedIncident(String incidentId, String dedupKey, String alertName) {
        jdbc.update(
            """
                INSERT INTO incidents
                    (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                     occurrence_count, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            incidentId,
            dedupKey,
            "cluster-1",
            "worker-a",
            alertName,
            "test cause",
            "open",
            1,
            Timestamp.from(now.minus(3, ChronoUnit.HOURS)),
            Timestamp.from(now.minus(2, ChronoUnit.HOURS))
        );
    }

    private void seedAction(String incidentId, String status) {
        String reportId = "report-" + incidentId;
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, incident_id, status, trigger_json, scope_json, summary_json,
                     evidence_json, root_cause_candidates_json, recommended_actions_json,
                     policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            reportId,
            "cluster-1",
            incidentId,
            "completed",
            "{}",
            "{}",
            "{}",
            "[]",
            "[]",
            "[]",
            "[]",
            Timestamp.from(now.minus(2, ChronoUnit.HOURS))
        );
        jdbc.update(
            """
                INSERT INTO action_requests
                    (action_request_id, report_id, action_index, action_key, policy, source, status,
                     requested_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "action-" + incidentId,
            reportId,
            0,
            "manual-check",
            "APPROVAL_REQUIRED",
            "rule_based",
            status,
            "admin",
            Timestamp.from(now.minus(90, ChronoUnit.MINUTES))
        );
    }
}
