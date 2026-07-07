package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionApprovalResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionManualCompletionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.ActionWorkflowService;
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
public class ActionWorkflowController {
    private final ActionWorkflowService actionWorkflow;
    private final AccessService access;

    public ActionWorkflowController(ActionWorkflowService actionWorkflow, AccessService access) {
        this.actionWorkflow = actionWorkflow;
        this.access = access;
    }

    @GetMapping("/api/rca/action-requests")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public List<ActionRequest> actionRequests(
        @RequestParam(name = "report_id", required = false) String reportId
    ) {
        return actionWorkflow.listRequests(reportId);
    }

    @GetMapping("/api/rca/action-executions")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    public List<ActionExecution> actionExecutions(
        @RequestParam(name = "report_id", required = false) String reportId
    ) {
        return actionWorkflow.listExecutions(reportId);
    }

    @PostMapping("/api/rca/reports/{reportId}/actions/{actionIndex}/execute")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ActionExecutionResponse execute(
        @PathVariable String reportId,
        @PathVariable int actionIndex,
        @Valid @RequestBody ActionExecutionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return actionWorkflow.execute(
            reportId,
            actionIndex,
            request,
            access.currentUser(authentication),
            servletRequest
        );
    }

    @PostMapping("/api/rca/action-requests/{actionRequestId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public ActionApprovalResponse approveActionRequest(
        @PathVariable String actionRequestId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return actionWorkflow.approve(actionRequestId, request, user, servletRequest);
    }

    @PostMapping("/api/rca/action-requests/{actionRequestId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public ActionApprovalResponse rejectActionRequest(
        @PathVariable String actionRequestId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return actionWorkflow.reject(actionRequestId, request, user, servletRequest);
    }

    @PostMapping("/api/rca/action-requests/{actionRequestId}/complete-manual")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ActionApprovalResponse completeManualActionRequest(
        @PathVariable String actionRequestId,
        @Valid @RequestBody ActionManualCompletionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        return actionWorkflow.completeManual(actionRequestId, request, user, servletRequest);
    }
}
