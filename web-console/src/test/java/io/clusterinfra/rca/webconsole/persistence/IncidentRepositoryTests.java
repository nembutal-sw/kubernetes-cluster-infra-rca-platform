package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
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

class IncidentRepositoryTests {
    private JdbcTemplate jdbc;
    private ClusterRepository clusters;
    private IncidentRepository incidents;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:incident-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        clusters = clusterRepository(jdbc);
        incidents = new IncidentRepository(jdbc, objectMapper());
    }

    @Test
    void correlatedSaveCreatesIncidentDeduplicatesRepeatedEvidenceAndPromotesRootCauseWhenRequested() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        Instant base = Instant.parse("2026-06-21T04:00:00Z");
        EvidenceBundle firstEvidence = evidence(cluster.clusterId(), "evidence-1", "worker-a", base);
        RcaReport firstReport = report(cluster.clusterId(), "report-1", "Inode exhaustion", firstEvidence, base);
        RcaJob firstJob = job(cluster.clusterId(), "job-1", firstReport.reportId(), firstEvidence, base);

        RcaJob saved = incidents.saveCorrelated(
            firstReport,
            firstJob,
            "dedup-disk-pressure",
            null,
            false,
            null,
            0,
            firstEvidence
        );

        assertThat(saved).isEqualTo(firstJob);
        var incident = incidents.findByDedupKey("dedup-disk-pressure").orElseThrow();
        assertThat(incident.occurrenceCount()).isEqualTo(1);
        assertThat(incident.latestReportId()).isEqualTo(firstReport.reportId());
        assertThat(incident.nodeNames()).containsExactly("worker-a");

        EvidenceBundle repeatedEvidence = evidence(
            cluster.clusterId(),
            "evidence-2",
            "worker-b",
            base.plusSeconds(30)
        );
        RcaReport repeatedReport = report(
            cluster.clusterId(),
            "report-2",
            "Same pressure signature",
            repeatedEvidence,
            base.plusSeconds(30)
        );
        RcaJob repeatedJob = job(
            cluster.clusterId(),
            "job-2",
            repeatedReport.reportId(),
            repeatedEvidence,
            base.plusSeconds(30)
        );

        RcaJob deduplicated = incidents.saveCorrelated(
            repeatedReport,
            repeatedJob,
            "dedup-disk-pressure",
            null,
            false,
            null,
            0,
            repeatedEvidence
        );

        assertThat(deduplicated).isEqualTo(firstJob);
        var dedupedIncident = incidents.find(incident.incidentId()).orElseThrow();
        assertThat(dedupedIncident.occurrenceCount()).isEqualTo(2);
        assertThat(dedupedIncident.latestReportId()).isEqualTo(firstReport.reportId());
        assertThat(dedupedIncident.latestEvidenceId()).isEqualTo(repeatedEvidence.evidenceId());
        assertThat(dedupedIncident.nodeNames()).containsExactly("worker-a", "worker-b");
        assertThat(reportCount(repeatedReport.reportId())).isZero();
        assertThat(jobCount(repeatedJob.jobId())).isZero();

        EvidenceBundle promotedEvidence = evidence(
            cluster.clusterId(),
            "evidence-3",
            "worker-b",
            base.plusSeconds(60)
        );
        RcaReport promotedReport = report(
            cluster.clusterId(),
            "report-3",
            "Kernel I/O error",
            promotedEvidence,
            base.plusSeconds(60)
        );
        RcaJob promotedJob = job(
            cluster.clusterId(),
            "job-3",
            promotedReport.reportId(),
            promotedEvidence,
            base.plusSeconds(60)
        );

        RcaJob promoted = incidents.saveCorrelated(
            promotedReport,
            promotedJob,
            "unused-when-matched",
            incident.incidentId(),
            true,
            null,
            0,
            promotedEvidence
        );

        assertThat(promoted).isEqualTo(promotedJob);
        var promotedIncident = incidents.find(incident.incidentId()).orElseThrow();
        assertThat(promotedIncident.occurrenceCount()).isEqualTo(3);
        assertThat(promotedIncident.latestReportId()).isEqualTo(promotedReport.reportId());
        assertThat(promotedIncident.rootCause()).isEqualTo("Kernel I/O error");
        assertThat(promotedIncident.latestEvidenceId()).isEqualTo(promotedEvidence.evidenceId());
        assertThat(reportIncidentId(promotedReport.reportId())).isEqualTo(incident.incidentId());
        assertThat(jobCount(promotedJob.jobId())).isOne();
    }

    private EvidenceBundle evidence(String clusterId, String evidenceId, String nodeName, Instant collectedAt) {
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            evidenceId,
            clusterId,
            nodeName,
            "DiskPressure",
            "{\"disk\":{\"usage_percent\":96}}",
            Timestamp.from(collectedAt)
        );
        return new EvidenceBundle(
            evidenceId,
            clusterId,
            nodeName,
            "DiskPressure",
            collectedAt,
            Map.of("disk", Map.of("usage_percent", 96))
        );
    }

    private RcaReport report(String clusterId, String reportId, String cause, EvidenceBundle evidence, Instant createdAt) {
        return new RcaReport(
            reportId,
            clusterId,
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", evidence.nodeName()),
            new RcaSummary("DiskPressure", cause, Confidence.high),
            List.of(Map.of("evidence_id", evidence.evidenceId())),
            List.of(new RootCauseCandidate(cause, Confidence.high, List.of("supporting evidence"))),
            List.of(),
            List.of(),
            createdAt
        );
    }

    private RcaJob job(String clusterId, String jobId, String reportId, EvidenceBundle evidence, Instant createdAt) {
        return new RcaJob(
            jobId,
            clusterId,
            "DiskPressure",
            evidence.nodeName(),
            RcaJobStatus.completed,
            reportId,
            evidence.evidenceId(),
            createdAt
        );
    }

    private int reportCount(String reportId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_reports WHERE report_id = ?",
            Integer.class,
            reportId
        );
    }

    private int jobCount(String jobId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_jobs WHERE job_id = ?",
            Integer.class,
            jobId
        );
    }

    private String reportIncidentId(String reportId) {
        return jdbc.queryForObject(
            "SELECT incident_id FROM rca_reports WHERE report_id = ?",
            String.class,
            reportId
        );
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }
}
