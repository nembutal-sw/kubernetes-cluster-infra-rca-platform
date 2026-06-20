package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.persistence.JdbcRcaStore;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class DatabaseCompatibilityTests {
    private static final String PYTHON_ADMIN_HASH =
        "pbkdf2_sha256$210000$AAECAwQFBgcICQoLDA0ODw$48lTXWG2pKRFYa2VDSIa1k9iNJ_kpewyX2PSJx1eg5Q";
    private static final List<String> DROP_ORDER = List.of(
        "rca_analysis_tasks",
        "action_executions",
        "action_requests",
        "audit_events",
        "rca_jobs",
        "user_sessions",
        "evidence_requests",
        "rca_reports",
        "incidents",
        "realtime_events",
        "evidence_bundles",
        "node_agents",
        "user_accounts",
        "clusters",
        "flyway_schema_history"
    );

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rca")
            .withUsername("rca")
            .withPassword("rca-test");

    @Container
    private static final MariaDBContainer<?> MARIADB =
        new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("rca")
            .withUsername("rca")
            .withPassword("rca-test");

    @Test
    void postgresqlSupportsFreshSchemaAndRepositoryWorkflow() {
        verifyFreshSchema(dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void mariadbSupportsFreshSchemaAndRepositoryWorkflow() {
        verifyFreshSchema(dataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
    }

    @Test
    void postgresqlBaselinesExistingAlembicSchemaWithoutLosingData() {
        verifyExistingSchemaBaseline(dataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ));
    }

    @Test
    void mariadbBaselinesExistingAlembicSchemaWithoutLosingData() {
        verifyExistingSchemaBaseline(dataSource(
            MARIADB.getJdbcUrl(),
            MARIADB.getUsername(),
            MARIADB.getPassword()
        ));
    }

    private void verifyFreshSchema(DataSource dataSource) {
        reset(dataSource);
        MigrateResult migration = flyway(dataSource).migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(4);

        JdbcRcaStore repository = repository(dataSource);
        var admin = repository.ensureDefaultAdmin("admin", "admin");
        assertThat(repository.authenticateUser("admin", "admin")).contains(admin);

        String sessionToken = repository.createUserSession(
            admin.userId(),
            Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(repository.getUserBySessionToken(sessionToken)).contains(admin);
        assertThat(repository.revokeUserSession(sessionToken)).isTrue();
        assertThat(repository.getUserBySessionToken(sessionToken)).isEmpty();

        Cluster cluster = repository.createCluster(new ClusterCreateRequest(
            "database-compatibility",
            "test",
            "Cross-database integration test"
        ));
        NodeAgentRegistrationResponse registration = repository.registerAgent(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            "0.1.0",
            List.of("disk", "inode", "kernel"),
            Map.of("platform", "testcontainers")
        ));
        assertThat(repository.verifyAgentNodeToken(
            cluster.clusterId(),
            "worker-a",
            registration.nodeToken()
        )).isTrue();
        assertThat(registration.agentProtocolVersion()).isEqualTo("1");

        assertThat(repository.recordAgentHeartbeat(new NodeAgentHeartbeatRequest(
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            registration.nodeToken(),
            AgentStatus.healthy,
            "0.1.1",
            List.of("disk", "inode", "kernel"),
            Map.of("ready", true)
        ))).isPresent();
        assertThat(repository.getAgent(cluster.clusterId(), "worker-a").orElseThrow().agentProtocolVersion())
            .isEqualTo("1");

        EvidenceRequest evidenceRequest = repository.createEvidenceRequest(new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            "worker-a",
            "DiskPressure",
            List.of("disk", "inode", "kernel"),
            Map.of("lookback_seconds", 300),
            "Database compatibility test",
            Map.of("source", "testcontainers")
        ));
        EvidenceRequest completed = repository.submitEvidenceResponse(
            new AgentEvidenceSubmitRequest(
                evidenceRequest.requestId(),
                cluster.clusterId(),
                "worker-a",
                cluster.bootstrapToken(),
                registration.nodeToken(),
                EvidenceRequestStatus.completed,
                Map.of(
                    "disk", Map.of("usage_percent", 96.0, "await_ms", 35.0),
                    "inode", Map.of("usage_percent", 98.0)
                ),
                null
            ),
            2
        ).orElseThrow();

        assertThat(completed.evidenceId()).isNotBlank();
        assertThat(repository.getEvidence(completed.evidenceId()).orElseThrow().collectors())
            .containsKeys("disk", "inode");
        var queuedTask = repository.getAnalysisTaskByEvidenceId(completed.evidenceId()).orElseThrow();
        assertThat(queuedTask.status()).isEqualTo(AnalysisTaskStatus.queued);
        Instant claimAt = Instant.now();
        var firstClaim = repository.claimAnalysisTasks(
            "database-worker",
            1,
            claimAt,
            claimAt.plusSeconds(30)
        );
        assertThat(firstClaim).hasSize(1);
        assertThat(firstClaim.get(0).attemptCount()).isEqualTo(1);
        assertThat(repository.failAnalysisTask(
            firstClaim.get(0),
            "database-worker",
            "temporary provider failure",
            claimAt.plusSeconds(1)
        )).isTrue();
        assertThat(repository.getAnalysisTask(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.retry_wait);

        var secondClaim = repository.claimAnalysisTasks(
            "database-worker",
            1,
            claimAt.plusSeconds(2),
            claimAt.plusSeconds(32)
        );
        assertThat(secondClaim).hasSize(1);
        assertThat(secondClaim.get(0).attemptCount()).isEqualTo(2);
        assertThat(repository.failAnalysisTask(
            secondClaim.get(0),
            "database-worker",
            "provider unavailable",
            claimAt.plusSeconds(3)
        )).isTrue();
        assertThat(repository.getAnalysisTask(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.dead_letter);
        assertThat(repository.retryAnalysisTask(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.queued);

        RecommendedAction action = new RecommendedAction(
            "Inspect filesystem consumers",
            PolicyLevel.MANUAL_INVESTIGATION,
            "Disk and inode thresholds are exceeded",
            "inspect-filesystem",
            "rule",
            "manual",
            false,
            false,
            true,
            List.of("Read-only commands only"),
            List.of("Production node"),
            null
        );
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RcaReport report = new RcaReport(
            "report-db-compat",
            cluster.clusterId(),
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Inode exhaustion", Confidence.high),
            List.of(Map.of("evidence_id", completed.evidenceId())),
            List.of(new RootCauseCandidate("Inode exhaustion", Confidence.high, List.of("inode=98%"))),
            List.of(action),
            List.of(action),
            now
        );
        RcaJob job = new RcaJob(
            "job-db-compat",
            cluster.clusterId(),
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            report.reportId(),
            completed.evidenceId(),
            now
        );
        repository.saveCorrelatedReportAndJob(
            report,
            job,
            "database-compatibility-dedup-key",
            repository.getEvidence(completed.evidenceId()).orElseThrow()
        );

        RcaReport storedReport = repository.getReport(report.reportId()).orElseThrow();
        assertThat(storedReport.summary().mostLikelyCause()).isEqualTo("Inode exhaustion");
        assertThat(storedReport.incidentId()).startsWith("incident-");
        assertThat(repository.getIncident(storedReport.incidentId()).orElseThrow().occurrenceCount()).isEqualTo(1);
        assertThat(repository.getJob(job.jobId())).contains(job);
        var actionRequest = repository.createActionRequest(
            report.reportId(),
            0,
            action.actionKey(),
            action.policy(),
            action.source(),
            ActionRequestStatus.accepted,
            admin.email(),
            "database compatibility",
            null
        );
        assertThat(repository.getActionRequest(actionRequest.actionRequestId())).contains(actionRequest);
        repository.saveAuditEvent(
            "user",
            admin.email(),
            "database.compatibility",
            "cluster",
            cluster.clusterId(),
            "success",
            Map.of("database", "testcontainers")
        );
        assertThat(repository.listAuditEvents(10)).hasSize(1);
        assertThat(repository.deleteCluster(cluster.clusterId())).isTrue();
        assertThat(repository.getCluster(cluster.clusterId())).isEmpty();
    }

    private void verifyExistingSchemaBaseline(DataSource dataSource) {
        reset(dataSource);
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V1__existing_schema.sql")
        ).execute(dataSource);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant createdAt = Instant.parse("2026-06-10T00:00:00Z");
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "cluster-legacy",
            "legacy-cluster",
            "prod",
            "Created by the Python backend",
            "registered",
            "legacy-bootstrap-token",
            Timestamp.from(createdAt),
            null
        );
        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "user-admin",
            "admin",
            "Administrator",
            PYTHON_ADMIN_HASH,
            "admin",
            "admin",
            "active",
            null,
            null,
            "system",
            Timestamp.from(createdAt),
            Timestamp.from(createdAt)
        );
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, status, trigger_json, scope_json, summary_json, evidence_json,
                     root_cause_candidates_json, recommended_actions_json, policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "report-legacy",
            "cluster-legacy",
            "completed",
            "{\"alert_name\":\"NodeNotReady\"}",
            "{\"node_name\":\"worker-legacy\"}",
            "{\"symptom\":\"NodeNotReady\",\"most_likely_cause\":\"kubelet unavailable\",\"confidence\":\"high\"}",
            "[]",
            "[]",
            "[]",
            "[]",
            Timestamp.from(createdAt)
        );

        MigrateResult migration = flyway(dataSource).migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND type = 'BASELINE'",
            Integer.class
        )).isEqualTo(1);

        JdbcRcaStore repository = repository(dataSource);
        assertThat(repository.authenticateUser("admin", "admin")).isPresent();
        assertThat(repository.getCluster("cluster-legacy").orElseThrow().name()).isEqualTo("legacy-cluster");
        assertThat(repository.getReport("report-legacy").orElseThrow().summary().mostLikelyCause())
            .isEqualTo("kubelet unavailable");

        Cluster newCluster = repository.createCluster(new ClusterCreateRequest(
            "post-migration-cluster",
            "test",
            null
        ));
        assertThat(repository.getCluster(newCluster.clusterId())).isPresent();
    }

    private Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load();
    }

    private JdbcRcaStore repository(DataSource dataSource) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new JdbcRcaStore(new JdbcTemplate(dataSource), objectMapper, new TokenService());
    }

    private DriverManagerDataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    private void reset(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DROP_ORDER.forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
    }
}
