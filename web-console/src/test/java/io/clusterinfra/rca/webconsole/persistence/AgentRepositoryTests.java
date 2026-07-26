package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.clusterinfra.rca.webconsole.TestSecurity.opaqueTokenHasher;
import static io.clusterinfra.rca.webconsole.TestSecurity.passwordHasher;
import static io.clusterinfra.rca.webconsole.TestSecurity.tokenGenerator;
import static io.clusterinfra.rca.webconsole.TestSecurity.agentSecurityPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AgentRepositoryTests {
    private JdbcTemplate jdbc;
    private ClusterRepository clusters;
    private AgentRepository agents;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        clusters = new ClusterRepository(
            jdbc,
            tokenGenerator(),
            opaqueTokenHasher(),
            passwordHasher()
        );
        agents = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            opaqueTokenHasher(),
            passwordHasher(),
            clusters,
            agentSecurityPolicy()
        );
    }

    @Test
    void registerStoresHashedNodeTokenAndMarksClusterActive() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));

        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("disk", "inode"),
            Map.of("kernel", "6.8")
        ));

        assertThat(registered.nodeToken()).isNotBlank();
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a"))
            .startsWith("hmac_sha256$v1$")
            .isNotEqualTo(registered.nodeToken());
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", registered.nodeToken())).isTrue();
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", "wrong-token")).isFalse();
        assertThat(agents.find(cluster.clusterId(), "node-a")).isPresent();
        assertThat(agents.list(cluster.clusterId())).hasSize(1);
        assertThat(agents.listAll()).hasSize(1);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM clusters WHERE cluster_id = ?",
            String.class,
            cluster.clusterId()
        )).isEqualTo("active");
        assertThat(jdbc.queryForObject(
            "SELECT last_seen_at FROM clusters WHERE cluster_id = ?",
            Timestamp.class,
            cluster.clusterId()
        )).isNotNull();
    }

    @Test
    void heartbeatUpdatesExistingAgentAndNormalizesNodeName() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            List.of("disk"),
            Map.of()
        ));

        var heartbeat = agents.heartbeat(new NodeAgentHeartbeatRequest(
            cluster.clusterId(),
            " node-a ",
            cluster.bootstrapToken(),
            registered.nodeToken(),
            AgentStatus.degraded,
            "0.1.1",
            "2",
            List.of("disk", "kernel"),
            Map.of("ready", false, "reason", "collector_degraded")
        ));

        assertThat(heartbeat).isPresent();
        assertThat(heartbeat.orElseThrow().nodeName()).isEqualTo("node-a");
        assertThat(heartbeat.orElseThrow().agentVersion()).isEqualTo("0.1.1");
        assertThat(heartbeat.orElseThrow().agentProtocolVersion()).isEqualTo("2");
        assertThat(heartbeat.orElseThrow().status()).isEqualTo(AgentStatus.degraded);
        assertThat(heartbeat.orElseThrow().supportedCollectors()).containsExactly("disk", "kernel");
        assertThat(heartbeat.orElseThrow().health()).containsEntry("ready", false);
        assertThat(agents.heartbeat(new NodeAgentHeartbeatRequest(
            cluster.clusterId(),
            "missing-node",
            cluster.bootstrapToken(),
            registered.nodeToken(),
            AgentStatus.healthy,
            "0.1.1",
            List.of(),
            Map.of()
        ))).isEmpty();
    }

    @Test
    void reregisterRotatesNodeTokenAndPreservesLatestHealth() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var first = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            List.of("disk"),
            Map.of("generation", "first")
        ));
        agents.heartbeat(new NodeAgentHeartbeatRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            first.nodeToken(),
            AgentStatus.healthy,
            "0.1.1",
            List.of("disk", "kernel"),
            Map.of("ready", true)
        ));

        var second = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            " node-a ",
            cluster.bootstrapToken(),
            "0.2.0",
            List.of("disk", "kernel", "network"),
            Map.of("generation", "second")
        ));

        assertThat(second.nodeToken()).isNotBlank().isNotEqualTo(first.nodeToken());
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", first.nodeToken())).isFalse();
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", second.nodeToken())).isTrue();
        var stored = agents.find(cluster.clusterId(), "node-a").orElseThrow();
        assertThat(stored.agentVersion()).isEqualTo("0.2.0");
        assertThat(stored.metadata()).containsEntry("generation", "second");
        assertThat(stored.health()).containsEntry("ready", true);
        assertThat(stored.supportedCollectors()).containsExactly("disk", "kernel", "network");
    }

    @Test
    void nodeTokenCanBeRotatedAndRevokedIndependently() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("disk"),
            Map.of()
        ));

        String rotated = agents.rotateNodeToken(cluster.clusterId(), "node-a");

        assertThat(rotated).isNotBlank().isNotEqualTo(registered.nodeToken());
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", registered.nodeToken())).isTrue();
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", rotated)).isTrue();
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", registered.nodeToken())).isFalse();
        assertThat(agents.revokeNodeToken(cluster.clusterId(), "node-a")).isTrue();
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", rotated)).isFalse();
        assertThat(agents.revokeNodeToken(cluster.clusterId(), "missing-node")).isFalse();
    }

    @Test
    void legacyPbkdf2NodeTokenIsUpgradedAfterSuccessfulVerification() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("disk"),
            Map.of()
        ));
        PasswordHasher legacy = passwordHasher();
        jdbc.update(
            "UPDATE node_agents SET node_token_hash = ? WHERE cluster_id = ? AND node_name = ?",
            legacy.hash(registered.nodeToken()),
            cluster.clusterId(),
            "node-a"
        );

        assertThat(agents.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            registered.nodeToken()
        )).isTrue();
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a"))
            .startsWith("hmac_sha256$v1$");
    }

    @Test
    void previousPepperNodeTokenIsProgressivelyRehashedAndRemainsRollingCompatible() {
        String oldPepper = "old-node-token-pepper-value-with-32-bytes";
        String newPepper = "new-node-token-pepper-value-with-32-bytes";
        var oldHasher = opaqueTokenHasher(oldPepper, "key-old", "", "v1", false);
        ClusterRepository oldClusters = new ClusterRepository(
            jdbc,
            tokenGenerator(),
            oldHasher,
            passwordHasher()
        );
        AgentRepository oldAgents = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            oldHasher,
            passwordHasher(),
            oldClusters,
            agentSecurityPolicy()
        );
        var cluster = oldClusters.create(
            new ClusterCreateRequest("prod-a", "prod", null)
        );
        var registered = oldAgents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("disk"),
            Map.of()
        ));
        var rotatingHasher = opaqueTokenHasher(
            newPepper,
            "key-new",
            "key-old=" + oldPepper,
            "v2",
            true
        );
        AgentRepository rotatingAgents = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            rotatingHasher,
            passwordHasher(),
            oldClusters,
            agentSecurityPolicy()
        );

        assertThat(rotatingAgents.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            registered.nodeToken()
        )).isTrue();
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a"))
            .startsWith("hmac_sha256$v2$key-new$");

        var preparedOldHasher = opaqueTokenHasher(
            oldPepper,
            "key-old",
            "key-new=" + newPepper,
            "v1",
            false
        );
        AgentRepository preparedOldAgents = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            preparedOldHasher,
            passwordHasher(),
            oldClusters,
            agentSecurityPolicy()
        );
        assertThat(preparedOldAgents.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            registered.nodeToken()
        )).isTrue();
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a"))
            .startsWith("hmac_sha256$v2$key-new$");

        String pendingToken = rotatingAgents.rotateNodeToken(
            cluster.clusterId(),
            "node-a"
        );
        assertThat(preparedOldAgents.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            pendingToken
        )).isTrue();
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a"))
            .startsWith("hmac_sha256$v2$key-new$");
    }

    @Test
    void legacyNodeTokenIsRejectedWhenConcurrentRotationWinsTheUpgradeRace() throws Exception {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("disk"),
            Map.of()
        ));
        jdbc.update(
            "UPDATE node_agents SET node_token_hash = ? WHERE cluster_id = ? AND node_name = ?",
            passwordHasher().hash(registered.nodeToken()),
            cluster.clusterId(),
            "node-a"
        );
        BlockingPasswordHasher blocking = new BlockingPasswordHasher();
        AgentRepository racingRepository = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            opaqueTokenHasher(),
            blocking,
            clusters,
            agentSecurityPolicy()
        );

        CompletableFuture<Boolean> verification = CompletableFuture.supplyAsync(
            () -> racingRepository.verifyNodeToken(
                cluster.clusterId(),
                "node-a",
                registered.nodeToken()
            )
        );
        assertThat(blocking.awaitMatch()).isTrue();
        jdbc.update(
            "UPDATE node_agents SET node_token_hash = ? WHERE cluster_id = ? AND node_name = ?",
            opaqueTokenHasher().hash("replacement-token"),
            cluster.clusterId(),
            "node-a"
        );
        blocking.release();

        assertThat(verification.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void trustedEnrollmentMetadataOverridesAgentInputWithoutRejectingNullMetadataValues() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        insertEnrollmentProfile(cluster.clusterId(), 1);
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("optional_runtime", null);
        supplied.put("_enrollment", Map.of("method", "forged"));

        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            null,
            "0.1.0",
            "2",
            List.of("node"),
            supplied
        ), Map.of(
            "method", "kubernetes_token_review",
            "profile_version", 1,
            "service_account_uid", "service-account-uid",
            "daemonset_uid", "daemonset-uid-1",
            "pod_uid", "pod-uid-1"
        ));

        assertThat(registered.metadata()).containsEntry("optional_runtime", null);
        assertThat(registered.metadata().get("_enrollment"))
            .isEqualTo(Map.of(
                "method", "kubernetes_token_review",
                "profile_version", 1,
                "service_account_uid", "service-account-uid",
                "daemonset_uid", "daemonset-uid-1",
                "pod_uid", "pod-uid-1"
            ));
        assertThat(agents.find(cluster.clusterId(), "node-a").orElseThrow().metadata())
            .containsEntry("optional_runtime", null);
    }

    @Test
    void activeKubernetesIdentityRejectsReplacementPodUntilAdminRevokesIt() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        insertEnrollmentProfile(cluster.clusterId(), 1);
        var request = new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            null,
            "0.1.0",
            "2",
            List.of("node"),
            Map.of()
        );

        var first = agents.register(request, trustedIdentity(1, "pod-uid-1", "daemonset-uid-1"));

        assertThatThrownBy(() -> agents.register(
            request,
            trustedIdentity(1, "pod-uid-2", "daemonset-uid-1")
        ))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("409")
            .hasMessageContaining("revoke");
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", first.nodeToken())).isTrue();

        assertThat(agents.revokeNodeToken(cluster.clusterId(), "node-a")).isTrue();
        var replacement = agents.register(
            request,
            trustedIdentity(1, "pod-uid-2", "daemonset-uid-1")
        );
        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", replacement.nodeToken())).isTrue();
    }

    @Test
    void nodeTokenStopsAuthenticatingWhenEnrollmentProfileVersionChanges() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        insertEnrollmentProfile(cluster.clusterId(), 1);
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            null,
            "0.1.0",
            "2",
            List.of("node"),
            Map.of()
        ), trustedIdentity(1, "pod-uid-1", "daemonset-uid-1"));

        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", registered.nodeToken())).isTrue();
        jdbc.update(
            "UPDATE agent_enrollment_profiles SET profile_version = 2 WHERE cluster_id = ?",
            cluster.clusterId()
        );

        assertThat(agents.verifyNodeToken(cluster.clusterId(), "node-a", registered.nodeToken())).isFalse();
    }

    @Test
    void legacyUnboundNodeTokenIsRejectedWhenAnEnrollmentProfileExists() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("node"),
            Map.of()
        ));
        insertEnrollmentProfile(cluster.clusterId(), 1);

        assertThat(agents.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            registered.nodeToken()
        )).isFalse();
    }

    @Test
    void legacyUnboundNodeTokenIsAcceptedOnlyDuringExplicitGracePeriod() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
        var registered = agents.register(new NodeAgentRegisterRequest(
            cluster.clusterId(),
            "node-a",
            cluster.bootstrapToken(),
            "0.1.0",
            "2",
            List.of("node"),
            Map.of()
        ));
        insertEnrollmentProfile(cluster.clusterId(), 1);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setLegacyUnboundAgentTokenGraceUntil(
            Instant.now().plus(java.time.Duration.ofHours(1)).toString()
        );
        AgentRepository graceRepository = new AgentRepository(
            jdbc,
            objectMapper(),
            tokenGenerator(),
            opaqueTokenHasher(),
            passwordHasher(),
            clusters,
            new AgentSecurityPolicy(properties)
        );

        assertThat(graceRepository.verifyNodeToken(
            cluster.clusterId(),
            "node-a",
            registered.nodeToken()
        )).isTrue();
    }

    private Map<String, Object> trustedIdentity(long version, String podUid, String daemonSetUid) {
        return Map.of(
            "method", "kubernetes_token_review",
            "profile_version", version,
            "service_account_uid", "service-account-uid",
            "daemonset_uid", daemonSetUid,
            "pod_uid", podUid
        );
    }

    private void insertEnrollmentProfile(String clusterId, long version) {
        jdbc.update(
            """
                INSERT INTO agent_enrollment_profiles
                    (cluster_id, mode, api_server_url, ca_bundle_pem, ca_sha256, audience,
                     service_account_namespace, service_account_name, profile_version,
                     reviewer_token_path, expected_service_account_uid,
                     expected_daemonset_name, expected_daemonset_uid,
                     required_pod_labels_json, allowed_image_digest,
                     bootstrap_fallback_allowed, created_at, updated_at)
                VALUES (?, 'kubernetes_token_review', 'https://kubernetes.example:6443',
                        'test-ca', 'test-ca-sha', 'cluster-infra-rca-agent-enrollment',
                        'rca-system', 'cluster-infra-rca-agent', ?,
                        '/var/run/secrets/kubernetes.io/serviceaccount/token',
                        'service-account-uid', 'cluster-infra-rca-agent', 'daemonset-uid-1',
                        '{}', 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
            clusterId,
            version
        );
    }

    private String storedNodeTokenHash(String clusterId, String nodeName) {
        return jdbc.queryForObject(
            "SELECT node_token_hash FROM node_agents WHERE cluster_id = ? AND node_name = ?",
            String.class,
            clusterId,
            nodeName
        );
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
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
