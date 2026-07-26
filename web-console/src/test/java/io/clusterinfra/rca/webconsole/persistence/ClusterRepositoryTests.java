package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static io.clusterinfra.rca.webconsole.TestSecurity.clusterRepository;
import static io.clusterinfra.rca.webconsole.TestSecurity.opaqueTokenHasher;
import static io.clusterinfra.rca.webconsole.TestSecurity.passwordHasher;
import static io.clusterinfra.rca.webconsole.TestSecurity.tokenGenerator;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ClusterRepositoryTests {
    private JdbcTemplate jdbc;
    private ClusterRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:cluster-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = clusterRepository(jdbc);
    }

    @Test
    void createStoresOnlyBootstrapTokenHashAndVerifiesToken() {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", "production"));

        assertThat(cluster.bootstrapToken()).isNotBlank();
        assertThat(storedBootstrapToken(cluster.clusterId())).isBlank();
        assertThat(storedBootstrapTokenHash(cluster.clusterId())).startsWith("hmac_sha256$v1$");
        assertThat(repository.verifyBootstrapToken(cluster.clusterId(), cluster.bootstrapToken())).isTrue();
        assertThat(repository.verifyBootstrapToken(cluster.clusterId(), "wrong-token")).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT bootstrap_token_last_used_at FROM clusters WHERE cluster_id = ?",
            java.sql.Timestamp.class,
            cluster.clusterId()
        )).isNotNull();
        assertThat(repository.find(cluster.clusterId()).orElseThrow().bootstrapToken()).isNull();
    }

    @Test
    void rotateInvalidatesOldBootstrapToken() {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", null));

        var rotated = repository.rotateBootstrapToken(cluster.clusterId());

        assertThat(rotated.bootstrapToken()).isNotBlank().isNotEqualTo(cluster.bootstrapToken());
        assertThat(repository.verifyBootstrapToken(cluster.clusterId(), cluster.bootstrapToken())).isFalse();
        assertThat(repository.verifyBootstrapToken(cluster.clusterId(), rotated.bootstrapToken())).isTrue();
        assertThat(storedBootstrapToken(cluster.clusterId())).isBlank();
        assertThat(storedBootstrapTokenHash(cluster.clusterId())).isNotBlank();
    }

    @Test
    void bootstrapTokenHonorsTtlAndExplicitRevocation() {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", null));
        jdbc.update(
            "UPDATE clusters SET bootstrap_token_rotated_at = ? WHERE cluster_id = ?",
            Timestamp.from(Instant.now().minus(Duration.ofHours(2))),
            cluster.clusterId()
        );

        assertThat(repository.verifyBootstrapToken(
            cluster.clusterId(), cluster.bootstrapToken(), Duration.ofMinutes(30)
        )).isFalse();

        var rotated = repository.rotateBootstrapToken(cluster.clusterId());
        assertThat(repository.verifyBootstrapToken(
            cluster.clusterId(), rotated.bootstrapToken(), Duration.ofMinutes(30)
        )).isTrue();
        assertThat(repository.revokeBootstrapToken(cluster.clusterId())).isTrue();
        assertThat(repository.verifyBootstrapToken(
            cluster.clusterId(), rotated.bootstrapToken(), Duration.ofMinutes(30)
        )).isFalse();
    }

    @Test
    void legacyPbkdf2BootstrapTokenIsUpgradedWithoutChangingCredentialAge() {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", null));
        Instant rotatedAt = Instant.now()
            .minus(Duration.ofMinutes(5))
            .truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
            "UPDATE clusters SET bootstrap_token_hash = ?, bootstrap_token_rotated_at = ? WHERE cluster_id = ?",
            passwordHasher().hash(cluster.bootstrapToken()),
            Timestamp.from(rotatedAt),
            cluster.clusterId()
        );

        assertThat(repository.verifyBootstrapToken(
            cluster.clusterId(),
            cluster.bootstrapToken()
        )).isTrue();
        assertThat(storedBootstrapTokenHash(cluster.clusterId())).startsWith("hmac_sha256$v1$");
        assertThat(jdbc.queryForObject(
            "SELECT bootstrap_token_rotated_at FROM clusters WHERE cluster_id = ?",
            Timestamp.class,
            cluster.clusterId()
        ).toInstant()).isEqualTo(rotatedAt);
    }

    @Test
    void legacyBootstrapTokenIsRejectedWhenConcurrentRotationWinsTheUpgradeRace() throws Exception {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", null));
        jdbc.update(
            "UPDATE clusters SET bootstrap_token_hash = ? WHERE cluster_id = ?",
            passwordHasher().hash(cluster.bootstrapToken()),
            cluster.clusterId()
        );
        BlockingPasswordHasher blocking = new BlockingPasswordHasher();
        ClusterRepository racingRepository = new ClusterRepository(
            jdbc,
            tokenGenerator(),
            opaqueTokenHasher(),
            blocking
        );

        CompletableFuture<Boolean> verification = CompletableFuture.supplyAsync(
            () -> racingRepository.verifyBootstrapToken(
                cluster.clusterId(),
                cluster.bootstrapToken()
            )
        );
        assertThat(blocking.awaitMatch()).isTrue();
        jdbc.update(
            "UPDATE clusters SET bootstrap_token_hash = ?, bootstrap_token_rotated_at = ? WHERE cluster_id = ?",
            opaqueTokenHasher().hash("replacement-token"),
            Timestamp.from(Instant.now()),
            cluster.clusterId()
        );
        blocking.release();

        assertThat(verification.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void deleteRemovesClusterAndRegisteredAgents() {
        var cluster = repository.create(new ClusterCreateRequest("prod-a", "prod", null));
        jdbc.update(
            """
                INSERT INTO node_agents
                    (agent_id, cluster_id, node_name, node_token_hash, agent_version,
                     agent_protocol_version, status, supported_collectors_json, metadata_json,
                     health_json, registered_at, last_heartbeat_at)
                VALUES ('agent-1', ?, 'node-a', 'hash', '0.1.0', '1', 'healthy',
                        '[]', '{}', '{}', CURRENT_TIMESTAMP, NULL)
            """,
            cluster.clusterId()
        );
        jdbc.update(
            """
                INSERT INTO cluster_threshold_overrides
                    (cluster_id, threshold_key, threshold_value, reason, updated_by, created_at, updated_at)
                VALUES (?, 'disk.critical.percent', 95.0, 'test', 'operator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
            cluster.clusterId()
        );

        assertThat(repository.delete(cluster.clusterId())).isTrue();

        assertThat(repository.find(cluster.clusterId())).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM node_agents WHERE cluster_id = ?",
            Integer.class,
            cluster.clusterId()
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM cluster_threshold_overrides WHERE cluster_id = ?",
            Integer.class,
            cluster.clusterId()
        )).isZero();
        assertThat(repository.delete(cluster.clusterId())).isFalse();
    }

    private String storedBootstrapToken(String clusterId) {
        return jdbc.queryForObject(
            "SELECT bootstrap_token FROM clusters WHERE cluster_id = ?",
            String.class,
            clusterId
        );
    }

    private String storedBootstrapTokenHash(String clusterId) {
        return jdbc.queryForObject(
            "SELECT bootstrap_token_hash FROM clusters WHERE cluster_id = ?",
            String.class,
            clusterId
        );
    }

    private static final class BlockingPasswordHasher extends PasswordHasher {
        private final CountDownLatch matched = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public boolean matches(String password, String encodedHash) {
            boolean result = super.matches(password, encodedHash);
            matched.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for concurrent token update");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for concurrent token update", exception);
            }
            return result;
        }

        private boolean awaitMatch() throws InterruptedException {
            return matched.await(10, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }
}
