package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.IncidentPersistenceService;
import io.clusterinfra.rca.webconsole.service.RcaAnalysisWorker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:analysis-boundary-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.pipeline.enabled=true",
    "rca.pipeline.initial-delay-ms=600000",
    "rca.monitoring.enabled=false",
    "rca.notification.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AnalysisTaskPersistenceBoundaryTests {
    private static final Instant NOW = Instant.parse("2026-07-27T03:00:00Z");

    @Autowired
    private ClusterRepository clusters;

    @Autowired
    private EvidenceRepository evidence;

    @Autowired
    private AnalysisTaskRepository tasks;

    @Autowired
    private IncidentPersistenceService persistence;

    @Autowired
    private RcaAnalysisWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AuditService audit;

    @BeforeEach
    void failEveryAuditWrite() {
        doThrow(new IllegalStateException("audit repository unavailable"))
            .when(audit)
            .system(any(), any(), any(), any(), any(), any());
    }

    @Test
    void auditFailureAfterCommitDoesNotRetryOrDuplicateEvidence() {
        var cluster = clusters.create(new ClusterCreateRequest("audit-boundary", "test", null));
        AnalysisTask queued = evidence.saveAndEnqueue(
            evidence(cluster.clusterId(), "evidence-audit"),
            "audit_failure_test",
            false,
            3
        );

        assertThat(worker.processAvailableTasks()).isEqualTo(1);

        AnalysisTask completed = tasks.find(queued.taskId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AnalysisTaskStatus.completed);
        assertThat(completed.attemptCount()).isEqualTo(1);
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("rca_reports")).isEqualTo(1);
        assertThat(count("rca_jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT occurrence_count FROM incidents",
            Integer.class
        )).isEqualTo(1);

        assertThat(worker.processAvailableTasks()).isZero();
        assertThat(count("rca_reports")).isEqualTo(1);
        assertThat(count("rca_jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT occurrence_count FROM incidents",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void staleLeaseRollsBackIncidentReportJobAndOutboxPersistence() {
        var cluster = clusters.create(new ClusterCreateRequest("lease-fence", "test", null));
        EvidenceBundle savedEvidence = evidence.save(
            evidence(cluster.clusterId(), "evidence-stale-lease")
        );
        AnalysisTask queued = tasks.enqueue(savedEvidence, "lease_fence_test", false, 3);
        Instant claimAt = Instant.now().plusSeconds(1);
        AnalysisTask staleClaim = tasks.claim(
            "worker-a",
            1,
            claimAt,
            claimAt.plusSeconds(30)
        ).getFirst();
        assertThat(staleClaim.taskId()).isEqualTo(queued.taskId());
        assertThat(tasks.claim(
            "worker-b",
            1,
            claimAt.plusSeconds(31),
            claimAt.plusSeconds(61)
        )).hasSize(1);

        assertThatThrownBy(() -> persistence.saveCorrelatedAndCompleteTask(
            report(cluster.clusterId(), "report-stale"),
            job(cluster.clusterId(), "report-stale", savedEvidence.evidenceId()),
            "lease-fence-dedup",
            null,
            false,
            null,
            0,
            savedEvidence,
            staleClaim,
            "worker-a"
        )).isInstanceOf(IncidentPersistenceService.AnalysisTaskLeaseLostException.class);

        assertThat(count("incidents")).isZero();
        assertThat(count("rca_reports")).isZero();
        assertThat(count("rca_jobs")).isZero();
        assertThat(count("notification_outbox")).isZero();
        AnalysisTask current = tasks.find(queued.taskId()).orElseThrow();
        assertThat(current.status()).isEqualTo(AnalysisTaskStatus.processing);
        assertThat(current.attemptCount()).isEqualTo(2);
        assertThat(current.leaseOwner()).isEqualTo("worker-b");
    }

    private EvidenceBundle evidence(String clusterId, String evidenceId) {
        return new EvidenceBundle(
            evidenceId,
            clusterId,
            "worker-a",
            "DiskPressure",
            NOW,
            Map.of("disk", Map.of("root_usage_percent", 97))
        );
    }

    private RcaReport report(String clusterId, String reportId) {
        return new RcaReport(
            reportId,
            clusterId,
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Disk pressure", Confidence.high),
            List.of(Map.of(
                "type", "derived_signals",
                "signals", List.of(Map.of("severity", "critical"))
            )),
            List.of(new RootCauseCandidate(
                "Disk pressure",
                Confidence.high,
                List.of("root filesystem usage is 97 percent")
            )),
            List.of(),
            List.of(),
            NOW
        );
    }

    private RcaJob job(String clusterId, String reportId, String evidenceId) {
        return new RcaJob(
            "job-" + reportId,
            clusterId,
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            reportId,
            evidenceId,
            NOW
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
