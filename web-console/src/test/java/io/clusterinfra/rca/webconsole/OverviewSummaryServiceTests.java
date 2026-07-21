package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import io.clusterinfra.rca.webconsole.service.OverviewSummaryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewSummaryServiceTests {
    @Test
    void combinesBoundedRecentItemsWithDatabaseTotals() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        ReportRepository reports = mock(ReportRepository.class);
        IncidentRepository incidents = mock(IncidentRepository.class);
        AnalysisTaskRepository tasks = mock(AnalysisTaskRepository.class);
        ActionRepository actions = mock(ActionRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        AgentHealthService agentHealth = mock(AgentHealthService.class);
        OverviewSummaryService service = new OverviewSummaryService(
            clusters,
            reports,
            incidents,
            tasks,
            actions,
            agents,
            agentHealth
        );

        when(clusters.list()).thenReturn(List.of());
        when(reports.pageReports(null, null, null, null, 10))
            .thenReturn(new CursorPage<>(List.of(), null, false, 42, 10));
        when(reports.countSince(any(Instant.class))).thenReturn(7L);
        when(incidents.page(null, null, null, null, 10))
            .thenReturn(new CursorPage<>(List.of(), null, false, 12, 10));
        when(incidents.page(null, IncidentStatus.open, null, null, 1))
            .thenReturn(new CursorPage<>(List.of(), null, false, 3, 1));
        when(tasks.count(AnalysisTaskStatus.queued)).thenReturn(2L);
        when(tasks.count(AnalysisTaskStatus.processing)).thenReturn(1L);
        when(tasks.count(AnalysisTaskStatus.retry_wait)).thenReturn(4L);
        when(tasks.count(AnalysisTaskStatus.dead_letter)).thenReturn(5L);
        when(actions.count(null)).thenReturn(11L);
        when(actions.count(ActionRequestStatus.pending_approval)).thenReturn(2L);
        when(actions.count(ActionRequestStatus.accepted)).thenReturn(1L);
        when(actions.count(ActionRequestStatus.approved_manual)).thenReturn(3L);
        when(actions.count(ActionRequestStatus.blocked)).thenReturn(4L);
        when(actions.count(ActionRequestStatus.rejected)).thenReturn(5L);
        when(actions.count(ActionRequestStatus.failed)).thenReturn(6L);
        when(agents.listAll()).thenReturn(List.of());

        var summary = service.summary();

        assertThat(summary.reportCount()).isEqualTo(42);
        assertThat(summary.openIncidentCount()).isEqualTo(3);
        assertThat(summary.reportsLast24Hours()).isEqualTo(7);
        assertThat(summary.analysisBacklogCount()).isEqualTo(7);
        assertThat(summary.analysisDeadLetterCount()).isEqualTo(5);
        assertThat(summary.actionRequestCount()).isEqualTo(11);
        assertThat(summary.manualActionCount()).isEqualTo(4);
        assertThat(summary.blockedActionCount()).isEqualTo(15);
        assertThat(summary.recentReports()).isEmpty();
    }
}
