package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EvidenceQualityAnalyzer {
    private static final Set<String> META_COLLECTORS = Set.of("_meta");
    private final AgentRepository agents;
    private final AgentHealthService agentHealth;
    private final RcaConsoleProperties properties;

    public EvidenceQualityAnalyzer(
        AgentRepository agents,
        AgentHealthService agentHealth,
        RcaConsoleProperties properties
    ) {
        this.agents = agents;
        this.agentHealth = agentHealth;
        this.properties = properties;
    }

    public Map<String, Object> assess(EvidenceBundle evidence) {
        Instant now = Instant.now();
        long staleAfterSeconds = Math.max(300, properties.getAgentOfflineAfterSeconds() * 2L);
        long ageSeconds = evidence.collectedAt() == null
            ? Long.MAX_VALUE
            : Math.max(0, Duration.between(evidence.collectedAt(), now).getSeconds());
        boolean stale = ageSeconds > staleAfterSeconds;

        LinkedHashSet<String> collected = new LinkedHashSet<>();
        if (evidence.collectors() != null) {
            evidence.collectors().keySet().stream()
                .filter(key -> !META_COLLECTORS.contains(key))
                .forEach(collected::add);
        }
        List<String> expected = expectedCollectors(evidence.alertName());
        List<String> missing = expected.stream()
            .filter(item -> !collected.contains(item))
            .toList();
        List<String> failed = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (evidence.collectors() != null) {
            evidence.collectors().forEach((collector, value) -> {
                String state = collectorState(value);
                if (state.isBlank()) {
                    return;
                }
                if (List.of("error", "failed", "failure", "unhealthy", "unauthorized", "forbidden").contains(state)) {
                    failed.add(collector);
                } else if (List.of("degraded", "limited", "timeout", "unavailable").contains(state)) {
                    degraded.add(collector);
                } else if (List.of("unsupported", "disabled").contains(state)) {
                    unsupported.add(collector);
                }
            });
        }

        Map<String, Object> agent = agentQuality(evidence.clusterId(), evidence.nodeName());
        int penalty = 0;
        List<String> notes = new ArrayList<>();
        if (stale) {
            penalty += 25;
            notes.add("Evidence was collected outside the freshness threshold.");
        }
        if (!missing.isEmpty()) {
            notes.add("Expected collectors are missing.");
        }
        if (!failed.isEmpty()) {
            penalty += Math.min(30, failed.size() * 10);
            notes.add("One or more collectors failed.");
        }
        if (!degraded.isEmpty() || !unsupported.isEmpty()) {
            penalty += Math.min(15, (degraded.size() + unsupported.size()) * 5);
            notes.add("One or more collectors were degraded, disabled, or unsupported.");
        }
        String agentStatus = String.valueOf(agent.getOrDefault("status", "unknown"));
        if (List.of("offline", "unauthorized", "collector_degraded", "version_mismatch").contains(agentStatus)) {
            penalty += 20;
            notes.add("Agent health can reduce RCA confidence.");
        } else if ("stale".equals(agentStatus)) {
            penalty += 10;
            notes.add("Agent heartbeat is stale.");
        } else if (Boolean.FALSE.equals(agent.get("registered"))) {
            notes.add("No registered agent was found for the node.");
        }
        penalty = Math.min(70, penalty);
        int score = Math.max(0, 100 - penalty);
        String status = status(stale, missing, failed, degraded, unsupported, agentStatus, score);

        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("collected_at", evidence.collectedAt() == null ? null : evidence.collectedAt().toString());
        freshness.put("age_seconds", ageSeconds == Long.MAX_VALUE ? -1 : ageSeconds);
        freshness.put("stale_after_seconds", staleAfterSeconds);
        freshness.put("stale", stale);

        Map<String, Object> collectorStatus = new LinkedHashMap<>();
        collectorStatus.put("collected", List.copyOf(collected));
        collectorStatus.put("expected", expected);
        collectorStatus.put("missing", missing);
        collectorStatus.put("failed", List.copyOf(failed));
        collectorStatus.put("degraded", List.copyOf(degraded));
        collectorStatus.put("unsupported", List.copyOf(unsupported));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("quality_score", score);
        result.put("confidence_penalty", penalty);
        result.put("freshness", freshness);
        result.put("collector_status", collectorStatus);
        result.put("agent_health", agent);
        result.put("notes", notes);
        return result;
    }

    public int confidencePenalty(Map<String, Object> quality) {
        Object value = quality == null ? null : quality.get("confidence_penalty");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> compact(Map<String, Object> quality) {
        if (quality == null || quality.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> freshness = quality.get("freshness") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        Map<String, Object> collectors = quality.get("collector_status") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        Map<String, Object> agent = quality.get("agent_health") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", quality.getOrDefault("status", "unknown"));
        result.put("quality_score", quality.getOrDefault("quality_score", 0));
        result.put("confidence_penalty", quality.getOrDefault("confidence_penalty", 0));
        result.put("stale", freshness.getOrDefault("stale", false));
        result.put("age_seconds", freshness.getOrDefault("age_seconds", -1));
        result.put("missing_collectors", collectors.getOrDefault("missing", List.of()));
        result.put("failed_collectors", collectors.getOrDefault("failed", List.of()));
        result.put("agent_status", agent.getOrDefault("status", "unknown"));
        return result;
    }

    private Map<String, Object> agentQuality(String clusterId, String nodeName) {
        if (clusterId == null || nodeName == null || nodeName.isBlank() || "unknown".equalsIgnoreCase(nodeName)) {
            return Map.of("registered", false, "status", "unknown", "reasons", List.of("Node name is not available."));
        }
        return agents.find(clusterId, nodeName)
            .map(agent -> {
                AgentHealthView health = agentHealth.classify(agent);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("registered", true);
                result.put("status", health.healthStatus().name());
                result.put("reported_status", health.reportedStatus() == null ? "unknown" : health.reportedStatus().name());
                result.put("heartbeat_age_seconds", health.heartbeatAgeSeconds());
                result.put("last_heartbeat_at", health.lastHeartbeatAt() == null ? null : health.lastHeartbeatAt().toString());
                result.put("reasons", health.reasons());
                return result;
            })
            .orElseGet(() -> Map.of(
                "registered", false,
                "status", "not_registered",
                "reasons", List.of("No registered agent was found for this cluster/node.")
            ));
    }

    private String collectorState(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object status = firstPresent(map, "status", "state", "health", "result");
            if (status != null) {
                String normalized = normalizeStatus(status);
                if (isCollectorExecutionState(normalized, map)) {
                    return normalized;
                }
            }
            return map.values().stream()
                .map(this::collectorState)
                .filter(item -> !item.isBlank())
                .findFirst()
                .orElse("");
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(this::collectorState)
                .filter(item -> !item.isBlank())
                .findFirst()
                .orElse("");
        }
        return "";
    }

    private boolean isCollectorExecutionState(String status, Map<?, ?> collector) {
        if (status == null || status.isBlank()) {
            return false;
        }
        if (List.of("ok", "healthy", "success", "available", "ready").contains(status)) {
            return false;
        }
        if (List.of("unsupported", "disabled", "limited", "unavailable", "timeout").contains(status)) {
            return true;
        }
        if ("error".equals(status)) {
            return true;
        }
        if (List.of("failed", "failure", "unhealthy", "forbidden", "unauthorized").contains(status)) {
            return collector.containsKey("error")
                || collector.containsKey("exception")
                || collector.containsKey("traceback")
                || collector.containsKey("collector_error")
                || collector.containsKey("collector_status");
        }
        return false;
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String normalizeStatus(Object value) {
        return String.valueOf(value)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
    }

    private String status(
        boolean stale,
        List<String> missing,
        List<String> failed,
        List<String> degraded,
        List<String> unsupported,
        String agentStatus,
        int score
    ) {
        if (stale) {
            return "stale";
        }
        if (!failed.isEmpty()
            || List.of("offline", "unauthorized", "collector_degraded", "version_mismatch").contains(agentStatus)
            || score < 60) {
            return "degraded";
        }
        if (!missing.isEmpty() || !degraded.isEmpty() || !unsupported.isEmpty()
            || "stale".equals(agentStatus) || "not_registered".equals(agentStatus)) {
            return "partial";
        }
        return "complete";
    }

    private List<String> expectedCollectors(String alertName) {
        return switch (alertName == null ? "" : alertName) {
            case "NodeNotReady", "KubeletDown", "KubeletUnhealthy" ->
                List.of("node", "kubernetes", "systemd", "runtime", "kubelet", "kernel", "network", "conntrack");
            case "DiskPressure" -> List.of("node", "disk", "inode", "kernel", "systemd");
            case "MemoryPressure", "OOMKillDetected" -> List.of("node", "memory", "kernel", "systemd", "process");
            case "PIDPressure" -> List.of("node", "process", "systemd", "kernel");
            case "NetworkUnavailable" ->
                List.of("node", "kubernetes", "network", "cni", "dns", "conntrack", "kernel");
            case "ContainerdDown", "ContainerRuntimeUnhealthy" ->
                List.of("runtime", "systemd", "kernel", "disk");
            case "CoreDNSUnhealthy", "CoreDNSLatencyHigh" ->
                List.of("dns", "network", "cni", "conntrack", "kubernetes");
            case "EtcdLatencyHigh", "APIServerLatencyHigh" ->
                List.of("node", "kubernetes", "network", "dns", "systemd", "kernel", "disk");
            default -> List.of("node", "systemd", "runtime", "disk", "inode", "memory", "process", "network", "kernel");
        };
    }
}
