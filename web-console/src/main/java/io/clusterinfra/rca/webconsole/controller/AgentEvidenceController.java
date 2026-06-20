package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidencePollRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@RestController
public class AgentEvidenceController {
    private final RcaRepository repository;
    private final AccessService access;
    private final AuditService audit;
    private final RcaConsoleProperties properties;

    public AgentEvidenceController(
        RcaRepository repository,
        AccessService access,
        AuditService audit,
        RcaConsoleProperties properties
    ) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.properties = properties;
    }

    @PostMapping("/api/agents/register")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeAgentRegistrationResponse register(@Valid @RequestBody NodeAgentRegisterRequest request) {
        access.verifyBootstrapToken(request.clusterId(), request.agentToken());
        NodeAgentRegistrationResponse registered = repository.registerAgent(request);
        audit.record(
            "agent",
            request.nodeName(),
            "agent.register",
            "cluster",
            request.clusterId(),
            "success",
            java.util.Map.of("agent_version", request.agentVersion())
        );
        return registered;
    }

    @PostMapping("/api/agents/heartbeat")
    public NodeAgent heartbeat(@Valid @RequestBody NodeAgentHeartbeatRequest request) {
        access.verifyAgentIdentity(
            request.clusterId(),
            request.nodeName(),
            request.agentToken(),
            request.nodeToken()
        );
        return repository.recordAgentHeartbeat(request)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "agent not registered"));
    }

    @PostMapping("/api/evidence/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvidenceRequest createRequest(@Valid @RequestBody EvidenceRequestCreateRequest request) {
        if (repository.getCluster(request.clusterId()).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "cluster not found");
        }
        if (repository.getAgent(request.clusterId(), request.nodeName()).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "agent not found");
        }
        return repository.createEvidenceRequest(request);
    }

    @GetMapping("/api/evidence/requests/{requestId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public EvidenceRequest request(@PathVariable String requestId) {
        return repository.getEvidenceRequest(requestId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "evidence request not found"));
    }

    @GetMapping("/api/evidence/{evidenceId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public EvidenceBundle evidence(@PathVariable String evidenceId) {
        return repository.getEvidence(evidenceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "evidence not found"));
    }

    @PostMapping("/api/agents/evidence-requests")
    public List<EvidenceRequest> poll(@Valid @RequestBody AgentEvidencePollRequest request) {
        access.verifyAgentIdentity(
            request.clusterId(),
            request.nodeName(),
            request.agentToken(),
            request.nodeToken()
        );
        return repository.listEvidenceRequests(
            request.clusterId(),
            request.nodeName(),
            EvidenceRequestStatus.pending,
            request.limitOrDefault()
        );
    }

    @PostMapping("/api/agents/evidence-responses")
    public EvidenceRequest submit(@Valid @RequestBody AgentEvidenceSubmitRequest request) {
        access.verifyAgentIdentity(
            request.clusterId(),
            request.nodeName(),
            request.agentToken(),
            request.nodeToken()
        );
        if (request.statusOrDefault() != EvidenceRequestStatus.completed
            && request.statusOrDefault() != EvidenceRequestStatus.failed) {
            throw new ResponseStatusException(
                UNPROCESSABLE_ENTITY,
                "evidence response status must be completed or failed"
            );
        }
        EvidenceRequest assigned = repository.getEvidenceRequest(request.requestId())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "evidence request not found"));
        if (!assigned.clusterId().equals(request.clusterId()) || !assigned.nodeName().equals(request.nodeName())) {
            throw new ResponseStatusException(FORBIDDEN, "evidence request is assigned to another agent");
        }
        if (assigned.status() != EvidenceRequestStatus.pending) {
            throw new ResponseStatusException(CONFLICT, "evidence request is already closed");
        }
        EvidenceRequest submitted = repository.submitEvidenceResponse(
            request,
            properties.getPipeline().getMaxAttempts()
        )
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "evidence request not found"));
        audit.record(
            "agent",
            request.nodeName(),
            "evidence.submit",
            "evidence_request",
            request.requestId(),
            submitted.status().name(),
            java.util.Map.of("cluster_id", request.clusterId())
        );
        return submitted;
    }
}
