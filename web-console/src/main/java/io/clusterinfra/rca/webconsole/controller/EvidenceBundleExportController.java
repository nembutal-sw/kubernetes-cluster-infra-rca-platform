package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundleManifestSummary;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService.DownloadableBundle;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
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

    public EvidenceBundleExportController(
        EvidenceBundleExportService exports,
        AccessService access
    ) {
        this.exports = exports;
        this.access = access;
    }

    @GetMapping("/api/rca/reports/{reportId}/bundle")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> reportBundle(
        @PathVariable String reportId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return response(exports.downloadReport(reportId, user, servletRequest));
    }

    @GetMapping("/api/rca/reports/{reportId}/bundle/manifest")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvidenceBundleManifestSummary reportBundleManifest(@PathVariable String reportId) {
        return exports.reportManifest(reportId);
    }

    @GetMapping("/api/rca/incidents/{incidentId}/bundle")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<byte[]> incidentBundle(
        @PathVariable String incidentId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return response(exports.downloadIncident(incidentId, user, servletRequest));
    }

    @GetMapping("/api/rca/incidents/{incidentId}/bundle/manifest")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvidenceBundleManifestSummary incidentBundleManifest(@PathVariable String incidentId) {
        return exports.incidentManifest(incidentId);
    }

    private ResponseEntity<byte[]> response(DownloadableBundle bundle) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(bundle.filename(), StandardCharsets.UTF_8)
            .build());
        headers.setCacheControl("no-store");
        headers.add("X-RCA-Bundle-Filename", bundle.filename());
        headers.add("X-RCA-Bundle-Evidence-Count", String.valueOf(bundle.evidenceCount()));
        headers.add("X-RCA-Bundle-Raw-Bytes", String.valueOf(bundle.rawBytes()));
        headers.add("X-RCA-Bundle-Zip-Bytes", String.valueOf(bundle.zipBytes()));
        headers.add("X-RCA-Bundle-Hash-Algorithm", bundle.hashAlgorithm());
        headers.add("X-RCA-Bundle-Entry-Count", String.valueOf(bundle.entryCount()));
        headers.add("X-RCA-Bundle-Signature-Enabled", String.valueOf(bundle.signatureEnabled()));
        if (!bundle.signatureKeyId().isBlank()) {
            headers.add("X-RCA-Bundle-Signature-Key-Id", bundle.signatureKeyId());
        }
        return ResponseEntity.ok().headers(headers).body(bundle.content());
    }
}
