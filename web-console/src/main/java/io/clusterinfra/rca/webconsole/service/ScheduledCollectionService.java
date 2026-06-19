package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledCollectionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledCollectionService.class);
    private static final List<String> COLLECTORS = List.of(
        "node", "kubernetes", "systemd", "runtime", "kubelet", "kernel",
        "disk", "inode", "memory", "process", "network", "conntrack", "cni", "dns"
    );

    private final RcaRepository repository;
    private final RcaConsoleProperties properties;

    public ScheduledCollectionService(RcaRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
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
        for (var cluster : repository.listClusters()) {
            for (NodeAgent agent : repository.listAgents(cluster.clusterId())) {
                if (isOffline(agent, requestedAt)
                    || repository.hasPendingEvidenceRequest(cluster.clusterId(), agent.nodeName())) {
                    continue;
                }
                try {
                    repository.createEvidenceRequest(new EvidenceRequestCreateRequest(
                        cluster.clusterId(),
                        agent.nodeName(),
                        "ScheduledNodeHealth",
                        COLLECTORS,
                        Map.of("source", "scheduled_monitoring", "requested_at", requestedAt.toString()),
                        "Periodic platform-initiated node health collection",
                        Map.of("trigger", "scheduled_monitoring", "requested_at", requestedAt.toString())
                    ));
                } catch (RuntimeException exception) {
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

    private boolean isOffline(NodeAgent agent, Instant now) {
        if (agent.status() == AgentStatus.offline || agent.lastHeartbeatAt() == null) {
            return true;
        }
        return Duration.between(agent.lastHeartbeatAt(), now).getSeconds()
            > Math.max(1, properties.getAgentOfflineAfterSeconds());
    }
}
