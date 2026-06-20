package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionPollRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentActionResultRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentRealtimeEventBatch;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.RealtimeEventService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@RestController
public class AgentRuntimeController {
    private final ActionRepository actions;
    private final AuditService audit;
    private final RealtimeEventService realtimeEvents;

    public AgentRuntimeController(
        ActionRepository actions,
        AuditService audit,
        RealtimeEventService realtimeEvents
    ) {
        this.actions = actions;
        this.audit = audit;
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
        Instant now = Instant.now();
        return actions.claimExecutions(
            request.clusterId(),
            request.nodeName(),
            "agent:" + request.nodeName(),
            request.limitOrDefault(),
            now,
            now.plus(5, ChronoUnit.MINUTES)
        );
    }

    @PostMapping("/api/agents/action-results")
    public ActionExecution submitActionResult(@Valid @RequestBody AgentActionResultRequest request) {
        ActionExecutionStatus status;
        try {
            status = ActionExecutionStatus.valueOf(request.status());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "invalid action result status");
        }
        if (status != ActionExecutionStatus.completed && status != ActionExecutionStatus.failed) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "action result must be completed or failed");
        }
        ActionExecution completed = actions.completeExecution(
            request.executionId(),
            "agent:" + request.nodeName(),
            status,
            request.exitCode(),
            request.stdout(),
            request.stderr(),
            request.errorMessage()
        ).orElseThrow(() -> new ResponseStatusException(CONFLICT, "action execution lease is not active"));
        audit.record(
            "agent", request.nodeName(), "action.execute", "action_execution",
            request.executionId(), status.name(), java.util.Map.of("command_key", completed.commandKey())
        );
        return completed;
    }
}
