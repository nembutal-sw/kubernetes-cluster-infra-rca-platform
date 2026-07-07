package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AuditSearchCriteria;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditExportService;
import io.clusterinfra.rca.webconsole.service.AuditExportService.AuditExport;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {
    private final AuditExportService auditExport;
    private final AccessService access;

    public AuditController(
        AuditExportService auditExport,
        AccessService access
    ) {
        this.auditExport = auditExport;
        this.access = access;
    }

    @GetMapping("/api/audit/events")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public List<AuditEvent> auditEvents(
        @RequestParam(name = "actor_type", required = false) String actorType,
        @RequestParam(name = "actor_id", required = false) String actorId,
        @RequestParam(name = "event_type", required = false) String eventType,
        @RequestParam(name = "resource_type", required = false) String resourceType,
        @RequestParam(name = "resource_id", required = false) String resourceId,
        @RequestParam(required = false) String outcome,
        @RequestParam(name = "client_ip", required = false) String clientIp,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(name = "limit", defaultValue = "200") Integer limit
    ) {
        return auditExport.search(new AuditSearchCriteria(
            actorType,
            actorId,
            eventType,
            resourceType,
            resourceId,
            outcome,
            clientIp,
            query,
            from,
            to,
            limit == null ? 200 : limit
        ), 1000);
    }

    @GetMapping("/api/audit/events/export")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public ResponseEntity<byte[]> exportAuditEvents(
        @RequestParam(name = "actor_type", required = false) String actorType,
        @RequestParam(name = "actor_id", required = false) String actorId,
        @RequestParam(name = "event_type", required = false) String eventType,
        @RequestParam(name = "resource_type", required = false) String resourceType,
        @RequestParam(name = "resource_id", required = false) String resourceId,
        @RequestParam(required = false) String outcome,
        @RequestParam(name = "client_ip", required = false) String clientIp,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(defaultValue = "1000") int limit,
        @RequestParam(defaultValue = "json") String format,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return attachment(auditExport.export(
            new AuditSearchCriteria(
                actorType,
                actorId,
                eventType,
                resourceType,
                resourceId,
                outcome,
                clientIp,
                query,
                from,
                to,
                limit
            ),
            format,
            user,
            servletRequest
        ));
    }

    private ResponseEntity<byte[]> attachment(AuditExport export) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(export.mediaType() + ";charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(export.filename(), StandardCharsets.UTF_8)
            .build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(export.body(), headers, HttpStatus.OK);
    }
}
