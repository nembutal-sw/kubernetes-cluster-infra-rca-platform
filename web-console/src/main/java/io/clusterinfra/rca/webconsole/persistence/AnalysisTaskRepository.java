package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisTaskRepository {
    private final JdbcRcaStore store;

    public AnalysisTaskRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public AnalysisTask enqueue(EvidenceBundle evidence, String source, boolean skipIfHealthy, int maxAttempts) {
        return store.enqueueAnalysisTask(evidence, source, skipIfHealthy, maxAttempts);
    }

    public Optional<AnalysisTask> find(String taskId) {
        return store.getAnalysisTask(taskId);
    }

    public Optional<AnalysisTask> findByEvidence(String evidenceId) {
        return store.getAnalysisTaskByEvidenceId(evidenceId);
    }

    public List<AnalysisTask> list(AnalysisTaskStatus status, Integer limit) {
        return store.listAnalysisTasks(status, limit);
    }

    public List<AnalysisTask> claim(
        String leaseOwner,
        int limit,
        Instant now,
        Instant leaseExpiresAt
    ) {
        return store.claimAnalysisTasks(leaseOwner, limit, now, leaseExpiresAt);
    }

    public boolean complete(
        String taskId,
        String leaseOwner,
        AnalysisTaskStatus status,
        String reportId,
        String jobId,
        Instant completedAt
    ) {
        return store.completeAnalysisTask(taskId, leaseOwner, status, reportId, jobId, completedAt);
    }

    public boolean fail(AnalysisTask task, String leaseOwner, String error, Instant failedAt) {
        return store.failAnalysisTask(task, leaseOwner, error, failedAt);
    }

    public Optional<AnalysisTask> retry(String taskId) {
        return store.retryAnalysisTask(taskId);
    }
}
