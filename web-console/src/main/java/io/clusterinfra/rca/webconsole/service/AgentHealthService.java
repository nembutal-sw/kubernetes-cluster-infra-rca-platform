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
        } else if (versionMismatch(agent)) {
            status = AgentHealthStatus.version_mismatch;
            reasons.addAll(versionMismatchReasons(agent));
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
            agent.agentProtocolVersion(),
            status,
            agent.status(),
            agent.supportedCollectors(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt(),
            ageSeconds == Long.MAX_VALUE ? -1 : ageSeconds,
            properties.getAgent().getProtocolVersion(),
            List.copyOf(reasons)
        );
    }

    private boolean versionMismatch(NodeAgent agent) {
        return !versionMismatchReasons(agent).isEmpty();
    }

    private List<String> versionMismatchReasons(NodeAgent agent) {
        List<String> reasons = new ArrayList<>();
        String expected = properties.getAgent().getExpectedVersion();
        if (!expected.isBlank() && !expected.equals(agent.agentVersion())) {
            reasons.add("Agent version does not match the configured expected version.");
        }
        String minimum = properties.getAgent().getMinimumSupportedVersion();
        if (!minimum.isBlank() && compareVersion(agent.agentVersion(), minimum) < 0) {
            reasons.add("Agent version is below the minimum supported version " + minimum + ".");
        }
        int protocol = integerVersion(agent.agentProtocolVersion());
        int minimumProtocol = integerVersion(properties.getAgent().getMinimumSupportedProtocolVersion());
        int platformProtocol = integerVersion(properties.getAgent().getProtocolVersion());
        if (protocol < minimumProtocol || protocol > platformProtocol) {
            reasons.add(
                "Agent protocol " + agent.agentProtocolVersion()
                    + " is outside the supported range "
                    + properties.getAgent().getMinimumSupportedProtocolVersion()
                    + "-" + properties.getAgent().getProtocolVersion() + "."
            );
        }
        return reasons;
    }

    private int compareVersion(String left, String right) {
        int[] leftParts = numericParts(left);
        int[] rightParts = numericParts(right);
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftValue = index < leftParts.length ? leftParts[index] : 0;
            int rightValue = index < rightParts.length ? rightParts[index] : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private int[] numericParts(String version) {
        if (version == null || version.isBlank()) {
            return new int[] {-1};
        }
        String normalized = version.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        String[] parts = normalized.split("\\.");
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                values[index] = Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException exception) {
                values[index] = -1;
            }
        }
        return values;
    }

    private int integerVersion(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
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
