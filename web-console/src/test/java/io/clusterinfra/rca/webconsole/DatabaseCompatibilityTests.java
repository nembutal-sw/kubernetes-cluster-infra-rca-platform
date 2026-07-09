package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
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
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterThresholdRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository.RetentionCutoffs;
import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
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
        "catalog_override_drafts",
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
        "topology_observations",
        "manifest_download_tokens",
        "cluster_threshold_overrides",
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
        assertThat(migration.migrationsExecuted).isEqualTo(17);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UserRepository users = userRepository(dataSource);
        UserSessionRepository sessions = userSessionRepository(dataSource);
        ActionRepository actions = actionRepository(dataSource);
        AuditRepository audits = auditRepository(dataSource);
        ClusterRepository clusters = clusterRepository(dataSource);
        ClusterThresholdRepository thresholds = thresholdRepository(dataSource);
        AgentRepository agents = agentRepository(dataSource);
        AnalysisTaskRepository tasks = analysisTaskRepository(dataSource);
        EvidenceRepository evidence = evidenceRepository(dataSource);
        IncidentRepository incidents = incidentRepository(dataSource);
        ReportRepository reports = reportRepository(dataSource);
        var admin = users.ensureDefaultAdmin("admin", "admin");
        assertThat(users.authenticate("admin", "admin")).contains(admin);

        String sessionToken = sessions.create(
            admin.userId(),
            Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(sessions.findUserByToken(sessionToken)).contains(admin);
        assertThat(sessions.revoke(sessionToken)).isTrue();
        assertThat(sessions.findUserByToken(sessionToken)).isEmpty();

        Cluster cluster = clusters.create(new ClusterCreateRequest(
            "database-compatibility",
            "test",
            "Cross-database integration test"
        ));
        assertThat(storedBootstrapToken(jdbc, cluster.clusterId())).isBlank();
        assertThat(storedBootstrapTokenHash(jdbc, cluster.clusterId())).isNotBlank();
        assertThat(clusters.verifyBootstrapToken(cluster.clusterId(), cluster.bootstrapToken())).isTrue();
        assertThat(clusters.verifyBootstrapToken(cluster.clusterId(), "wrong-token")).isFalse();
        thresholds.replace(
            cluster.clusterId(),
            Map.of("disk.critical.percent", 95.0),
            "database compatibility",
            "test"
        );
        assertThat(thresholds.values(cluster.clusterId()))
            .containsEntry("disk.critical.percent", 95.0);
        assertThat(jdbc.queryForObject(
            "SELECT bootstrap_token_last_used_at FROM clusters WHERE cluster_id = ?",
            Timestamp.class,
            cluster.clusterId()
        )).isNotNull();
        NodeAgentRegistrationResponse registration = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            "0.1.0",
            List.of("disk", "inode", "kernel"),
            Map.of("platform", "testcontainers")
        ));
        assertThat(agents.verifyNodeToken(
            cluster.clusterId(),
            "worker-a",
            registration.nodeToken()
        )).isTrue();
        assertThat(registration.agentProtocolVersion()).isEqualTo("1");

        assertThat(agents.heartbeat(new NodeAgentHeartbeatRequest(
            cluster.clusterId(),
            "worker-a",
            cluster.bootstrapToken(),
            registration.nodeToken(),
            AgentStatus.healthy,
            "0.1.1",
            List.of("disk", "inode", "kernel"),
            Map.of("ready", true)
        ))).isPresent();
        assertThat(agents.find(cluster.clusterId(), "worker-a").orElseThrow().agentProtocolVersion())
            .isEqualTo("1");

        EvidenceRequest evidenceRequest = evidence.createRequest(new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            "worker-a",
            "DiskPressure",
            List.of("disk", "inode", "kernel"),
            Map.of("lookback_seconds", 300),
            "Database compatibility test",
            Map.of("source", "testcontainers")
        ));
        EvidenceRequest completed = evidence.submitResponse(
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
        assertThat(evidence.find(completed.evidenceId()).orElseThrow().collectors())
            .containsKeys("disk", "inode");
        var queuedTask = tasks.findByEvidence(completed.evidenceId()).orElseThrow();
        assertThat(queuedTask.status()).isEqualTo(AnalysisTaskStatus.queued);
        Instant claimAt = Instant.now();
        var firstClaim = tasks.claim(
            "database-worker",
            1,
            claimAt,
            claimAt.plusSeconds(30)
        );
        assertThat(firstClaim).hasSize(1);
        assertThat(firstClaim.get(0).attemptCount()).isEqualTo(1);
        assertThat(tasks.fail(
            firstClaim.get(0),
            "database-worker",
            "temporary provider failure",
            claimAt.plusSeconds(1)
        )).isTrue();
        assertThat(tasks.find(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.retry_wait);

        var secondClaim = tasks.claim(
            "database-worker",
            1,
            claimAt.plusSeconds(2),
            claimAt.plusSeconds(32)
        );
        assertThat(secondClaim).hasSize(1);
        assertThat(secondClaim.get(0).attemptCount()).isEqualTo(2);
        assertThat(tasks.fail(
            secondClaim.get(0),
            "database-worker",
            "provider unavailable",
            claimAt.plusSeconds(3)
        )).isTrue();
        assertThat(tasks.find(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.dead_letter);
        assertThat(tasks.retry(queuedTask.taskId()).orElseThrow().status())
            .isEqualTo(AnalysisTaskStatus.queued);
        verifyConcurrentClaimContract(evidence, tasks, cluster.clusterId());

        Instant retentionCutoff = Instant.now().minus(3650, ChronoUnit.DAYS);
        var retentionResult = new RetentionRepository(new JdbcTemplate(dataSource)).cleanup(
            new RetentionCutoffs(
                Instant.now(),
                retentionCutoff,
                retentionCutoff,
                retentionCutoff,
                retentionCutoff,
                retentionCutoff,
                retentionCutoff,
                retentionCutoff
            ),
            10
        );
        assertThat(retentionResult.totalDeleted()).isZero();

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
        incidents.saveCorrelated(
            report,
            job,
            "database-compatibility-dedup-key",
            null,
            false,
            null,
            0,
            evidence.find(completed.evidenceId()).orElseThrow()
        );

        RcaReport storedReport = reports.findReport(report.reportId()).orElseThrow();
        assertThat(storedReport.summary().mostLikelyCause()).isEqualTo("Inode exhaustion");
        assertThat(storedReport.incidentId()).startsWith("incident-");
        var storedIncident = incidents.find(storedReport.incidentId()).orElseThrow();
        assertThat(storedIncident.occurrenceCount()).isEqualTo(1);
        assertThat(incidents.findRecentOpen(
            cluster.clusterId(),
            "worker-a",
            now.minus(1, ChronoUnit.HOURS),
            now.plus(1, ChronoUnit.HOURS),
            10
        )).extracting(incident -> incident.incidentId()).contains(storedIncident.incidentId());
        assertThat(reports.findJob(job.jobId())).contains(job);

        RcaReport promotedReport = new RcaReport(
            "report-db-promoted",
            cluster.clusterId(),
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a", "components", List.of("disk")),
            new RcaSummary("DiskPressure", "Kernel I/O error", Confidence.high),
            List.of(Map.of("evidence_id", completed.evidenceId())),
            List.of(new RootCauseCandidate("Kernel I/O error", Confidence.high, List.of("I/O error"))),
            List.of(action),
            List.of(action),
            now.plusSeconds(1)
        );
        RcaJob promotedJob = new RcaJob(
            "job-db-promoted",
            cluster.clusterId(),
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            promotedReport.reportId(),
            completed.evidenceId(),
            now.plusSeconds(1)
        );
        incidents.saveCorrelated(
            promotedReport,
            promotedJob,
            "unused-when-matched",
            storedIncident.incidentId(),
            true,
            null,
            0,
            evidence.find(completed.evidenceId()).orElseThrow()
        );
        var promotedIncident = incidents.find(storedIncident.incidentId()).orElseThrow();
        assertThat(promotedIncident.latestReportId()).isEqualTo(promotedReport.reportId());
        assertThat(promotedIncident.rootCause()).isEqualTo("Kernel I/O error");
        assertThat(promotedIncident.occurrenceCount()).isEqualTo(2);
        var resolvedIncident = incidents.updateStatus(
            storedIncident.incidentId(),
            io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus.resolved,
            "automatic",
            "database compatibility lifecycle",
            now.plusSeconds(2)
        ).orElseThrow();
        assertThat(resolvedIncident.resolvedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(resolvedIncident.resolutionSource()).isEqualTo("automatic");

        RcaReport recurrenceReport = new RcaReport(
            "report-db-recurrence",
            cluster.clusterId(),
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a", "components", List.of("disk")),
            new RcaSummary("DiskPressure", "Disk pressure recurred", Confidence.high),
            List.of(Map.of("evidence_id", completed.evidenceId())),
            List.of(new RootCauseCandidate("Disk pressure recurred", Confidence.high, List.of("disk=96%"))),
            List.of(action),
            List.of(action),
            now.plusSeconds(3)
        );
        RcaJob recurrenceJob = new RcaJob(
            "job-db-recurrence",
            cluster.clusterId(),
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            recurrenceReport.reportId(),
            completed.evidenceId(),
            now.plusSeconds(3)
        );
        incidents.saveCorrelated(
            recurrenceReport,
            recurrenceJob,
            "database-compatibility-recurrence-key",
            null,
            false,
            storedIncident.incidentId(),
            1,
            evidence.find(completed.evidenceId()).orElseThrow()
        );
        var recurrenceIncident = incidents.find(
            reports.findReport(recurrenceReport.reportId()).orElseThrow().incidentId()
        ).orElseThrow();
        assertThat(recurrenceIncident.recurrenceOfIncidentId()).isEqualTo(storedIncident.incidentId());
        assertThat(recurrenceIncident.recurrenceSequence()).isEqualTo(1);

        var actionRequest = actions.createRequest(
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
        assertThat(actions.findRequest(actionRequest.actionRequestId())).contains(actionRequest);
        audits.save(
            "user",
            admin.email(),
            "database.compatibility",
            "cluster",
            cluster.clusterId(),
            "new_incident_linked_to_resolved_incident",
            Map.of("database", "testcontainers")
        );
        assertThat(audits.list(10)).hasSize(1);
        assertThat(clusters.delete(cluster.clusterId())).isTrue();
        assertThat(clusters.find(cluster.clusterId())).isEmpty();
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
        assertThat(migration.migrationsExecuted).isEqualTo(16);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND type = 'BASELINE'",
            Integer.class
        )).isEqualTo(1);

        UserRepository users = userRepository(dataSource);
        ClusterRepository clusters = clusterRepository(dataSource);
        ClusterThresholdRepository thresholds = thresholdRepository(dataSource);
        ReportRepository reports = reportRepository(dataSource);
        assertThat(users.authenticate("admin", "admin")).isPresent();
        assertThat(clusters.find("cluster-legacy").orElseThrow().name()).isEqualTo("legacy-cluster");
        assertThat(clusters.verifyBootstrapToken("cluster-legacy", "legacy-bootstrap-token")).isTrue();
        assertThat(storedBootstrapToken(jdbc, "cluster-legacy")).isBlank();
        assertThat(storedBootstrapTokenHash(jdbc, "cluster-legacy")).isNotBlank();
        assertThat(reports.findReport("report-legacy").orElseThrow().summary().mostLikelyCause())
            .isEqualTo("kubelet unavailable");

        Cluster newCluster = clusters.create(new ClusterCreateRequest(
            "post-migration-cluster",
            "test",
            null
        ));
        thresholds.replace(
            newCluster.clusterId(),
            Map.of("pid.critical.percent", 97.0),
            "post migration threshold check",
            "test"
        );
        assertThat(clusters.find(newCluster.clusterId())).isPresent();
        assertThat(thresholds.values(newCluster.clusterId()))
            .containsEntry("pid.critical.percent", 97.0);
    }

    private void verifyConcurrentClaimContract(
        EvidenceRepository evidence,
        AnalysisTaskRepository tasks,
        String clusterId
    ) {
        IntStream.range(0, 12).forEach(index -> evidence.saveAndEnqueue(
            new EvidenceBundle(
                null,
                clusterId,
                "db-worker-" + index,
                "DiskPressure",
                Instant.now(),
                Map.of("disk", Map.of("usage_percent", 90 + index))
            ),
            "database_concurrency_test",
            false,
            3
        ));
        Instant claimAt = Instant.now();
        Instant leaseUntil = claimAt.plusSeconds(30);
        CyclicBarrier barrier = new CyclicBarrier(10);
        List<AnalysisTask> combined = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            List<Future<List<AnalysisTask>>> futures = IntStream.range(0, 10)
                .mapToObj(index -> executor.submit(workerClaim(
                    tasks,
                    "db-claim-worker-" + index,
                    barrier,
                    claimAt,
                    leaseUntil
                )))
                .toList();
            for (Future<List<AnalysisTask>> future : futures) {
                combined.addAll(future.get());
            }
        } catch (Exception exception) {
            throw new AssertionError("database concurrent claim contract failed", exception);
        }

        assertThat(combined).isNotEmpty();
        Set<String> uniqueTaskIds = new HashSet<>(combined.stream().map(AnalysisTask::taskId).toList());
        assertThat(uniqueTaskIds).hasSameSizeAs(combined);
        combined.forEach(task -> assertThat(task.attemptCount()).isEqualTo(1));
    }

    private Callable<List<AnalysisTask>> workerClaim(
        AnalysisTaskRepository tasks,
        String leaseOwner,
        CyclicBarrier barrier,
        Instant claimAt,
        Instant leaseUntil
    ) {
        return () -> {
            barrier.await();
            return tasks.claim(leaseOwner, 2, claimAt, leaseUntil);
        };
    }

    private Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load();
    }

    private ActionRepository actionRepository(DataSource dataSource) {
        return new ActionRepository(new JdbcTemplate(dataSource), objectMapper());
    }

    private AuditRepository auditRepository(DataSource dataSource) {
        return new AuditRepository(new JdbcTemplate(dataSource), objectMapper());
    }

    private UserRepository userRepository(DataSource dataSource) {
        return new UserRepository(new JdbcTemplate(dataSource), new TokenService());
    }

    private UserSessionRepository userSessionRepository(DataSource dataSource) {
        return new UserSessionRepository(new JdbcTemplate(dataSource), new TokenService());
    }

    private AgentRepository agentRepository(DataSource dataSource) {
        return new AgentRepository(
            new JdbcTemplate(dataSource),
            objectMapper(),
            new TokenService(),
            clusterRepository(dataSource)
        );
    }

    private EvidenceRepository evidenceRepository(DataSource dataSource) {
        return new EvidenceRepository(
            new JdbcTemplate(dataSource),
            objectMapper(),
            analysisTaskRepository(dataSource),
            clusterRepository(dataSource)
        );
    }

    private AnalysisTaskRepository analysisTaskRepository(DataSource dataSource) {
        return new AnalysisTaskRepository(new JdbcTemplate(dataSource));
    }

    private IncidentRepository incidentRepository(DataSource dataSource) {
        return new IncidentRepository(new JdbcTemplate(dataSource), objectMapper());
    }

    private ReportRepository reportRepository(DataSource dataSource) {
        return new ReportRepository(new JdbcTemplate(dataSource), objectMapper());
    }

    private ClusterRepository clusterRepository(DataSource dataSource) {
        return new ClusterRepository(new JdbcTemplate(dataSource), new TokenService());
    }

    private ClusterThresholdRepository thresholdRepository(DataSource dataSource) {
        return new ClusterThresholdRepository(new JdbcTemplate(dataSource));
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }

    private String storedBootstrapToken(JdbcTemplate jdbc, String clusterId) {
        return jdbc.queryForObject(
            "SELECT bootstrap_token FROM clusters WHERE cluster_id = ?",
            String.class,
            clusterId
        );
    }

    private String storedBootstrapTokenHash(JdbcTemplate jdbc, String clusterId) {
        return jdbc.queryForObject(
            "SELECT bootstrap_token_hash FROM clusters WHERE cluster_id = ?",
            String.class,
            clusterId
        );
    }

    private DriverManagerDataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    private void reset(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DROP_ORDER.forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
    }
}
