package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.OverviewSummary;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OverviewSummaryService {
    private static final int RECENT_ITEM_LIMIT = 10;

    private final ClusterRepository clusters;
    private final ReportRepository reports;
    private final IncidentRepository incidents;
    private final AnalysisTaskRepository tasks;
    private final ActionRepository actions;
    private final AgentRepository agents;
    private final AgentHealthService agentHealth;

    public OverviewSummaryService(
        ClusterRepository clusters,
        ReportRepository reports,
        IncidentRepository incidents,
        AnalysisTaskRepository tasks,
        ActionRepository actions,
        AgentRepository agents,
        AgentHealthService agentHealth
    ) {
        this.clusters = clusters;
        this.reports = reports;
        this.incidents = incidents;
        this.tasks = tasks;
        this.actions = actions;
        this.agents = agents;
        this.agentHealth = agentHealth;
    }

    public OverviewSummary summary() {
        var clusterRows = clusters.list();
        var reportPage = reports.pageReports(null, null, null, null, RECENT_ITEM_LIMIT);
        var incidentPage = incidents.page(null, null, null, null, RECENT_ITEM_LIMIT);
        var openIncidentPage = incidents.page(null, IncidentStatus.open, null, null, 1);

        Map<AgentHealthStatus, Long> healthCounts = new EnumMap<>(AgentHealthStatus.class);
        agents.listAll().stream()
            .map(agentHealth::classify)
            .forEach(view -> healthCounts.merge(view.healthStatus(), 1L, Long::sum));

        long queued = tasks.count(AnalysisTaskStatus.queued);
        long processing = tasks.count(AnalysisTaskStatus.processing);
        long retry = tasks.count(AnalysisTaskStatus.retry_wait);
        long stale = count(healthCounts, AgentHealthStatus.stale);
        long degraded = count(healthCounts, AgentHealthStatus.collector_degraded)
            + count(healthCounts, AgentHealthStatus.version_mismatch)
            + count(healthCounts, AgentHealthStatus.unauthorized);

        return new OverviewSummary(
            clusterRows.size(),
            reportPage.total(),
            openIncidentPage.total(),
            reports.countSince(Instant.now().minus(24, ChronoUnit.HOURS)),
            queued + processing + retry,
            queued,
            processing,
            retry,
            tasks.count(AnalysisTaskStatus.dead_letter),
            actions.count(null),
            actions.count(ActionRequestStatus.pending_approval),
            actions.count(ActionRequestStatus.accepted) + actions.count(ActionRequestStatus.approved_manual),
            actions.count(ActionRequestStatus.blocked)
                + actions.count(ActionRequestStatus.rejected)
                + actions.count(ActionRequestStatus.failed),
            healthCounts.values().stream().mapToLong(Long::longValue).sum(),
            count(healthCounts, AgentHealthStatus.healthy),
            stale,
            degraded,
            count(healthCounts, AgentHealthStatus.offline),
            clusterRows.stream().limit(RECENT_ITEM_LIMIT).map(ClusterView::from).toList(),
            reportPage.items(),
            incidentPage.items()
        );
    }

    private long count(Map<AgentHealthStatus, Long> counts, AgentHealthStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}
