package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationalMetricsRefresher {
    private final AgentRepository agents;
    private final AnalysisTaskRepository tasks;
    private final RcaConsoleProperties properties;
    private final RcaMetrics metrics;

    public OperationalMetricsRefresher(
        AgentRepository agents,
        AnalysisTaskRepository tasks,
        RcaConsoleProperties properties,
        RcaMetrics metrics
    ) {
        this.agents = agents;
        this.tasks = tasks;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
        fixedDelayString = "${rca.observability.refresh-interval-ms:15000}",
        initialDelayString = "${rca.observability.initial-delay-ms:5000}"
    )
    public void refresh() {
        if (!properties.getObservability().isEnabled()) {
            return;
        }
        long queueDepth = tasks.count(AnalysisTaskStatus.queued)
            + tasks.count(AnalysisTaskStatus.retry_wait)
            + tasks.count(AnalysisTaskStatus.processing);
        metrics.refreshOperationalGauges(
            agents.listAll(),
            Math.max(1, properties.getAgentOfflineAfterSeconds()),
            queueDepth,
            tasks.count(AnalysisTaskStatus.dead_letter)
        );
    }
}
