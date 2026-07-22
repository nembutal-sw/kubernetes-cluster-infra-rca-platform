package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionPollRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionResultRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentNodeTokenRotateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentRealtimeEventBatch;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.RealtimeEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.GONE;

@RestController
public class AgentRuntimeController {
    private final RealtimeEventService realtimeEvents;
    private final AgentRepository agents;
    private final AuditService audit;

    public AgentRuntimeController(
        RealtimeEventService realtimeEvents,
        AgentRepository agents,
        AuditService audit
    ) {
        this.realtimeEvents = realtimeEvents;
        this.agents = agents;
        this.audit = audit;
    }

    @PostMapping("/api/agents/realtime-events")
    public List<RealtimeEvent> realtimeEvents(@Valid @RequestBody AgentRealtimeEventBatch request) {
        List<RealtimeEvent> saved = new ArrayList<>();
        request.eventsOrEmpty().forEach(event ->
            saved.add(realtimeEvents.ingest(request.clusterId(), request.nodeName(), event))
        );
        return saved;
    }

    @PostMapping("/api/agents/token/rotate")
    public Map<String, Object> rotateNodeToken(
        @Valid @RequestBody AgentNodeTokenRotateRequest request,
        HttpServletRequest servletRequest
    ) {
        Instant issuedAt = Instant.now();
        String nodeToken = agents.rotateNodeToken(request.clusterId(), request.nodeName());
        audit.record(
            "agent",
            request.nodeName(),
            "agent.node_token_rotation_requested",
            "agent",
            request.clusterId() + "/" + request.nodeName(),
            "success",
            Map.of("pending_expires_at", issuedAt.plusSeconds(600)),
            servletRequest
        );
        return Map.of(
            "cluster_id", request.clusterId(),
            "node_name", request.nodeName(),
            "node_token", nodeToken,
            "issued_at", issuedAt,
            "expires_at", issuedAt.plusSeconds(600)
        );
    }

    @PostMapping("/api/agents/action-executions")
    public List<ActionExecution> pollActions(@Valid @RequestBody AgentActionPollRequest request) {
        return List.of();
    }

    @PostMapping("/api/agents/action-results")
    public ActionExecution submitActionResult(@Valid @RequestBody AgentActionResultRequest request) {
        throw new ResponseStatusException(
            GONE,
            "agent-side action execution is disabled; use the approval and manual handling workflow"
        );
    }
}
