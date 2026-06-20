package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentHealthServiceTests {
    private final RcaConsoleProperties properties = new RcaConsoleProperties();
    private final AgentHealthService service = new AgentHealthService(properties);

    @Test
    void classifiesFreshAndDegradedAgents() {
        NodeAgent healthy = agent("0.1.0", AgentStatus.healthy, Map.of(), Instant.now());
        NodeAgent degraded = agent(
            "0.1.0",
            AgentStatus.healthy,
            Map.of("collectors", Map.of("kernel", "failed")),
            Instant.now()
        );

        assertThat(service.classify(healthy).healthStatus()).isEqualTo(AgentHealthStatus.healthy);
        assertThat(service.classify(degraded).healthStatus())
            .isEqualTo(AgentHealthStatus.collector_degraded);
    }

    @Test
    void versionMismatchAndOfflineTakePrecedence() {
        properties.getAgent().setExpectedVersion("0.2.0");
        NodeAgent mismatch = agent("0.1.0", AgentStatus.healthy, Map.of(), Instant.now());
        NodeAgent offline = agent(
            "0.1.0",
            AgentStatus.healthy,
            Map.of(),
            Instant.now().minusSeconds(properties.getAgentOfflineAfterSeconds() + 10L)
        );

        assertThat(service.classify(mismatch).healthStatus())
            .isEqualTo(AgentHealthStatus.version_mismatch);
        assertThat(service.classify(offline).healthStatus()).isEqualTo(AgentHealthStatus.offline);
    }

    @Test
    void unsupportedProtocolIsReportedAsVersionMismatch() {
        NodeAgent incompatible = new NodeAgent(
            "agent-1",
            "cluster-1",
            "worker-1",
            "0.1.0",
            "2",
            AgentStatus.healthy,
            List.of("disk", "kernel"),
            Map.of(),
            Map.of(),
            Instant.now().minusSeconds(60),
            Instant.now()
        );

        var health = service.classify(incompatible);
        assertThat(health.healthStatus()).isEqualTo(AgentHealthStatus.version_mismatch);
        assertThat(health.reasons()).anyMatch(reason -> reason.contains("protocol 2"));
    }

    private NodeAgent agent(
        String version,
        AgentStatus status,
        Map<String, Object> health,
        Instant heartbeat
    ) {
        return new NodeAgent(
            "agent-1",
            "cluster-1",
            "worker-1",
            version,
            status,
            List.of("disk", "kernel"),
            Map.of(),
            health,
            Instant.now().minusSeconds(60),
            heartbeat
        );
    }
}
