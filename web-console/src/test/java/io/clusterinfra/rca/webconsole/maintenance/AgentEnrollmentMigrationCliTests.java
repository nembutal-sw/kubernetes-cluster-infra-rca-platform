package io.clusterinfra.rca.webconsole.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentEnrollmentMigrationCliTests {
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:agent-enrollment-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE agent_enrollment_profiles (
                    cluster_id VARCHAR(64) PRIMARY KEY,
                    audience VARCHAR(255) NOT NULL,
                    profile_version BIGINT NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE node_agents (
                    cluster_id VARCHAR(64) NOT NULL,
                    node_name VARCHAR(255) NOT NULL,
                    node_token_revoked_at TIMESTAMP(6),
                    next_node_token_hash VARCHAR(512),
                    next_node_token_expires_at TIMESTAMP(6)
                )
                """);
            statement.execute("""
                INSERT INTO agent_enrollment_profiles
                    (cluster_id, audience, profile_version, updated_at)
                VALUES
                    ('cluster-a', 'https://kubernetes.default.svc', 1, CURRENT_TIMESTAMP),
                    ('cluster-b', 'https://kubernetes.default.svc', 3, CURRENT_TIMESTAMP),
                    ('cluster-safe', 'cluster-infra-rca-agent-enrollment', 2, CURRENT_TIMESTAMP)
                """);
            statement.execute("""
                INSERT INTO node_agents
                    (cluster_id, node_name, next_node_token_hash, next_node_token_expires_at)
                VALUES
                    ('cluster-a', 'node-a', 'pending-a', CURRENT_TIMESTAMP),
                    ('cluster-b', 'node-b', 'pending-b', CURRENT_TIMESTAMP)
                """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void auditReportsUnsafeProfilesWithoutChangingThem() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int status = AgentEnrollmentMigrationCli.execute(
            connection,
            options("audit", ""),
            new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        assertThat(status).isEqualTo(3);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
            .contains("unsafe_profile_count=2")
            .contains("unsafe_profile=cluster-a")
            .contains("unsafe_profile=cluster-b");
        assertThat(profile("cluster-a").audience())
            .isEqualTo("https://kubernetes.default.svc");
    }

    @Test
    void applyMigratesOnlyExplicitClustersAndRevokesTheirTokens() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int status = AgentEnrollmentMigrationCli.execute(
            connection,
            options("apply", "cluster-a"),
            new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        assertThat(status).isZero();
        assertThat(profile("cluster-a"))
            .isEqualTo(new Profile("cluster-infra-rca-agent-enrollment", 2));
        assertThat(profile("cluster-b"))
            .isEqualTo(new Profile("https://kubernetes.default.svc", 3));
        assertThat(revoked("cluster-a")).isTrue();
        assertThat(revoked("cluster-b")).isFalse();
        assertThat(bytes.toString(StandardCharsets.UTF_8))
            .contains("migrated_profile_count=1")
            .contains("revoked_node_token_count=1");
    }

    @Test
    void applyRequiresExactConfirmationAndClusterAllowlist() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RCA_AGENT_ENROLLMENT_MIGRATION_MODE", "apply");

        assertThatThrownBy(() -> AgentEnrollmentMigrationCli.Options.from(environment))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM");

        environment.put(
            "RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM",
            AgentEnrollmentMigrationCli.APPLY_CONFIRMATION
        );
        assertThatThrownBy(() -> AgentEnrollmentMigrationCli.Options.from(environment))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS");
    }

    private AgentEnrollmentMigrationCli.Options options(String mode, String clusters) {
        Map<String, String> environment = baseEnvironment();
        environment.put("RCA_AGENT_ENROLLMENT_MIGRATION_MODE", mode);
        if ("apply".equals(mode)) {
            environment.put(
                "RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM",
                AgentEnrollmentMigrationCli.APPLY_CONFIRMATION
            );
            environment.put("RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS", clusters);
        }
        return AgentEnrollmentMigrationCli.Options.from(environment);
    }

    private Map<String, String> baseEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("RCA_JDBC_URL", "jdbc:h2:mem:unused");
        environment.put("RCA_DB_USERNAME", "sa");
        environment.put("RCA_DB_PASSWORD", "");
        environment.put(
            "RCA_KUBERNETES_API_AUDIENCES",
            "https://kubernetes.default.svc,https://kubernetes.default.svc.cluster.local"
        );
        return environment;
    }

    private Profile profile(String clusterId) throws Exception {
        try (var statement = connection.prepareStatement("""
            SELECT audience, profile_version
            FROM agent_enrollment_profiles
            WHERE cluster_id = ?
            """)) {
            statement.setString(1, clusterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return new Profile(
                    resultSet.getString("audience"),
                    resultSet.getLong("profile_version")
                );
            }
        }
    }

    private boolean revoked(String clusterId) throws Exception {
        try (var statement = connection.prepareStatement("""
            SELECT node_token_revoked_at
            FROM node_agents
            WHERE cluster_id = ?
            """)) {
            statement.setString(1, clusterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getTimestamp("node_token_revoked_at") != null;
            }
        }
    }

    private record Profile(String audience, long version) {
    }
}
