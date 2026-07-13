package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.IncidentWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IncidentController {
    private final IncidentWorkflowService incidents;
    private final AccessService access;

    public IncidentController(
        IncidentWorkflowService incidents,
        AccessService access
    ) {
        this.incidents = incidents;
        this.access = access;
    }

    @GetMapping("/api/rca/incidents")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public List<Incident> incidents(
        @RequestParam(name = "cluster_id", required = false) String clusterId
    ) {
        return incidents.list(clusterId);
    }

    @GetMapping("/api/v1/rca/incidents")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public CursorPage<Incident> incidentPage(
        @RequestParam(name = "cluster_id", required = false) String clusterId,
        @RequestParam(name = "status", required = false) IncidentStatus status,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "50") Integer limit
    ) {
        return incidents.page(clusterId, status, query, cursor, limit);
    }

    @GetMapping("/api/rca/incidents/{incidentId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public Incident incident(@PathVariable String incidentId) {
        return incidents.requireIncident(incidentId);
    }

    @PostMapping("/api/rca/incidents/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Incident resolveIncident(
        @PathVariable String incidentId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return incidents.resolve(incidentId, request, user, servletRequest);
    }

    @PostMapping("/api/rca/incidents/{incidentId}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Incident reopenIncident(
        @PathVariable String incidentId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return incidents.reopen(incidentId, request, user, servletRequest);
    }
}
