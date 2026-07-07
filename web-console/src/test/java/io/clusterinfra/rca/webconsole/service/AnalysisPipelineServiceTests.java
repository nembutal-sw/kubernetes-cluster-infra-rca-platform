package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.IncidentCorrelationService.CorrelationDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalysisPipelineServiceTests {
    private static final Instant NOW = Instant.parse("2026-07-06T02:00:00Z");

    @Mock
    private EvidenceRepository evidenceRepository;

    @Mock
    private IncidentRepository incidents;

    @Mock
    private ReportRepository reports;

    @Mock
    private RuleBasedRcaAnalyzer analyzer;

    @Mock
    private IncidentCorrelationService correlation;

    @Mock
    private AuditService audit;

    @Mock
    private IncidentNotificationService notifications;

    @Mock
    private RcaMetrics metrics;

    @Mock
    private TopologyService topology;

    private AnalysisPipelineService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisPipelineService(
            evidenceRepository,
            incidents,
            reports,
            analyzer,
            correlation,
            audit,
            notifications,
            metrics,
            topology
        );
    }

    @Test
    void missingEvidenceFailsFast() {
        AnalysisTask task = task("missing-evidence", false);
        when(evidenceRepository.find("missing-evidence")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processAnalysisTask(task))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("analysis evidence not found");
    }

    @Test
    void skipIfHealthyTaskObservesTopologyButDoesNotCreateReport() {
        EvidenceBundle evidence = evidence(Map.of("node", Map.of("status", "healthy")));
        AnalysisTask task = task(evidence.evidenceId(), true);
        when(evidenceRepository.find(evidence.evidenceId())).thenReturn(Optional.of(evidence));
        when(analyzer.hasActionableSignals(evidence.clusterId(), evidence.collectors())).thenReturn(false);

        assertThat(service.processAnalysisTask(task)).isNull();

        verify(topology).observe(evidence);
        verify(analyzer).hasActionableSignals(evidence.clusterId(), evidence.collectors());
        verifyNoInteractions(incidents, reports, audit, notifications);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedAnalysisCreatesCorrelatedIncidentAuditAndNotification() {
        EvidenceBundle evidence = evidence(Map.of("disk", Map.of("disk_usage_percent", 97)));
        AnalysisTask task = task(evidence.evidenceId(), false);
        when(evidenceRepository.find(evidence.evidenceId())).thenReturn(Optional.of(evidence));
        RcaReport analyzedReport = report("generated-report", null);
        when(analyzer.analyze(any(), eq(evidence))).thenReturn(analyzedReport);
        when(correlation.decide(analyzedReport, evidence)).thenReturn(new CorrelationDecision(
            "cluster-1:worker-a:disk",
            null,
            "new_incident",
            "new incident",
            100,
            false,
            "disk",
            null,
            0,
            false,
            "",
            List.of()
        ));
        when(incidents.saveCorrelated(
            any(),
            any(),
            eq("cluster-1:worker-a:disk"),
            eq(null),
            eq(false),
            eq(null),
            eq(0),
            eq(evidence)
        )).thenAnswer(invocation -> invocation.getArgument(1));
        when(reports.findReport(any())).thenAnswer(invocation ->
            Optional.of(report(invocation.getArgument(0), "incident-1"))
        );

        RcaJob result = service.processAnalysisTask(task);

        assertThat(result.reportId()).startsWith("report-");
        verify(topology).observe(evidence);
        verify(metrics).incident("created");
        verify(metrics).reportGenerated(eq("created"), any());
        verify(notifications).notifyIncident(any(RcaReport.class), eq(evidence));

        ArgumentCaptor<Map<String, Object>> auditDetails = ArgumentCaptor.forClass(Map.class);
        verify(audit).system(
            eq("rca-pipeline"),
            eq("incident.created"),
            eq("incident"),
            eq("incident-1"),
            eq("report_created"),
            auditDetails.capture()
        );
        assertThat(auditDetails.getValue())
            .containsEntry("cluster_id", "cluster-1")
            .containsEntry("node_name", "worker-a")
            .containsEntry("alert_name", "DiskPressure")
            .containsEntry("report_id", result.reportId())
            .containsEntry("correlation_rule", "new_incident");
    }

    private AnalysisTask task(String evidenceId, boolean skipIfHealthy) {
        return new AnalysisTask(
            "task-1",
            evidenceId,
            "cluster-1",
            "worker-a",
            "DiskPressure",
            "test",
            skipIfHealthy,
            AnalysisTaskStatus.queued,
            1,
            3,
            NOW,
            null,
            null,
            null,
            null,
            null,
            NOW,
            null,
            null
        );
    }

    private EvidenceBundle evidence(Map<String, Object> collectors) {
        return new EvidenceBundle(
            "evidence-1",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            NOW,
            collectors
        );
    }

    private RcaReport report(String reportId, String incidentId) {
        return new RcaReport(
            reportId,
            "cluster-1",
            incidentId,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Disk pressure", Confidence.high),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            NOW
        );
    }
}
