package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EvidenceRepositoryTests {
    private JdbcTemplate jdbc;
    private ClusterRepository clusters;
    private AnalysisTaskRepository tasks;
    private EvidenceRepository evidence;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:evidence-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        clusters = new ClusterRepository(jdbc, new TokenService());
        tasks = new AnalysisTaskRepository(jdbc);
        evidence = new EvidenceRepository(jdbc, objectMapper(), tasks, clusters);
    }

    @Test
    void completedResponseStoresRedactedEvidenceEnqueuesAnalysisAndMarksClusterActive() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var request = evidence.createRequest(new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            "worker-a",
            "DiskPressure",
            List.of("disk", "kernel"),
            Map.of("lookback_seconds", 300),
            "disk pressure investigation",
            Map.of("trigger", "scheduled_monitoring")
        ));

        var completed = evidence.submitResponse(new AgentEvidenceSubmitRequest(
            request.requestId(),
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            "node-token",
            EvidenceRequestStatus.completed,
            Map.of(
                "disk", Map.of("usage_percent", 96),
                "runtime", Map.of(
                    "authorization", "Bearer node-secret",
                    "messages", List.of("password=node-password")
                )
            ),
            null
        ), 3).orElseThrow();

        assertThat(completed.evidenceId()).isNotBlank();
        String storedCollectors = evidence.find(completed.evidenceId()).orElseThrow().collectors().toString();
        assertThat(storedCollectors)
            .contains("[redacted]")
            .doesNotContain("node-secret")
            .doesNotContain("node-password");

        var task = tasks.findByEvidence(completed.evidenceId()).orElseThrow();
        assertThat(task.status()).isEqualTo(AnalysisTaskStatus.queued);
        assertThat(task.source()).isEqualTo("agent_evidence");
        assertThat(task.skipIfHealthy()).isTrue();
        assertThat(task.maxAttempts()).isEqualTo(3);
        assertThat(clusters.find(cluster.clusterId()).orElseThrow().status()).isEqualTo(ClusterStatus.active);
    }

    @Test
    void failedResponseStoresRedactedErrorWithoutCreatingEvidenceOrTask() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var request = evidence.createRequest(new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            "worker-a",
            "NodeNotReady",
            List.of("kernel"),
            Map.of(),
            null,
            Map.of()
        ));

        var failed = evidence.submitResponse(new AgentEvidenceSubmitRequest(
            request.requestId(),
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            "node-token",
            EvidenceRequestStatus.failed,
            Map.of(),
            "collector failed: password=node-password"
        ), 2).orElseThrow();

        assertThat(failed.status()).isEqualTo(EvidenceRequestStatus.failed);
        assertThat(failed.evidenceId()).isNull();
        assertThat(failed.errorMessage())
            .contains("[redacted]")
            .doesNotContain("node-password");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM evidence_bundles WHERE cluster_id = ?",
            Integer.class,
            cluster.clusterId()
        )).isZero();
        assertThat(tasks.count(AnalysisTaskStatus.queued)).isZero();
    }

    @Test
    void duplicateCompletedResponseReturnsExistingRequestWithoutCreatingMoreEvidenceOrTasks() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var request = evidence.createRequest(new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            "worker-a",
            "DiskPressure",
            List.of("disk"),
            Map.of(),
            "idempotency check",
            Map.of()
        ));
        AgentEvidenceSubmitRequest submit = new AgentEvidenceSubmitRequest(
            request.requestId(),
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            "node-token",
            EvidenceRequestStatus.completed,
            Map.of("disk", Map.of("usage_percent", 96)),
            null
        );

        var first = evidence.submitResponse(submit, 3).orElseThrow();
        var duplicate = evidence.submitResponse(submit, 3).orElseThrow();

        assertThat(duplicate).isEqualTo(first);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM evidence_bundles WHERE cluster_id = ?",
            Integer.class,
            cluster.clusterId()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_analysis_tasks WHERE evidence_id = ?",
            Integer.class,
            first.evidenceId()
        )).isEqualTo(1);
        assertThat(tasks.count(AnalysisTaskStatus.queued)).isEqualTo(1);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }
}
