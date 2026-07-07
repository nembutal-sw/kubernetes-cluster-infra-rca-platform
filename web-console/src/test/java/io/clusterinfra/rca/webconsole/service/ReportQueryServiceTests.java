package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ReportQueryServiceTests {
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
    private AuditService audit;
    private ObjectMapper objectMapper;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        reports = mock(ReportRepository.class);
        audit = mock(AuditService.class);
        objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        service = new ReportQueryService(reports, audit, objectMapper);
    }

    @Test
    void requireJobAndReportReturn404WhenMissing() {
        when(reports.findJob("missing-job")).thenReturn(Optional.empty());
        when(reports.findReport("missing-report")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireJob("missing-job"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.requireReport("missing-report"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void exportReportsFiltersByClusterAndAuditsResult() throws Exception {
        when(reports.listReports()).thenReturn(List.of(
            report("report-a", "cluster-a"),
            report("report-b", "cluster-b"),
            report("report-c", "cluster-a")
        ));

        var export = service.exportReports(
            "cluster-a",
            "json",
            USER,
            new MockHttpServletRequest()
        );

        JsonNode payload = objectMapper.readTree(new String(export.body(), StandardCharsets.UTF_8));
        assertThat(payload.path("schema_version").asText()).isEqualTo("1.0");
        assertThat(payload.path("report_count").asInt()).isEqualTo(2);
        assertThat(payload.path("filters").path("cluster_id").asText()).isEqualTo("cluster-a");
        assertThat(payload.path("reports"))
            .extracting(node -> node.path("cluster_id").asText())
            .containsExactly("cluster-a", "cluster-a");
        assertThat(export.filename())
            .startsWith("rca-reports-cluster-a-")
            .endsWith(".json");

        verify(audit).user(
            eq(USER),
            eq("rca.reports_exported"),
            eq("rca_report"),
            eq("cluster-a"),
            eq("success"),
            eq(Map.of("cluster_id", "cluster-a", "report_count", 2)),
            any()
        );
    }

    @Test
    void exportSingleReportUsesSafeFilenameAndAuditsCluster() throws Exception {
        when(reports.findReport("report/a 1")).thenReturn(Optional.of(report("report/a 1", "cluster-a")));

        var export = service.exportReport(
            "report/a 1",
            " JSON ",
            USER,
            new MockHttpServletRequest()
        );

        JsonNode payload = objectMapper.readTree(new String(export.body(), StandardCharsets.UTF_8));
        assertThat(payload.path("report_count").asInt()).isEqualTo(1);
        assertThat(payload.path("filters").path("report_id").asText()).isEqualTo("report/a 1");
        assertThat(export.filename())
            .startsWith("rca-report-report_a_1-")
            .endsWith(".json");

        verify(audit).user(
            eq(USER),
            eq("rca.report_exported"),
            eq("rca_report"),
            eq("report/a 1"),
            eq("success"),
            eq(Map.of("cluster_id", "cluster-a")),
            any()
        );
    }

    @Test
    void exportRejectsNonJsonFormatBeforeReadingReports() {
        assertThatThrownBy(() -> service.exportReports(
            null,
            "csv",
            USER,
            new MockHttpServletRequest()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void listDelegatesToRepository() {
        RcaJob job = new RcaJob(
            "job-1",
            "cluster-a",
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            "report-a",
            "evidence-a",
            NOW
        );
        RcaReport report = report("report-a", "cluster-a");
        when(reports.listJobs()).thenReturn(List.of(job));
        when(reports.listReports()).thenReturn(List.of(report));

        assertThat(service.listJobs()).containsExactly(job);
        assertThat(service.listReports()).containsExactly(report);
    }

    private RcaReport report(String reportId, String clusterId) {
        return new RcaReport(
            reportId,
            clusterId,
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Storage pressure", Confidence.high),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            NOW
        );
    }
}
