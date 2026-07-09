package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledCollectionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledCollectionService.class);
    private static final List<String> BASELINE_COLLECTORS = List.of(
        "node", "kubernetes", "disk", "inode", "memory", "network", "conntrack", "runtime", "cni", "dns"
    );
    private static final List<String> DEEP_COLLECTORS = List.of(
        "node", "kubernetes", "systemd", "runtime", "kubelet", "kernel",
        "disk", "inode", "memory", "process", "network", "conntrack", "cni", "dns"
    );
    private static final List<String> VERSION_COLLECTORS = List.of("node", "kubernetes", "runtime");

    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final RcaConsoleProperties properties;
    private final RcaMetrics metrics;
    private final AgentHealthService agentHealth;

    public ScheduledCollectionService(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        RcaConsoleProperties properties,
        RcaMetrics metrics,
        AgentHealthService agentHealth
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.evidence = evidence;
        this.properties = properties;
        this.metrics = metrics;
        this.agentHealth = agentHealth;
    }

    @Scheduled(
        fixedDelayString = "${rca.monitoring.interval-ms:60000}",
        initialDelayString = "${rca.monitoring.initial-delay-ms:30000}"
    )
    public void requestScheduledEvidence() {
        if (!properties.getMonitoring().isEnabled()) {
            return;
        }
        Instant requestedAt = Instant.now();
        for (var cluster : clusters.list()) {
            for (NodeAgent agent : agents.list(cluster.clusterId())) {
                AgentHealthView health = agentHealth.classify(agent);
                if (health.healthStatus() == AgentHealthStatus.offline
                    || isOffline(agent, requestedAt)) {
                    metrics.evidenceRequest("scheduled", "skipped_offline", 1);
                    continue;
                }
                if (health.healthStatus() == AgentHealthStatus.healthy
                    && !properties.getMonitoring().isCollectHealthyAgents()) {
                    metrics.evidenceRequest("scheduled", "skipped_healthy_disabled", 1);
                    continue;
                }
                if (evidence.hasPendingRequest(cluster.clusterId(), agent.nodeName())) {
                    metrics.evidenceRequest("scheduled", "skipped_pending", 1);
                    continue;
                }
                if (hasRecentScheduledRequest(cluster, agent, health, requestedAt)) {
                    metrics.evidenceRequest("scheduled", "skipped_recent", 1);
                    continue;
                }
                try {
                    evidence.createRequest(requestFor(cluster, agent, health, requestedAt));
                    metrics.evidenceRequest("scheduled", "created", 1);
                } catch (RuntimeException exception) {
                    metrics.evidenceRequest("scheduled", "failed", 1);
                    LOGGER.warn(
                        "Failed to create scheduled evidence request for cluster={} node={}",
                        cluster.clusterId(),
                        agent.nodeName(),
                        exception
                    );
                }
            }
        }
    }

    private EvidenceRequestCreateRequest requestFor(
        Cluster cluster,
        NodeAgent agent,
        AgentHealthView health,
        Instant requestedAt
    ) {
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("source", "scheduled_monitoring");
        timeRange.put("requested_at", requestedAt.toString());
        timeRange.put("health_status", health.healthStatus().name());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("trigger", "scheduled_monitoring");
        context.put("requested_at", requestedAt.toString());
        context.put("health_status", health.healthStatus().name());
        context.put("reported_status", health.reportedStatus().name());
        context.put("heartbeat_age_seconds", health.heartbeatAgeSeconds());
        context.put("health_reasons", health.reasons());
        context.put("supported_collectors", health.supportedCollectors());
        context.put("platform_agent_protocol", health.platformProtocolVersion());
        context.put("agent_protocol", health.agentProtocolVersion());

        return new EvidenceRequestCreateRequest(
            cluster.clusterId(),
            agent.nodeName(),
            alertName(health.healthStatus()),
            collectors(health.healthStatus()),
            timeRange,
            reason(health.healthStatus(), health.reasons()),
            context
        );
    }

    private String alertName(AgentHealthStatus status) {
        return switch (status) {
            case collector_degraded -> "AgentCollectorDegraded";
            case version_mismatch -> "AgentVersionMismatch";
            case unauthorized -> "AgentAuthenticationFailure";
            case stale -> "AgentHeartbeatStale";
            case healthy -> "ScheduledNodeHealth";
            case offline -> "AgentOffline";
        };
    }

    private List<String> collectors(AgentHealthStatus status) {
        return switch (status) {
            case healthy -> BASELINE_COLLECTORS;
            case version_mismatch, unauthorized -> VERSION_COLLECTORS;
            case stale, collector_degraded -> DEEP_COLLECTORS;
            case offline -> List.of();
        };
    }

    private boolean hasRecentScheduledRequest(
        Cluster cluster,
        NodeAgent agent,
        AgentHealthView health,
        Instant requestedAt
    ) {
        int cooldownMinutes = switch (health.healthStatus()) {
            case healthy -> properties.getMonitoring().getHealthyIntervalMinutes();
            case collector_degraded -> properties.getMonitoring().getDegradedIntervalMinutes();
            case stale -> properties.getMonitoring().getStaleIntervalMinutes();
            case version_mismatch -> properties.getMonitoring().getVersionMismatchIntervalMinutes();
            case unauthorized -> properties.getMonitoring().getUnauthorizedIntervalMinutes();
            case offline -> 60;
        };
        Instant since = requestedAt.minus(Duration.ofMinutes(Math.max(1, cooldownMinutes)));
        return evidence.hasRecentRequest(
            cluster.clusterId(),
            agent.nodeName(),
            List.of(alertName(health.healthStatus())),
            since
        );
    }

    private String reason(AgentHealthStatus status, List<String> reasons) {
        if (reasons != null && !reasons.isEmpty()) {
            return String.join(" ", reasons);
        }
        return switch (status) {
            case healthy -> "Periodic platform-initiated node health collection.";
            case stale -> "Agent heartbeat is stale; collect node evidence before it becomes offline.";
            case unauthorized -> "Agent reported an authentication or authorization failure.";
            case version_mismatch -> "Agent version or protocol is outside the supported range.";
            case collector_degraded -> "Agent reported degraded collection capability.";
            case offline -> "Agent is offline and cannot collect evidence.";
        };
    }

    private boolean isOffline(NodeAgent agent, Instant now) {
        if (agent.status() == AgentStatus.offline || agent.lastHeartbeatAt() == null) {
            return true;
        }
        return Duration.between(agent.lastHeartbeatAt(), now).getSeconds()
            > Math.max(1, properties.getAgentOfflineAfterSeconds());
    }
}
