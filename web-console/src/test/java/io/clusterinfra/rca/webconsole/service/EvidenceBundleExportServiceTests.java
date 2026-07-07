package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentTimeline;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EvidenceBundleExportServiceTests {
    private static final Instant NOW = Instant.parse("2026-06-21T04:00:00Z");
    private static final UserAccount USER = new UserAccount(
        "user-1",
        "operator@example.com",
        "Operator",
        UserRole.operator,
        UserRole.operator,
        UserStatus.active,
        null,
        null,
        null,
        NOW,
        NOW
    );

    private ReportRepository reports;
    private IncidentRepository incidents;
    private EvidenceRepository evidence;
    private IncidentTimelineService timelines;
    private AuditService audit;
    private EvidenceBundleExportService service;

    @BeforeEach
    void setUp() {
        reports = mock(ReportRepository.class);
        incidents = mock(IncidentRepository.class);
        evidence = mock(EvidenceRepository.class);
        timelines = mock(IncidentTimelineService.class);
        audit = mock(AuditService.class);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getExport().setMaxBundleBytes(1024 * 1024);
        properties.getExport().setSignatureSecret("bundle-signing-secret");
        properties.getExport().setSignatureKeyId("test-key-1");
        service = new EvidenceBundleExportService(
            reports,
            incidents,
            evidence,
            timelines,
            properties,
            objectMapper(),
            audit
        );
    }

    @Test
    void downloadReportReturnsZipMetadataAndWritesAuditEvent() {
        RcaReport report = report("report-1", "incident-1");
        Incident incident = incident("incident-1", "report-1");
        stubBundleInputs(report, incident);

        var download = service.downloadReport("report-1", USER, new MockHttpServletRequest());

        assertThat(download.filename()).isEqualTo("rca-evidence-bundle-report-1.zip");
        assertThat(download.content()).startsWith(new byte[] {'P', 'K'});
        assertThat(download.evidenceCount()).isEqualTo(1);
        assertThat(download.rawBytes()).isPositive();
        assertThat(download.zipBytes()).isEqualTo(download.content().length);
        assertThat(download.hashAlgorithm()).isEqualTo("SHA-256");
        assertThat(download.entryCount()).isGreaterThanOrEqualTo(5);
        assertThat(download.signatureEnabled()).isTrue();
        assertThat(download.signatureKeyId()).isEqualTo("test-key-1");
        verify(audit).user(
            eq(USER),
            eq("evidence.bundle_exported"),
            eq("report"),
            eq("report-1"),
            eq("success"),
            argThat(details -> details.get("evidence_count").equals(1)
                && ((Number) details.get("raw_bytes")).longValue() > 0),
            any()
        );
    }

    @Test
    void downloadIncidentUsesIncidentAuditResource() {
        RcaReport report = report("report-1", "incident-1");
        Incident incident = incident("incident-1", "report-1");
        stubBundleInputs(report, incident);

        var download = service.downloadIncident("incident-1", USER, new MockHttpServletRequest());

        assertThat(download.filename()).isEqualTo("rca-evidence-bundle-report-1.zip");
        assertThat(download.signatureEnabled()).isTrue();
        verify(audit).user(
            eq(USER),
            eq("evidence.bundle_exported"),
            eq("incident"),
            eq("incident-1"),
            eq("success"),
            argThat(details -> details.get("evidence_count").equals(1)
                && ((Number) details.get("raw_bytes")).longValue() > 0),
            any()
        );
    }

    private void stubBundleInputs(RcaReport report, Incident incident) {
        when(reports.findReport(report.reportId())).thenReturn(Optional.of(report));
        when(reports.findReport(incident.latestReportId())).thenReturn(Optional.of(report));
        when(incidents.find(incident.incidentId())).thenReturn(Optional.of(incident));
        when(evidence.listForNodeWindow(
            eq(incident.clusterId()),
            eq(incident.nodeName()),
            any(Instant.class),
            any(Instant.class)
        )).thenReturn(List.of(evidenceBundle()));
        when(timelines.build(incident)).thenReturn(new IncidentTimeline(
            incident.incidentId(),
            incident.firstSeenAt(),
            incident.lastSeenAt(),
            List.of(),
            List.of(),
            Map.of("node_count", 1)
        ));
    }

    private RcaReport report(String reportId, String incidentId) {
        return new RcaReport(
            reportId,
            "cluster-a",
            incidentId,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Inode exhaustion", Confidence.high),
            List.of(Map.of("type", "derived_signals", "signals", List.of())),
            List.of(),
            List.of(),
            List.of(),
            NOW
        );
    }

    private Incident incident(String incidentId, String latestReportId) {
        return new Incident(
            incidentId,
            "cluster-a",
            "worker-a",
            "DiskPressure",
            "Inode exhaustion",
            IncidentStatus.open,
            1,
            NOW.minusSeconds(300),
            NOW,
            "evidence-1",
            latestReportId,
            null,
            null,
            null,
            null,
            0,
            List.of("worker-a")
        );
    }

    private EvidenceBundle evidenceBundle() {
        return new EvidenceBundle(
            "evidence-1",
            "cluster-a",
            "worker-a",
            "DiskPressure",
            NOW,
            Map.of(
                "disk", Map.of("disk_usage_percent", 98),
                "kernel", Map.of("message", "EXT4-fs error")
            )
        );
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }
}
