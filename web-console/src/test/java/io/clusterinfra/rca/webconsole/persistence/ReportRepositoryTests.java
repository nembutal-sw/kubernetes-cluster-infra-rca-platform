package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import static io.clusterinfra.rca.webconsole.TestSecurity.clusterRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReportRepositoryTests {
    private JdbcTemplate jdbc;
    private ClusterRepository clusters;
    private ReportRepository reports;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:report-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        clusters = clusterRepository(jdbc);
        reports = new ReportRepository(jdbc, objectMapper());
    }

    @Test
    void savePersistsReportAndJobWithTypedJsonFields() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        Instant createdAt = Instant.parse("2026-06-21T04:00:00Z");
        insertEvidence(cluster.clusterId(), "evidence-1", createdAt);
        RcaReport report = report(cluster.clusterId(), "report-1", createdAt);
        RcaJob job = new RcaJob(
            "job-1",
            cluster.clusterId(),
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            report.reportId(),
            "evidence-1",
            createdAt.plusSeconds(1)
        );

        assertThat(reports.save(report, job)).isEqualTo(job);

        RcaReport storedReport = reports.findReport(report.reportId()).orElseThrow();
        assertThat(storedReport.summary().mostLikelyCause()).isEqualTo("Inode exhaustion");
        assertThat(storedReport.trigger()).containsEntry("alert_name", "DiskPressure");
        assertThat(storedReport.rootCauseCandidates().getFirst().confidenceScore()).isEqualTo(91);
        assertThat(storedReport.recommendedActions().getFirst().executionPlan().commandKey())
            .isEqualTo("collect_node_diagnostics");
        assertThat(reports.findJob(job.jobId())).contains(job);
        assertThat(reports.listReports()).extracting(RcaReport::reportId).contains(report.reportId());
        assertThat(reports.listJobs()).extracting(RcaJob::jobId).contains(job.jobId());
    }

    @Test
    void findReturnsEmptyWhenReportOrJobIsMissing() {
        assertThat(reports.findReport("missing-report")).isEmpty();
        assertThat(reports.findJob("missing-job")).isEmpty();
    }

    private RcaReport report(String clusterId, String reportId, Instant createdAt) {
        RecommendedAction action = new RecommendedAction(
            "Collect node diagnostics",
            PolicyLevel.MANUAL_INVESTIGATION,
            "Read-only evidence is required.",
            "collect_node_diagnostics",
            "rule_based",
            "manual",
            false,
            false,
            true,
            List.of("read_only"),
            List.of("requires operator review"),
            new ActionPlan(
                "collect_node_diagnostics",
                Map.of("node", "worker-a"),
                List.of("kubectl describe node worker-a"),
                null,
                false,
                60
            )
        );
        return new RcaReport(
            reportId,
            clusterId,
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Inode exhaustion", Confidence.high),
            List.of(Map.of("evidence_id", "evidence-1")),
            List.of(new RootCauseCandidate(
                "Inode exhaustion",
                Confidence.high,
                List.of("inode usage is above threshold"),
                91,
                List.of("$.collectors.inode")
            )),
            List.of(action),
            List.of(action),
            createdAt
        );
    }

    private void insertEvidence(String clusterId, String evidenceId, Instant collectedAt) {
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            evidenceId,
            clusterId,
            "worker-a",
            "DiskPressure",
            "{\"inode\":{\"usage_percent\":98}}",
            Timestamp.from(collectedAt)
        );
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }
}
