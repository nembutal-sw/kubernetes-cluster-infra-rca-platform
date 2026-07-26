package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.service.IncidentCorrelationService;
import io.clusterinfra.rca.webconsole.service.TopologyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IncidentCorrelationServiceTests {
    private IncidentRepository incidents;
    private ReportRepository reports;
    private IncidentCorrelationService service;
    private TopologyService topology;
    private Instant observedAt;

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentRepository.class);
        reports = mock(ReportRepository.class);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getIncident().setCorrelationWindowMinutes(15);
        properties.getIncident().setMinimumScore(70);
        properties.getIncident().setCandidateLimit(20);
        topology = mock(TopologyService.class);
        service = new IncidentCorrelationService(
            incidents,
            reports,
            properties,
            new Sha256Digest(),
            new IncidentCausalityRules(),
            topology
        );
        observedAt = Instant.parse("2026-06-21T02:10:00Z");
    }

    @Test
    void promotesStorageAsRootCauseWhenNodeSymptomArrivedFirst() {
        Incident existing = incident("NodeNotReady", "Kubelet unavailable", "report-node");
        RcaReport existingReport = report("report-node", "NodeNotReady", "Kubelet unavailable", List.of(
            "kubelet", "node"
        ));
        prepare(existing, existingReport);

        var decision = service.decide(
            report("report-disk", "DiskPressure", "Disk I/O latency and inode exhaustion", List.of(
                "disk", "inode"
            )),
            evidence("DiskPressure")
        );

        assertThat(decision.matchedIncidentId()).isEqualTo(existing.incidentId());
        assertThat(decision.promoteRootCause()).isTrue();
        assertThat(decision.ruleId()).isIn("storage_kubelet", "storage_node");
        assertThat(decision.primaryFamily()).isEqualTo("storage");
        assertThat(decision.score()).isGreaterThanOrEqualTo(86);
    }

    @Test
    void correlatesConntrackExhaustionWithDnsFailure() {
        Incident existing = incident("NetworkUnavailable", "Conntrack table near limit", "report-network");
        RcaReport existingReport = report(
            "report-network",
            "NetworkUnavailable",
            "Conntrack table near limit",
            List.of("conntrack", "network")
        );
        prepare(existing, existingReport);

        var decision = service.decide(
            report("report-dns", "CoreDNSLatencyHigh", "DNS requests are timing out", List.of("dns")),
            evidence("CoreDNSLatencyHigh")
        );

        assertThat(decision.matched()).isTrue();
        assertThat(decision.promoteRootCause()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("conntrack_dns");
    }

    @Test
    void correlatesEtcdLatencyWithApiServerLatency() {
        Incident existing = incident("EtcdLatencyHigh", "etcd fsync latency increased", "report-etcd");
        RcaReport existingReport = report(
            "report-etcd",
            "EtcdLatencyHigh",
            "etcd fsync latency increased",
            List.of("etcd")
        );
        prepare(existing, existingReport);

        var decision = service.decide(
            report(
                "report-api",
                "APIServerLatencyHigh",
                "API server request latency increased",
                List.of("api_server")
            ),
            evidence("APIServerLatencyHigh")
        );

        assertThat(decision.matched()).isTrue();
        assertThat(decision.ruleId()).isEqualTo("etcd_api_server");
        assertThat(decision.score()).isGreaterThanOrEqualTo(82);
    }

    @Test
    void doesNotMergeUnrelatedStorageAndDnsSignals() {
        Incident existing = incident("DiskPressure", "Filesystem usage critical", "report-disk");
        prepare(existing, report(
            "report-disk",
            "DiskPressure",
            "Filesystem usage critical",
            List.of("disk", "kernel", "systemd")
        ));

        var decision = service.decide(
            report(
                "report-dns",
                "CoreDNSLatencyHigh",
                "DNS timeout",
                List.of("dns", "kernel", "systemd")
            ),
            evidence("CoreDNSLatencyHigh")
        );

        assertThat(decision.matched()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("new_incident");
    }

    @Test
    void linksNewIncidentToStrictResolvedRecurrence() {
        Incident resolved = new Incident(
            "incident-resolved",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            "Filesystem usage critical",
            IncidentStatus.resolved,
            2,
            observedAt.minusSeconds(7200),
            observedAt.minusSeconds(3600),
            "evidence-existing",
            "report-disk",
            observedAt.minusSeconds(3500),
            "automatic",
            "inactive",
            null,
            0
        );
        when(incidents.findRecentOpen(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(List.of());
        when(incidents.findRecentResolved(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(List.of(resolved));
        when(reports.findReport("report-disk")).thenReturn(Optional.of(report(
            "report-disk",
            "DiskPressure",
            "Filesystem usage critical",
            List.of("disk")
        )));

        var decision = service.decide(
            report("report-recurrence", "DiskPressure", "Disk usage critical again", List.of("disk")),
            evidence("DiskPressure")
        );

        assertThat(decision.matched()).isFalse();
        assertThat(decision.recurrence()).isTrue();
        assertThat(decision.recurrenceOfIncidentId()).isEqualTo(resolved.incidentId());
        assertThat(decision.recurrenceSequence()).isEqualTo(1);
        assertThat(decision.ruleId()).isEqualTo("incident_recurrence_same_alert");
    }

    @Test
    void correlatesDnsFailuresAcrossNodesThatServeTheSameService() {
        Incident existing = incident("CoreDNSLatencyHigh", "DNS requests are timing out", "report-dns");
        prepareCrossNode(existing, report(
            "report-dns",
            "CoreDNSLatencyHigh",
            "DNS requests are timing out",
            List.of("dns")
        ));
        when(topology.connection("cluster-1", "worker-a", "worker-b")).thenReturn(
            new TopologyService.NodeConnection(
                true,
                "topology_shared_service",
                "nodes host endpoints for the same Service",
                0.95,
                List.of("kube-system/kube-dns")
            )
        );

        var decision = service.decide(
            report("report-dns-b", "CoreDNSLatencyHigh", "DNS requests are timing out", List.of("dns")),
            evidence("worker-b", "CoreDNSLatencyHigh")
        );

        assertThat(decision.matchedIncidentId()).isEqualTo(existing.incidentId());
        assertThat(decision.crossNode()).isTrue();
        assertThat(decision.topologyRule()).isEqualTo("topology_shared_service");
        assertThat(decision.sharedServices()).containsExactly("kube-system/kube-dns");
    }

    @Test
    void doesNotMergeNodeLocalStorageFailuresAcrossNodes() {
        Incident existing = incident("DiskPressure", "Filesystem usage critical", "report-disk");
        prepareCrossNode(existing, report(
            "report-disk",
            "DiskPressure",
            "Filesystem usage critical",
            List.of("disk")
        ));

        var decision = service.decide(
            report("report-disk-b", "DiskPressure", "Filesystem usage critical", List.of("disk")),
            evidence("worker-b", "DiskPressure")
        );

        assertThat(decision.matched()).isFalse();
        assertThat(decision.crossNode()).isFalse();
    }

    private void prepare(Incident incident, RcaReport report) {
        when(incidents.findRecentOpen(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(List.of(incident));
        when(reports.findReport(report.reportId())).thenReturn(Optional.of(report));
    }

    private void prepareCrossNode(Incident incident, RcaReport report) {
        when(incidents.findRecentOpen(
            eq("cluster-1"),
            eq("worker-b"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(List.of());
        when(incidents.findRecentOpenCluster(
            eq("cluster-1"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(List.of(incident));
        when(reports.findReport(report.reportId())).thenReturn(Optional.of(report));
    }

    private Incident incident(String alertName, String cause, String reportId) {
        return new Incident(
            "incident-1",
            "cluster-1",
            "worker-a",
            alertName,
            cause,
            IncidentStatus.open,
            1,
            observedAt.minusSeconds(120),
            observedAt.minusSeconds(30),
            "evidence-existing",
            reportId
        );
    }

    private RcaReport report(
        String reportId,
        String alertName,
        String cause,
        List<String> components
    ) {
        return new RcaReport(
            reportId,
            "cluster-1",
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", alertName),
            Map.of("nodes", List.of("worker-a"), "components", components),
            new RcaSummary(alertName, cause, Confidence.high),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            observedAt
        );
    }

    private EvidenceBundle evidence(String alertName) {
        return evidence("worker-a", alertName);
    }

    private EvidenceBundle evidence(String nodeName, String alertName) {
        return new EvidenceBundle(
            "evidence-new",
            "cluster-1",
            nodeName,
            alertName,
            observedAt,
            Map.of()
        );
    }
}
