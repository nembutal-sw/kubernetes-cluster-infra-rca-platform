package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentHealthService {
    private final RcaConsoleProperties properties;

    public AgentHealthService(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    public AgentHealthView classify(NodeAgent agent) {
        Instant now = Instant.now();
        long ageSeconds = agent.lastHeartbeatAt() == null
            ? Long.MAX_VALUE
            : Math.max(0, Duration.between(agent.lastHeartbeatAt(), now).getSeconds());
        long offlineAfter = Math.max(30, properties.getAgentOfflineAfterSeconds());
        long staleAfter = Math.max(15, offlineAfter / 2);
        List<String> reasons = new ArrayList<>();
        AgentHealthStatus status;

        if (agent.status() == AgentStatus.offline || ageSeconds > offlineAfter) {
            status = AgentHealthStatus.offline;
            reasons.add(agent.lastHeartbeatAt() == null
                ? "No heartbeat has been received."
                : "Heartbeat exceeded the offline threshold.");
        } else if (unauthorized(agent.health())) {
            status = AgentHealthStatus.unauthorized;
            reasons.add("Agent reported an authentication or authorization failure.");
        } else if (versionMismatch(agent.agentVersion())) {
            status = AgentHealthStatus.version_mismatch;
            reasons.add("Agent version does not match the configured expected version.");
        } else if (collectorDegraded(agent)) {
            status = AgentHealthStatus.collector_degraded;
            reasons.add("One or more collectors reported a degraded or failed state.");
        } else if (ageSeconds > staleAfter) {
            status = AgentHealthStatus.stale;
            reasons.add("Heartbeat is older than the stale threshold.");
        } else {
            status = AgentHealthStatus.healthy;
        }

        return new AgentHealthView(
            agent.agentId(),
            agent.clusterId(),
            agent.nodeName(),
            agent.agentVersion(),
            status,
            agent.status(),
            agent.supportedCollectors(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt(),
            ageSeconds == Long.MAX_VALUE ? -1 : ageSeconds,
            List.copyOf(reasons)
        );
    }

    private boolean versionMismatch(String version) {
        String expected = properties.getAgent().getExpectedVersion();
        return !expected.isBlank() && !expected.equals(version);
    }

    private boolean collectorDegraded(NodeAgent agent) {
        if (agent.status() == AgentStatus.degraded) {
            return true;
        }
        return containsState(agent.health(), List.of("degraded", "failed", "error", "unhealthy"));
    }

    private boolean unauthorized(Map<String, Object> health) {
        return containsState(health, List.of("unauthorized", "forbidden", "authentication_failed"));
    }

    private boolean containsState(Object value, List<String> states) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(item -> containsState(item, states));
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsState(item, states)) {
                    return true;
                }
            }
            return false;
        }
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).toLowerCase(Locale.ROOT);
        return states.stream().anyMatch(normalized::contains);
    }
}
