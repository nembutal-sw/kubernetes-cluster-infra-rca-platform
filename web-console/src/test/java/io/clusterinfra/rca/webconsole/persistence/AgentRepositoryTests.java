package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
        TokenService tokens = new TokenService();
        clusters = new ClusterRepository(jdbc, tokens);
        agents = new AgentRepository(jdbc, objectMapper(), tokens, clusters);
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
        assertThat(storedNodeTokenHash(cluster.clusterId(), "node-a")).isNotEqualTo(registered.nodeToken());
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
    void trustedEnrollmentMetadataOverridesAgentInputWithoutRejectingNullMetadataValues() {
        var cluster = clusters.create(new ClusterCreateRequest("prod-a", "prod", null));
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
            "pod_uid", "pod-uid-1"
        ));

        assertThat(registered.metadata()).containsEntry("optional_runtime", null);
        assertThat(registered.metadata().get("_enrollment"))
            .isEqualTo(Map.of("method", "kubernetes_token_review", "pod_uid", "pod-uid-1"));
        assertThat(agents.find(cluster.clusterId(), "node-a").orElseThrow().metadata())
            .containsEntry("optional_runtime", null);
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
}
