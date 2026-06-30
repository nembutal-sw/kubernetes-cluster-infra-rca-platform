package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundleManifestSummary;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService.ExportedBundle;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        return response(exports.exportIncident(incidentId), "incident", incidentId, authentication, servletRequest);
    }

    @GetMapping("/api/rca/incidents/{incidentId}/bundle/manifest")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvidenceBundleManifestSummary incidentBundleManifest(@PathVariable String incidentId) {
        return exports.incidentManifest(incidentId);
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
        headers.add("X-RCA-Bundle-Filename", bundle.filename());
        headers.add("X-RCA-Bundle-Evidence-Count", String.valueOf(bundle.evidenceCount()));
        headers.add("X-RCA-Bundle-Raw-Bytes", String.valueOf(bundle.rawBytes()));
        headers.add("X-RCA-Bundle-Zip-Bytes", String.valueOf(bundle.zipBytes()));
        headers.add("X-RCA-Bundle-Hash-Algorithm", String.valueOf(bundle.manifest().getOrDefault("hash_algorithm", "")));
        headers.add("X-RCA-Bundle-Entry-Count", String.valueOf(entryCount(bundle.manifest().get("entries"))));
        Map<String, Object> signature = signature(bundle.manifest());
        headers.add("X-RCA-Bundle-Signature-Enabled", String.valueOf(Boolean.TRUE.equals(signature.get("enabled"))));
        if (signature.get("key_id") != null) {
            headers.add("X-RCA-Bundle-Signature-Key-Id", String.valueOf(signature.get("key_id")));
        }
        return ResponseEntity.ok().headers(headers).body(bundle.content());
    }

    private int entryCount(Object value) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : 0;
    }

    private Map<String, Object> signature(Map<String, Object> manifest) {
        Object signature = manifest.get("signature");
        if (!(signature instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
