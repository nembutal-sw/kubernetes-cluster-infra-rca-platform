package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionApprovalResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionManualCompletionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ActionWorkflowService {
    private final ReportRepository reports;
    private final ActionRepository actions;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final AuditService audit;
    private final RcaMetrics metrics;

    public ActionWorkflowService(
        ReportRepository reports,
        ActionRepository actions,
        AgentRepository agents,
        EvidenceRepository evidence,
        AuditService audit,
        RcaMetrics metrics
    ) {
        this.reports = reports;
        this.actions = actions;
        this.agents = agents;
        this.evidence = evidence;
        this.audit = audit;
        this.metrics = metrics;
    }

    public List<ActionRequest> listRequests(String reportId) {
        return actions.listRequests(reportId);
    }

    public List<ActionRequest> listRequests(String reportId, Integer limit) {
        return actions.listRequests(reportId, limit);
    }

    public List<ActionExecution> listExecutions(String reportId) {
        return actions.listExecutions(reportId);
    }

    @Transactional
    public ActionExecutionResponse execute(
        String reportId,
        int actionIndex,
        ActionExecutionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "action confirmation is required");
        }
        RcaReport report = requireReport(reportId);
        if (actionIndex < 0 || actionIndex >= report.recommendedActions().size()) {
            throw new ResponseStatusException(NOT_FOUND, "recommended action not found");
        }
        RecommendedAction action = report.recommendedActions().get(actionIndex);
        if (!action.automationAllowed()) {
            return recordNonExecutableAction(reportId, actionIndex, request, user, action, servletRequest);
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
                "missing_target_node",
                servletRequest
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
                "agent_not_registered",
                servletRequest
            );
        }

        EvidenceRequest evidenceRequest = evidence.createRequest(new EvidenceRequestCreateRequest(
            report.clusterId(),
            nodeName,
            String.valueOf(report.trigger().getOrDefault("alert_name", report.summary().symptom())),
            collectorsForAction(action.actionKey()),
            Map.of("source", "rca_action", "report_created_at", report.createdAt().toString()),
            "RCA read-only action confirmed: " + action.action(),
            actionContext(reportId, actionIndex, action, user, request.note())
        ));
        metrics.evidenceRequest("action_read_only", "created", 1);
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
        auditAction(user, actionRequest, "accepted", servletRequest);
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
            guardrails(action),
            actionRequest,
            null
        );
    }

    @Transactional
    public ActionApprovalResponse approve(
        String actionRequestId,
        ActionDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        ActionRequest decided = decideActionRequest(
            actionRequestId,
            request,
            user,
            ActionRequestStatus.approved_manual,
            servletRequest
        );
        return new ActionApprovalResponse(decided, null);
    }

    @Transactional
    public ActionApprovalResponse reject(
        String actionRequestId,
        ActionDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        ActionRequest decided = decideActionRequest(
            actionRequestId,
            request,
            user,
            ActionRequestStatus.rejected,
            servletRequest
        );
        return new ActionApprovalResponse(decided, null);
    }

    @Transactional
    public ActionApprovalResponse completeManual(
        String actionRequestId,
        ActionManualCompletionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "manual completion confirmation is required");
        }
        ActionRequest existing = actions.findRequest(actionRequestId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "action request not found"));
        if (existing.status() != ActionRequestStatus.approved_manual) {
            throw new ResponseStatusException(CONFLICT, "action request is not approved for manual handling");
        }
        ActionRequest completed = actions.completeManual(actionRequestId)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "manual action was already completed"));
        audit.user(
            user,
            "rca.action_manual_completed",
            "action_request",
            actionRequestId,
            "completed",
            Map.of(
                "report_id", completed.reportId(),
                "action_key", completed.actionKey(),
                "note", request.note()
            ),
            servletRequest
        );
        return new ActionApprovalResponse(completed, null);
    }

    private ActionExecutionResponse recordNonExecutableAction(
        String reportId,
        int actionIndex,
        ActionExecutionRequest request,
        UserAccount user,
        RecommendedAction action,
        HttpServletRequest servletRequest
    ) {
        ActionRequestStatus status = (
            action.policy() == PolicyLevel.APPROVAL_REQUIRED
                || action.policy() == PolicyLevel.GITOPS_PR_ONLY
        ) && !isLlmAction(action)
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
        auditAction(user, actionRequest, status.name(), servletRequest);
        return new ActionExecutionResponse(
            reportId,
            actionIndex,
            action.actionKey(),
            action.policy(),
            status.name(),
            status == ActionRequestStatus.pending_approval
                ? manualWorkflowMessage(action)
                : isLlmAction(action)
                    ? "LLM-origin actions are diagnostic suggestions and cannot trigger automation."
                    : "Policy Engine does not allow this action to execute automatically.",
            false,
            action.requiresApproval() || action.reviewRequired(),
            null,
            guardrails(action),
            actions.findRequest(actionRequest.actionRequestId()).orElse(actionRequest),
            null
        );
    }

    private ActionRequest decideActionRequest(
        String actionRequestId,
        ActionDecisionRequest request,
        UserAccount user,
        ActionRequestStatus decision,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "action decision confirmation is required");
        }
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
        auditAction(user, decided, decision.name(), servletRequest);
        return decided;
    }

    private ActionExecutionResponse blocked(
        String reportId,
        int actionIndex,
        RecommendedAction action,
        UserAccount user,
        String note,
        String message,
        String guardrail,
        HttpServletRequest servletRequest
    ) {
        List<String> guardrails = new java.util.ArrayList<>(guardrails(action));
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
        auditAction(user, actionRequest, "blocked", servletRequest);
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

    private String manualWorkflowMessage(RecommendedAction action) {
        if (action.policy() == PolicyLevel.GITOPS_PR_ONLY) {
            return "Review request recorded. After approval, create a GitOps PR from the YAML preview, "
                + "complete the external review, and mark this request as manually completed.";
        }
        return "Approval request recorded. Approval only authorizes a human-operated runbook; "
            + "the platform and node agent will not execute the command. Mark completion after manual handling.";
    }

    private void auditAction(
        UserAccount user,
        ActionRequest request,
        String outcome,
        HttpServletRequest servletRequest
    ) {
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
                "source", source(request.source())
            ),
            servletRequest
        );
    }

    private boolean isLlmAction(RecommendedAction action) {
        return "llm".equals(source(action.source()));
    }

    private String source(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    private List<String> guardrails(RecommendedAction action) {
        return action.guardrails() == null ? List.of() : action.guardrails();
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

    private Map<String, Object> actionContext(
        String reportId,
        int actionIndex,
        RecommendedAction action,
        UserAccount user,
        String note
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("report_id", reportId);
        context.put("action_index", actionIndex);
        context.put("action_key", action.actionKey());
        context.put("action_source", action.source());
        context.put("policy", action.policy().name());
        context.put("requested_by", user.email());
        context.put("note", note == null ? "" : note);
        return context;
    }

    private String targetNode(RcaReport report) {
        Object nodes = report.scope().get("nodes");
        if (nodes instanceof List<?> list && !list.isEmpty() && list.getFirst() != null) {
            String value = String.valueOf(list.getFirst()).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        Object nodeName = report.scope().get("node_name");
        if (nodeName != null) {
            String value = String.valueOf(nodeName).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private RcaReport requireReport(String reportId) {
        return reports.findReport(reportId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "RCA report not found"));
    }
}
