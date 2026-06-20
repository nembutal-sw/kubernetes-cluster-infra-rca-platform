package io.clusterinfra.rca.webconsole.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionApprovalResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.RcaService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class RcaController {
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ReportRepository reports;
    private final IncidentRepository incidents;
    private final AnalysisTaskRepository analysisTasks;
    private final ActionRepository actions;
    private final AuditRepository audits;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final RcaService rcaService;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public RcaController(
        ReportRepository reports,
        IncidentRepository incidents,
        AnalysisTaskRepository analysisTasks,
        ActionRepository actions,
        AuditRepository audits,
        AgentRepository agents,
        EvidenceRepository evidence,
        RcaService rcaService,
        AccessService access,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.reports = reports;
        this.incidents = incidents;
        this.analysisTasks = analysisTasks;
        this.actions = actions;
        this.audits = audits;
        this.agents = agents;
        this.evidence = evidence;
        this.rcaService = rcaService;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/webhooks/alertmanager")
    public WebhookIngestResponse alertmanager(
        @Valid @RequestBody AlertmanagerPayload payload,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(name = "X-Webhook-Token", required = false) String webhookToken
    ) {
        access.verifyWebhookToken(authorization, webhookToken);
        WebhookIngestResponse response = rcaService.ingestAlertmanager(payload);
        audit.system(
            "alertmanager",
            "webhook.ingest",
            "webhook",
            "alertmanager",
            "success",
            Map.of(
                "received_alerts", response.receivedAlerts(),
                "created_reports", response.createdReports().size(),
                "queued_analysis_tasks", response.queuedAnalysisTasks().size(),
                "created_evidence_requests", response.createdEvidenceRequests().size(),
                "skipped_alerts", response.skippedAlerts().size()
            )
        );
        return response;
    }

    @GetMapping("/api/rca/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<RcaJob> jobs() {
        return reports.listJobs();
    }

    @GetMapping("/api/rca/jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public RcaJob job(@PathVariable String jobId) {
        return reports.findJob(jobId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "RCA job not found"));
    }

    @GetMapping("/api/rca/analysis-tasks")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<AnalysisTask> analysisTasks(
        @RequestParam(name = "status", required = false) AnalysisTaskStatus status,
        @RequestParam(name = "limit", defaultValue = "200") Integer limit
    ) {
        return analysisTasks.list(status, limit);
    }

    @GetMapping("/api/rca/analysis-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public AnalysisTask analysisTask(@PathVariable String taskId) {
        return analysisTasks.find(taskId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "analysis task not found"));
    }

    @PostMapping("/api/rca/analysis-tasks/{taskId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public AnalysisTask retryAnalysisTask(
        @PathVariable String taskId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "analysis retry confirmation is required");
        }
        UserAccount user = access.currentUser(authentication);
        AnalysisTask task = analysisTasks.retry(taskId)
            .orElseThrow(() -> new ResponseStatusException(
                CONFLICT,
                "only dead-letter analysis tasks can be retried"
            ));
        audit.user(
            user,
            "analysis.task_requeued",
            "analysis_task",
            taskId,
            "queued",
            Map.of("note", request.note() == null ? "" : request.note())
        );
        return task;
    }

    @GetMapping("/api/rca/reports")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<RcaReport> reports() {
        return reports.listReports();
    }

    @GetMapping("/api/rca/reports/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public RcaReport report(@PathVariable String reportId) {
        return requireReport(reportId);
    }

    @GetMapping("/api/rca/incidents")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<Incident> incidents(
        @RequestParam(name = "cluster_id", required = false) String clusterId
    ) {
        return incidents.list(clusterId);
    }

    @GetMapping("/api/rca/incidents/{incidentId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public Incident incident(@PathVariable String incidentId) {
        return incidents.find(incidentId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "incident not found"));
    }

    @PostMapping("/api/rca/incidents/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Incident resolveIncident(
        @PathVariable String incidentId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication
    ) {
        return changeIncidentStatus(incidentId, request, authentication, IncidentStatus.resolved);
    }

    @PostMapping("/api/rca/incidents/{incidentId}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Incident reopenIncident(
        @PathVariable String incidentId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication
    ) {
        return changeIncidentStatus(incidentId, request, authentication, IncidentStatus.open);
    }

    private Incident changeIncidentStatus(
        String incidentId,
        ActionDecisionRequest request,
        Authentication authentication,
        IncidentStatus status
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "incident status confirmation is required");
        }
        UserAccount user = access.currentUser(authentication);
        Incident incident = incidents.updateStatus(incidentId, status)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "incident not found"));
        audit.user(
            user,
            "incident.status_change",
            "incident",
            incidentId,
            status.name(),
            Map.of("note", request.note() == null ? "" : request.note())
        );
        return incident;
    }

    @GetMapping("/api/rca/action-requests")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<ActionRequest> actionRequests(
        @RequestParam(name = "report_id", required = false) String reportId
    ) {
        return actions.listRequests(reportId);
    }

    @GetMapping("/api/rca/action-executions")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<ActionExecution> actionExecutions(
        @RequestParam(name = "report_id", required = false) String reportId
    ) {
        return actions.listExecutions(reportId);
    }

    @GetMapping("/api/audit/events")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditEvent> auditEvents(
        @RequestParam(name = "limit", defaultValue = "200") Integer limit
    ) {
        return audits.list(limit);
    }

    @GetMapping("/api/rca/reports/export")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<byte[]> exportReports(
        @RequestParam(name = "cluster_id", required = false) String clusterId,
        @RequestParam(name = "format", defaultValue = "json") String format
    ) {
        requireJson(format);
        List<RcaReport> reports = this.reports.listReports().stream()
            .filter(report -> clusterId == null || clusterId.equals(report.clusterId()))
            .toList();
        return attachment(
            exportPayload(reports, Map.of("cluster_id", clusterId == null ? "" : clusterId)),
            "rca-reports-" + safeFilename(clusterId == null ? "all" : clusterId)
        );
    }

    @GetMapping("/api/rca/reports/{reportId}/export")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<byte[]> exportReport(
        @PathVariable String reportId,
        @RequestParam(name = "format", defaultValue = "json") String format
    ) {
        requireJson(format);
        return attachment(
            exportPayload(List.of(requireReport(reportId)), Map.of("report_id", reportId)),
            "rca-report-" + safeFilename(reportId)
        );
    }

    @PostMapping("/api/rca/reports/{reportId}/actions/{actionIndex}/execute")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ActionExecutionResponse execute(
        @PathVariable String reportId,
        @PathVariable int actionIndex,
        @Valid @RequestBody ActionExecutionRequest request,
        Authentication authentication
    ) {
        UserAccount user = access.currentUser(authentication);
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "action confirmation is required");
        }
        RcaReport report = requireReport(reportId);
        if (actionIndex < 0 || actionIndex >= report.recommendedActions().size()) {
            throw new ResponseStatusException(NOT_FOUND, "recommended action not found");
        }
        RecommendedAction action = report.recommendedActions().get(actionIndex);
        if (!action.automationAllowed()) {
            ActionRequestStatus status = action.policy() == PolicyLevel.APPROVAL_REQUIRED
                && !"llm".equals(action.source())
                ? ActionRequestStatus.pending_approval
                : ActionRequestStatus.blocked;
            ActionRequest actionRequest = actions.createRequest(
                reportId,
                actionIndex,
                action.actionKey(),
                action.policy(),
                action.source(),
                status,
                user.email(),
                request.note(),
                null
            );
            ActionExecution actionExecution = null;
            if (status == ActionRequestStatus.pending_approval
                && action.executionPlan() != null
                && action.executionPlan().executable()) {
                String nodeName = targetNode(report);
                if (nodeName == null || agents.find(report.clusterId(), nodeName).isEmpty()) {
                    status = ActionRequestStatus.blocked;
                    actions.decide(
                        actionRequest.actionRequestId(),
                        status,
                        user.email(),
                        "Target node agent is not registered."
                    );
                } else {
                    actionExecution = actions.createExecution(
                        actionRequest,
                        report,
                        nodeName,
                        action.executionPlan()
                    );
                }
            }
            auditAction(user, actionRequest, status.name());
            return new ActionExecutionResponse(
                reportId,
                actionIndex,
                action.actionKey(),
                action.policy(),
                status.name(),
                status == ActionRequestStatus.pending_approval
                    ? "Approval request recorded. Approval does not execute a node mutation automatically."
                    : action.source().equals("llm")
                        ? "LLM-origin actions are diagnostic suggestions and cannot trigger automation."
                        : "Policy Engine does not allow this action to execute automatically.",
                false,
                action.requiresApproval(),
                null,
                action.guardrails(),
                actions.findRequest(actionRequest.actionRequestId()).orElse(actionRequest),
                actionExecution
            );
        }
        String nodeName = targetNode(report);
        if (nodeName == null) {
            return blocked(
                reportId,
                actionIndex,
                action,
                user,
                request.note(),
                "No target node was found in the RCA report scope.",
                "missing_target_node"
            );
        }
        if (agents.find(report.clusterId(), nodeName).isEmpty()) {
            return blocked(
                reportId,
                actionIndex,
                action,
                user,
                request.note(),
                "Target node agent is not registered, so evidence collection cannot be requested.",
                "agent_not_registered"
            );
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("report_id", reportId);
        context.put("action_index", actionIndex);
        context.put("action_key", action.actionKey());
        context.put("action_source", action.source());
        context.put("policy", action.policy().name());
        context.put("requested_by", user.email());
        context.put("note", request.note() == null ? "" : request.note());
        EvidenceRequest evidenceRequest = evidence.createRequest(new EvidenceRequestCreateRequest(
            report.clusterId(),
            nodeName,
            String.valueOf(report.trigger().getOrDefault("alert_name", report.summary().symptom())),
            collectorsForAction(action.actionKey()),
            Map.of("source", "rca_action", "report_created_at", report.createdAt().toString()),
            "RCA read-only action confirmed: " + action.action(),
            context
        ));
        ActionRequest actionRequest = actions.createRequest(
            reportId,
            actionIndex,
            action.actionKey(),
            action.policy(),
            action.source(),
            ActionRequestStatus.accepted,
            user.email(),
            request.note(),
            evidenceRequest.requestId()
        );
        auditAction(user, actionRequest, "accepted");
        return new ActionExecutionResponse(
            reportId,
            actionIndex,
            action.actionKey(),
            action.policy(),
            "accepted",
            "Read-only evidence collection was requested for the node agent.",
            true,
            false,
            evidenceRequest,
            action.guardrails(),
            actionRequest,
            null
        );
    }

    @PostMapping("/api/rca/action-requests/{actionRequestId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ActionApprovalResponse approveActionRequest(
        @PathVariable String actionRequestId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication
    ) {
        boolean executable = actions.findExecutionByRequest(actionRequestId).isPresent();
        ActionRequest decided = decideActionRequest(
            actionRequestId,
            request,
            authentication,
            executable ? ActionRequestStatus.queued : ActionRequestStatus.approved_manual
        );
        ActionExecution execution = executable
            ? actions.approveExecution(actionRequestId, decided.reviewedBy())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "approved execution was not found"))
            : null;
        return new ActionApprovalResponse(decided, execution);
    }

    @PostMapping("/api/rca/action-requests/{actionRequestId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ActionApprovalResponse rejectActionRequest(
        @PathVariable String actionRequestId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication
    ) {
        ActionRequest decided = decideActionRequest(
            actionRequestId,
            request,
            authentication,
            ActionRequestStatus.rejected
        );
        ActionExecution execution = actions.rejectExecution(actionRequestId).orElse(null);
        return new ActionApprovalResponse(decided, execution);
    }

    private ActionRequest decideActionRequest(
        String actionRequestId,
        ActionDecisionRequest request,
        Authentication authentication,
        ActionRequestStatus decision
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "action decision confirmation is required");
        }
        UserAccount user = access.currentUser(authentication);
        ActionRequest existing = actions.findRequest(actionRequestId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "action request not found"));
        if (existing.status() != ActionRequestStatus.pending_approval) {
            throw new ResponseStatusException(CONFLICT, "action request is not pending approval");
        }
        ActionRequest decided = actions.decide(
            actionRequestId,
            decision,
            user.email(),
            request.note()
        ).orElseThrow(() -> new ResponseStatusException(CONFLICT, "action request was already decided"));
        auditAction(user, decided, decision.name());
        return decided;
    }

    private ActionExecutionResponse blocked(
        String reportId,
        int actionIndex,
        RecommendedAction action,
        UserAccount user,
        String note,
        String message,
        String guardrail
    ) {
        List<String> guardrails = new java.util.ArrayList<>(action.guardrails());
        guardrails.add(guardrail);
        ActionRequest actionRequest = actions.createRequest(
            reportId,
            actionIndex,
            action.actionKey(),
            action.policy(),
            action.source(),
            ActionRequestStatus.blocked,
            user.email(),
            note,
            null
        );
        auditAction(user, actionRequest, "blocked");
        return new ActionExecutionResponse(
            reportId,
            actionIndex,
            action.actionKey(),
            action.policy(),
            "blocked",
            message,
            false,
            action.requiresApproval(),
            null,
            guardrails,
            actionRequest,
            null
        );
    }

    private void auditAction(UserAccount user, ActionRequest request, String outcome) {
        audit.user(
            user,
            "rca.action_request",
            "action_request",
            request.actionRequestId(),
            outcome,
            Map.of(
                "report_id", request.reportId(),
                "action_key", request.actionKey(),
                "policy", request.policy().name(),
                "source", request.source()
            )
        );
    }

    private List<String> collectorsForAction(String actionKey) {
        return switch (actionKey == null ? "" : actionKey) {
            case "inspect_storage_state" -> List.of("disk", "inode", "kernel", "systemd");
            case "inspect_network_state" -> List.of("network", "cni", "dns", "conntrack", "kernel");
            case "inspect_kernel_state" -> List.of("kernel", "systemd", "process");
            case "collect_linux_low_level_evidence" ->
                List.of("systemd", "runtime", "kubelet", "kernel", "disk", "inode", "memory", "process", "network", "conntrack");
            default -> List.of("node", "kubernetes", "systemd", "runtime", "kernel", "disk", "inode", "memory", "network");
        };
    }

    private String targetNode(RcaReport report) {
        Object nodes = report.scope().get("nodes");
        if (nodes instanceof List<?> list && !list.isEmpty() && list.getFirst() != null) {
            return String.valueOf(list.getFirst());
        }
        return null;
    }

    private RcaReport requireReport(String reportId) {
        return reports.findReport(reportId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "RCA report not found"));
    }

    private void requireJson(String format) {
        if (!"json".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "only json export is supported");
        }
    }

    private Map<String, Object> exportPayload(List<RcaReport> reports, Map<String, Object> filters) {
        return Map.of(
            "schema_version", "1.0",
            "exported_at", Instant.now().toString(),
            "filters", filters,
            "report_count", reports.size(),
            "reports", reports
        );
    }

    private ResponseEntity<byte[]> attachment(Map<String, Object> payload, String prefix) {
        try {
            byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
            headers.setContentDisposition(ContentDisposition.attachment()
                .filename(prefix + "-" + FILE_TIME.format(Instant.now()) + ".json", StandardCharsets.UTF_8)
                .build());
            headers.setCacheControl("no-store");
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("report export serialization failed", exception);
        }
    }

    private String safeFilename(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
