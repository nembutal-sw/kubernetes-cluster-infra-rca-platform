package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.ReportQueryService;
import io.clusterinfra.rca.webconsole.service.ReportQueryService.ReportExport;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
    private final ReportQueryService reportQuery;
    private final AccessService access;

    public ReportController(
        ReportQueryService reportQuery,
        AccessService access
    ) {
        this.reportQuery = reportQuery;
        this.access = access;
    }

    @GetMapping("/api/rca/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<RcaJob> jobs() {
        return reportQuery.listJobs();
    }

    @GetMapping("/api/rca/jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public RcaJob job(@PathVariable String jobId) {
        return reportQuery.requireJob(jobId);
    }

    @GetMapping("/api/rca/reports")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public List<RcaReport> reports() {
        return reportQuery.listReports();
    }

    @GetMapping("/api/rca/reports/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public RcaReport report(@PathVariable String reportId) {
        return reportQuery.requireReport(reportId);
    }

    @GetMapping("/api/rca/reports/export")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> exportReports(
        @RequestParam(name = "cluster_id", required = false) String clusterId,
        @RequestParam(name = "format", defaultValue = "json") String format,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return attachment(reportQuery.exportReports(clusterId, format, user, servletRequest));
    }

    @GetMapping("/api/rca/reports/{reportId}/export")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> exportReport(
        @PathVariable String reportId,
        @RequestParam(name = "format", defaultValue = "json") String format,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return attachment(reportQuery.exportReport(reportId, format, user, servletRequest));
    }

    private ResponseEntity<byte[]> attachment(ReportExport export) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(export.filename(), StandardCharsets.UTF_8)
            .build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(export.body(), headers, HttpStatus.OK);
    }
}
