package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class IncidentWorkflowService {
    private final IncidentRepository incidents;
    private final AuditService audit;

    public IncidentWorkflowService(IncidentRepository incidents, AuditService audit) {
        this.incidents = incidents;
        this.audit = audit;
    }

    public List<Incident> list(String clusterId) {
        return incidents.list(clusterId);
    }

    public CursorPage<Incident> page(
        String clusterId,
        IncidentStatus status,
        String query,
        String cursor,
        Integer limit
    ) {
        return incidents.page(clusterId, status, query, cursor, limit);
    }

    public Incident requireIncident(String incidentId) {
        return incidents.find(incidentId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "incident not found"));
    }

    @Transactional
    public Incident resolve(
        String incidentId,
        ActionDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        return changeStatus(incidentId, request, user, servletRequest, IncidentStatus.resolved);
    }

    @Transactional
    public Incident reopen(
        String incidentId,
        ActionDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        return changeStatus(incidentId, request, user, servletRequest, IncidentStatus.open);
    }

    private Incident changeStatus(
        String incidentId,
        ActionDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest,
        IncidentStatus status
    ) {
        if (request == null || !request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "incident status confirmation is required");
        }
        String note = request.note() == null ? "" : request.note();
        Incident incident = incidents.updateStatus(
            incidentId,
            status,
            "manual",
            note,
            Instant.now()
        ).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "incident not found"));
        audit.user(
            user,
            "incident.status_change",
            "incident",
            incidentId,
            status.name(),
            Map.of("note", note),
            servletRequest
        );
        return incident;
    }
}
