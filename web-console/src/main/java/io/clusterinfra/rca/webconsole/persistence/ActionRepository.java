package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
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

    public Optional<ActionRequest> completeManual(String actionRequestId) {
        return store.completeManualActionRequest(actionRequestId);
    }

    public Optional<ActionExecution> findExecution(String executionId) {
        return store.getActionExecution(executionId);
    }

    public List<ActionExecution> listExecutions(String reportId) {
        return store.listActionExecutions(reportId);
    }
}
