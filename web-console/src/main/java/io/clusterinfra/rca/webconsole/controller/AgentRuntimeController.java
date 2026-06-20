package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionPollRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionResultRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentRealtimeEventBatch;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.service.RealtimeEventService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.GONE;

@RestController
public class AgentRuntimeController {
    private final RealtimeEventService realtimeEvents;

    public AgentRuntimeController(RealtimeEventService realtimeEvents) {
        this.realtimeEvents = realtimeEvents;
    }

    @PostMapping("/api/agents/realtime-events")
    public List<RealtimeEvent> realtimeEvents(@Valid @RequestBody AgentRealtimeEventBatch request) {
        List<RealtimeEvent> saved = new ArrayList<>();
        request.eventsOrEmpty().forEach(event ->
            saved.add(realtimeEvents.ingest(request.clusterId(), request.nodeName(), event))
        );
        return saved;
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
