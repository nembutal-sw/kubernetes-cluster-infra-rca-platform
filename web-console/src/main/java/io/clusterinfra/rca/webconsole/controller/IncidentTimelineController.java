package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentTimeline;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.service.IncidentTimelineService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class IncidentTimelineController {
    private final IncidentRepository incidents;
    private final IncidentTimelineService timelines;

    public IncidentTimelineController(IncidentRepository incidents, IncidentTimelineService timelines) {
        this.incidents = incidents;
        this.timelines = timelines;
    }

    @GetMapping("/api/rca/incidents/{incidentId}/timeline")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public IncidentTimeline timeline(@PathVariable String incidentId) {
        Incident incident = incidents.find(incidentId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "incident not found"));
        return timelines.build(incident);
    }
}
