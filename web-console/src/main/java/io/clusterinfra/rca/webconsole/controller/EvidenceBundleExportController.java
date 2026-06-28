package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService.ExportedBundle;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvidenceBundleExportController {
    private final EvidenceBundleExportService exports;
    private final AccessService access;
    private final AuditService audit;

    public EvidenceBundleExportController(
        EvidenceBundleExportService exports,
        AccessService access,
        AuditService audit
    ) {
        this.exports = exports;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/rca/reports/{reportId}/bundle")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> reportBundle(
        @PathVariable String reportId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return response(exports.exportReport(reportId), "report", reportId, authentication, servletRequest);
    }

    @GetMapping("/api/rca/incidents/{incidentId}/bundle")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> incidentBundle(
        @PathVariable String incidentId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return response(exports.exportIncident(incidentId), "incident", incidentId, authentication, servletRequest);
    }

    private ResponseEntity<byte[]> response(
        ExportedBundle bundle,
        String resourceType,
        String resourceId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        audit.user(
            user,
            "evidence.bundle_exported",
            resourceType,
            resourceId,
            "success",
            Map.of("evidence_count", bundle.evidenceCount(), "raw_bytes", bundle.rawBytes()),
            servletRequest
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(bundle.filename(), StandardCharsets.UTF_8)
            .build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(bundle.content());
    }
}
