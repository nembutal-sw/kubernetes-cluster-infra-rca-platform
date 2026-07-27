package io.clusterinfra.rca.webconsole.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AgentEnrollmentMigrationPackagedJarIT {
    private static final String API_AUDIENCE = "https://kubernetes.default.svc";
    private static final String TARGET_AUDIENCE = "cluster-infra-rca-agent-enrollment";

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL =
        new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MariaDBContainer<?> MARIADB =
        new MariaDBContainer<>("mariadb:11.4");

    @Test
    void packagedCliMigratesPostgresqlAndPassesFinalAudit() throws Exception {
        verifyPackagedCli(POSTGRESQL, "postgresql");
    }

    @Test
    void packagedCliMigratesMariadbAndPassesFinalAudit() throws Exception {
        verifyPackagedCli(MARIADB, "mariadb");
    }

    private void verifyPackagedCli(
        JdbcDatabaseContainer<?> database,
        String suffix
    ) throws Exception {
        migrate(database);
        String clusterId = "migration-" + suffix;
        seedUnsafeProfile(database, clusterId);

        ProcessResult apply = runCli(database, Map.of(
            "RCA_AGENT_ENROLLMENT_MIGRATION_MODE", "apply",
            "RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS", clusterId,
            "RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM",
            AgentEnrollmentMigrationCli.APPLY_CONFIRMATION
        ));

        assertThat(apply.exitCode()).isZero();
        assertThat(apply.output())
            .contains("migration_result=applied")
            .contains("migrated_profile_count=1")
            .contains("revoked_node_token_count=1");
        assertDatabaseState(database, clusterId);

        ProcessResult audit = runCli(database, Map.of(
            "RCA_AGENT_ENROLLMENT_MIGRATION_MODE", "audit"
        ));

        assertThat(audit.exitCode()).isZero();
        assertThat(audit.output())
            .contains("agent_enrollment_migration_mode=audit")
            .contains("unsafe_profile_count=0");
    }

    private void migrate(JdbcDatabaseContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    private void seedUnsafeProfile(
        JdbcDatabaseContainer<?> database,
        String clusterId
    ) throws Exception {
        try (Connection connection = connection(database);
             var cluster = connection.prepareStatement("""
                 INSERT INTO clusters (
                     cluster_id, name, environment, status, bootstrap_token, created_at
                 ) VALUES (?, ?, 'ci', 'active', 'legacy-placeholder', CURRENT_TIMESTAMP)
                 """);
             var profile = connection.prepareStatement("""
                 INSERT INTO agent_enrollment_profiles (
                     cluster_id, mode, api_server_url, ca_bundle_pem, ca_sha256,
                     audience, service_account_namespace, service_account_name,
                     bootstrap_fallback_allowed, created_at, updated_at
                 ) VALUES (
                     ?, 'kubernetes_token_review', 'https://kubernetes.default.svc',
                     'test-ca', ?, ?, 'rca-system', 'rca-agent', false,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                 )
                 """);
             var agent = connection.prepareStatement("""
                 INSERT INTO node_agents (
                     agent_id, cluster_id, node_name, node_token_hash, agent_version,
                     status, supported_collectors_json, metadata_json, health_json,
                     registered_at, next_node_token_hash, next_node_token_expires_at
                 ) VALUES (
                     ?, ?, 'worker-1', 'node-token-hash', '0.1.0', 'healthy',
                     '[]', '{}', '{}', CURRENT_TIMESTAMP, 'pending-token',
                     CURRENT_TIMESTAMP
                 )
                 """)) {
            cluster.setString(1, clusterId);
            cluster.setString(2, clusterId);
            cluster.executeUpdate();

            profile.setString(1, clusterId);
            profile.setString(2, "a".repeat(64));
            profile.setString(3, API_AUDIENCE);
            profile.executeUpdate();

            agent.setString(1, "agent-" + clusterId);
            agent.setString(2, clusterId);
            agent.executeUpdate();
        }
    }

    private ProcessResult runCli(
        JdbcDatabaseContainer<?> database,
        Map<String, String> additionalEnvironment
    ) throws Exception {
        Path projectDirectory = Path.of(
            System.getProperty("basedir", System.getProperty("user.dir"))
        ).toAbsolutePath();
        Path jar = projectDirectory.resolve(
            "target/cluster-infra-rca-platform-0.1.0.jar"
        );
        assertThat(Files.isRegularFile(jar)).isTrue();

        String executable = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        ).toString();
        ProcessBuilder builder = new ProcessBuilder(
            executable,
            "-Dloader.main=io.clusterinfra.rca.webconsole.maintenance.AgentEnrollmentMigrationCli",
            "-cp",
            jar.toString(),
            "org.springframework.boot.loader.launch.PropertiesLauncher"
        );
        builder.directory(projectDirectory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("RCA_JDBC_URL", database.getJdbcUrl());
        environment.put("RCA_DB_USERNAME", database.getUsername());
        environment.put("RCA_DB_PASSWORD", database.getPassword());
        environment.put("RCA_KUBERNETES_API_AUDIENCES", API_AUDIENCE);
        environment.put(
            "RCA_AGENT_ENROLLMENT_MIGRATION_TARGET_AUDIENCE",
            TARGET_AUDIENCE
        );
        environment.putAll(additionalEnvironment);

        Process process = builder.start();
        boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertThat(completed).as("packaged migration CLI completed").isTrue();
        String output = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        return new ProcessResult(process.exitValue(), output);
    }

    private void assertDatabaseState(
        JdbcDatabaseContainer<?> database,
        String clusterId
    ) throws Exception {
        try (Connection connection = connection(database);
             var statement = connection.prepareStatement("""
                 SELECT p.audience, p.profile_version, n.node_token_revoked_at,
                        n.next_node_token_hash, n.next_node_token_expires_at
                 FROM agent_enrollment_profiles p
                 JOIN node_agents n ON n.cluster_id = p.cluster_id
                 WHERE p.cluster_id = ?
                 """)) {
            statement.setString(1, clusterId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("audience")).isEqualTo(TARGET_AUDIENCE);
                assertThat(result.getLong("profile_version")).isEqualTo(2);
                assertThat(result.getTimestamp("node_token_revoked_at")).isNotNull();
                assertThat(result.getString("next_node_token_hash")).isNull();
                assertThat(result.getTimestamp("next_node_token_expires_at")).isNull();
            }
        }
    }

    private Connection connection(JdbcDatabaseContainer<?> database) throws Exception {
        return DriverManager.getConnection(
            database.getJdbcUrl(),
            database.getUsername(),
            database.getPassword()
        );
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
