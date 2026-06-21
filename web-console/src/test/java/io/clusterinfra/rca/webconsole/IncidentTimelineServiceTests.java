package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.IncidentTimelineService;
import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncidentTimelineServiceTests {
    @Test
    void buildsCausalEdgeAndPromotesLaterStorageEvidenceAsRootTrigger() {
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RuleBasedRcaAnalyzer analyzer = mock(RuleBasedRcaAnalyzer.class);
        ReportRepository reports = mock(ReportRepository.class);
        IncidentCausalityRules causality = new IncidentCausalityRules();
        IncidentTimelineService service = new IncidentTimelineService(
            evidence,
            analyzer,
            reports,
            causality
        );
        Instant first = Instant.parse("2026-06-21T02:00:00Z");
        EvidenceBundle nodeSymptom = new EvidenceBundle(
            "evidence-node",
            "cluster-1",
            "worker-a",
            "NodeNotReady",
            first,
            Map.of("node", Map.of())
        );
        EvidenceBundle storageCause = new EvidenceBundle(
            "evidence-storage",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            first.plusSeconds(60),
            Map.of("disk", Map.of())
        );
        Incident incident = new Incident(
            "incident-1",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            "Disk I/O latency",
            IncidentStatus.open,
            2,
            first,
            first.plusSeconds(60),
            storageCause.evidenceId(),
            "report-storage"
        );
        RcaReport canonical = new RcaReport(
            "report-storage",
            "cluster-1",
            incident.incidentId(),
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("nodes", List.of("worker-a"), "components", List.of("disk")),
            new RcaSummary("DiskPressure", "Disk I/O latency", Confidence.high),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            first.plusSeconds(60)
        );

        when(reports.findReport("report-storage")).thenReturn(Optional.of(canonical));
        when(evidence.listRealtimeEvents(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class)
        )).thenReturn(List.of());
        when(evidence.listForNodeWindow(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class)
        )).thenReturn(List.of(nodeSymptom, storageCause));
        when(analyzer.deriveTimelineSignals(nodeSymptom.collectors())).thenReturn(List.of(Map.of(
            "component", "node",
            "signal", "node_not_ready",
            "severity", "critical",
            "interpretation", "Node readiness was lost."
        )));
        when(analyzer.deriveTimelineSignals(storageCause.collectors())).thenReturn(List.of(Map.of(
            "component", "disk",
            "signal", "disk_io_latency_high",
            "severity", "critical",
            "interpretation", "Block device latency exceeded the threshold."
        )));

        var timeline = service.build(incident);

        assertThat(timeline.nodes()).hasSize(2);
        assertThat(timeline.nodes().getFirst().rootTrigger()).isTrue();
        assertThat(timeline.nodes().getFirst().signalFamily()).isEqualTo("storage");
        assertThat(timeline.nodes())
            .filteredOn(node -> node.rootTrigger())
            .singleElement()
            .extracting(node -> node.signalFamily())
            .isEqualTo("storage");
        assertThat(timeline.edges())
            .anySatisfy(edge -> {
                assertThat(edge.ruleId()).isEqualTo("storage_node");
                assertThat(edge.confidence()).isGreaterThan(0.9);
                assertThat(edge.inferred()).isTrue();
            });
    }
}
