package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {
    private final JdbcRcaStore store;

    public EvidenceRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public EvidenceRequest createRequest(EvidenceRequestCreateRequest request) {
        return store.createEvidenceRequest(request);
    }

    public List<EvidenceRequest> listRequests(
        String clusterId,
        String nodeName,
        EvidenceRequestStatus status,
        Integer limit
    ) {
        return store.listEvidenceRequests(clusterId, nodeName, status, limit);
    }

    public Optional<EvidenceRequest> findRequest(String requestId) {
        return store.getEvidenceRequest(requestId);
    }

    public boolean hasPendingRequest(String clusterId, String nodeName) {
        return store.hasPendingEvidenceRequest(clusterId, nodeName);
    }

    public Optional<EvidenceRequest> submitResponse(AgentEvidenceSubmitRequest request, int maxAttempts) {
        return store.submitEvidenceResponse(request, maxAttempts);
    }

    public AnalysisTask saveAndEnqueue(
        EvidenceBundle evidence,
        String source,
        boolean skipIfHealthy,
        int maxAttempts
    ) {
        return store.saveEvidenceAndEnqueue(evidence, source, skipIfHealthy, maxAttempts);
    }

    public EvidenceBundle save(EvidenceBundle evidence) {
        return store.saveEvidence(evidence);
    }

    public Optional<EvidenceBundle> find(String evidenceId) {
        return store.getEvidence(evidenceId);
    }

    public List<EvidenceBundle> listForNodeWindow(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return store.listEvidenceForNodeWindow(clusterId, nodeName, from, to);
    }

    public RealtimeEvent saveRealtimeEvent(RealtimeEvent event) {
        return store.saveRealtimeEvent(event);
    }

    public List<RealtimeEvent> listRealtimeEvents(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return store.listRealtimeEventsForNodeWindow(clusterId, nodeName, from, to);
    }
}
