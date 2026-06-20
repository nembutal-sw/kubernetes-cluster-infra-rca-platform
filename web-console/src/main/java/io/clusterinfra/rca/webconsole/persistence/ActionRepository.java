package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ActionRepository {
    private final JdbcRcaStore store;

    public ActionRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public ActionRequest createRequest(
        String reportId,
        int actionIndex,
        String actionKey,
        PolicyLevel policy,
        String source,
        ActionRequestStatus status,
        String requestedBy,
        String requestNote,
        String evidenceRequestId
    ) {
        return store.createActionRequest(
            reportId, actionIndex, actionKey, policy, source, status,
            requestedBy, requestNote, evidenceRequestId
        );
    }

    public Optional<ActionRequest> decide(
        String actionRequestId,
        ActionRequestStatus status,
        String reviewedBy,
        String decisionNote
    ) {
        return store.decideActionRequest(actionRequestId, status, reviewedBy, decisionNote);
    }

    public Optional<ActionRequest> findRequest(String actionRequestId) {
        return store.getActionRequest(actionRequestId);
    }

    public List<ActionRequest> listRequests(String reportId) {
        return store.listActionRequests(reportId);
    }

    public ActionExecution createExecution(
        ActionRequest request,
        RcaReport report,
        String nodeName,
        ActionPlan plan
    ) {
        return store.createActionExecution(request, report, nodeName, plan);
    }

    public Optional<ActionExecution> findExecution(String executionId) {
        return store.getActionExecution(executionId);
    }

    public Optional<ActionExecution> findExecutionByRequest(String actionRequestId) {
        return store.getActionExecutionByRequest(actionRequestId);
    }

    public List<ActionExecution> listExecutions(String reportId) {
        return store.listActionExecutions(reportId);
    }

    public Optional<ActionExecution> approveExecution(String actionRequestId, String approvedBy) {
        return store.approveActionExecution(actionRequestId, approvedBy);
    }

    public Optional<ActionExecution> rejectExecution(String actionRequestId) {
        return store.rejectActionExecution(actionRequestId);
    }

    public List<ActionExecution> claimExecutions(
        String clusterId,
        String nodeName,
        String leaseOwner,
        int limit,
        Instant now,
        Instant leaseExpiresAt
    ) {
        return store.claimActionExecutions(
            clusterId, nodeName, leaseOwner, limit, now, leaseExpiresAt
        );
    }

    public Optional<ActionExecution> completeExecution(
        String executionId,
        String leaseOwner,
        ActionExecutionStatus status,
        Integer exitCode,
        String stdout,
        String stderr,
        String errorMessage
    ) {
        return store.completeActionExecution(
            executionId, leaseOwner, status, exitCode, stdout, stderr, errorMessage
        );
    }
}
