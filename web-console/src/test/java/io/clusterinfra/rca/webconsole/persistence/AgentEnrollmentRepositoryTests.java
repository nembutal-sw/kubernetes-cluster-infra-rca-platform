package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AgentEnrollmentRepositoryTests {
    private JdbcTemplate jdbc;
    private AgentEnrollmentRepository enrollments;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-enrollment-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        enrollments = new AgentEnrollmentRepository(jdbc, new ObjectMapper());
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, bootstrap_token,
                     bootstrap_token_hash, status, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, '', ?, 'registered', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
            "cluster-1",
            "cluster-1",
            "test",
            null,
            "test-hash"
        );
    }

    @Test
    void persistsReviewerCredentialLifecycleWithoutStoringTokenMaterial() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant previousValidUntil = now.plusSeconds(900);
        var configuration = new AgentEnrollmentRepository.AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "test-sha",
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            3,
            "/var/run/secrets/cluster-infra-rca-reviewers/current/token",
            8,
            "/var/run/secrets/cluster-infra-rca-reviewers/previous/token",
            previousValidUntil,
            now,
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            Map.of("cluster-infra-rca.io/cluster-id", "cluster-1"),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            null,
            false,
            now,
            now
        );

        var saved = enrollments.save(configuration);
        var loaded = enrollments.findConfiguration("cluster-1").orElseThrow();

        assertThat(saved.reviewerCredentialVersion()).isEqualTo(8);
        assertThat(loaded.reviewerTokenPath()).isEqualTo(configuration.reviewerTokenPath());
        assertThat(loaded.reviewerPreviousTokenPath())
            .isEqualTo(configuration.reviewerPreviousTokenPath());
        assertThat(loaded.reviewerPreviousValidUntil()).isEqualTo(previousValidUntil);
        assertThat(loaded.reviewerCredentialRotatedAt()).isEqualTo(now);
        assertThat(jdbc.queryForObject(
            """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'agent_enrollment_profiles'
                  AND column_name LIKE 'reviewer_%'
                """,
            Integer.class
        )).isEqualTo(5);
    }
}
