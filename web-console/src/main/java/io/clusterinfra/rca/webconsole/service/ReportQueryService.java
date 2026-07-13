package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ReportQueryService {
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ReportRepository reports;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ReportQueryService(ReportRepository reports, AuditService audit, ObjectMapper objectMapper) {
        this.reports = reports;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<RcaJob> listJobs() {
        return reports.listJobs();
    }

    public RcaJob requireJob(String jobId) {
        return reports.findJob(jobId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "RCA job not found"));
    }

    public List<RcaReport> listReports() {
        return reports.listReports();
    }

    public CursorPage<RcaReport> pageReports(
        String clusterId,
        RcaJobStatus status,
        String query,
        String cursor,
        Integer limit
    ) {
        return reports.pageReports(clusterId, status, query, cursor, limit);
    }

    public RcaReport requireReport(String reportId) {
        return reports.findReport(reportId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "RCA report not found"));
    }

    public ReportExport exportReports(
        String clusterId,
        String format,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        requireJson(format);
        List<RcaReport> selectedReports = reports.listReports().stream()
            .filter(report -> clusterId == null || clusterId.equals(report.clusterId()))
            .toList();
        audit.user(
            user,
            "rca.reports_exported",
            "rca_report",
            clusterId == null ? "all" : clusterId,
            "success",
            Map.of("cluster_id", clusterId == null ? "" : clusterId, "report_count", selectedReports.size()),
            servletRequest
        );
        return export(
            exportPayload(selectedReports, Map.of("cluster_id", clusterId == null ? "" : clusterId)),
            "rca-reports-" + safeFilename(clusterId == null ? "all" : clusterId)
        );
    }

    public ReportExport exportReport(
        String reportId,
        String format,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        requireJson(format);
        RcaReport report = requireReport(reportId);
        audit.user(
            user,
            "rca.report_exported",
            "rca_report",
            reportId,
            "success",
            Map.of("cluster_id", report.clusterId()),
            servletRequest
        );
        return export(
            exportPayload(List.of(report), Map.of("report_id", reportId)),
            "rca-report-" + safeFilename(reportId)
        );
    }

    private void requireJson(String format) {
        if (!"json".equalsIgnoreCase(format == null ? "" : format.trim())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "only json export is supported");
        }
    }

    private Map<String, Object> exportPayload(List<RcaReport> reports, Map<String, Object> filters) {
        return Map.of(
            "schema_version", "1.0",
            "exported_at", Instant.now().toString(),
            "filters", filters,
            "report_count", reports.size(),
            "reports", reports
        );
    }

    private ReportExport export(Map<String, Object> payload, String prefix) {
        try {
            return new ReportExport(
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload),
                prefix + "-" + FILE_TIME.format(Instant.now()) + ".json"
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("report export serialization failed", exception);
        }
    }

    private String safeFilename(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    public record ReportExport(
        byte[] body,
        String filename
    ) {
    }
}
