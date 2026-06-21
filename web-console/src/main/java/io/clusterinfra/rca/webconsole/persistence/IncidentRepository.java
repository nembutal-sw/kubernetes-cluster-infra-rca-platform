package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class IncidentRepository {
    private final JdbcRcaStore store;

    public IncidentRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public RcaJob saveCorrelated(
        RcaReport report,
        RcaJob job,
        String dedupKey,
        String matchedIncidentId,
        boolean promoteRootCause,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        EvidenceBundle evidence
    ) {
        return store.saveCorrelatedReportAndJob(
            report,
            job,
            dedupKey,
            matchedIncidentId,
            promoteRootCause,
            recurrenceOfIncidentId,
            recurrenceSequence,
            evidence
        );
    }

    public List<Incident> list(String clusterId) {
        return store.listIncidents(clusterId);
    }

    public Optional<Incident> find(String incidentId) {
        return store.getIncident(incidentId);
    }

    public Optional<Incident> updateStatus(String incidentId, IncidentStatus status) {
        return store.updateIncidentStatus(incidentId, status);
    }

    public Optional<Incident> updateStatus(
        String incidentId,
        IncidentStatus status,
        String source,
        String note,
        Instant changedAt
    ) {
        return store.updateIncidentStatus(incidentId, status, source, note, changedAt);
    }

    public Optional<Incident> findByDedupKey(String dedupKey) {
        return store.findIncidentByDedupKey(dedupKey);
    }

    public List<Incident> findRecentOpen(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to,
        int limit
    ) {
        return store.listRecentOpenIncidents(clusterId, nodeName, from, to, limit);
    }

    public List<Incident> findRecentOpenCluster(
        String clusterId,
        Instant from,
        Instant to,
        int limit
    ) {
        return store.listRecentOpenClusterIncidents(clusterId, from, to, limit);
    }

    public List<Incident> findRecentResolved(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to,
        int limit
    ) {
        return store.listRecentResolvedIncidents(clusterId, nodeName, from, to, limit);
    }

    public List<Incident> resolveInactive(Instant inactiveBefore, Instant resolvedAt, int limit) {
        return store.resolveInactiveIncidents(inactiveBefore, resolvedAt, limit);
    }

    public List<Incident> resolveBySignal(
        String clusterId,
        String nodeName,
        String alertName,
        Instant resolvedAt,
        String source,
        String note
    ) {
        return store.resolveOpenIncidentsBySignal(
            clusterId,
            nodeName,
            alertName,
            resolvedAt,
            source,
            note
        );
    }

    public Optional<RcaJob> latestJob(String incidentId) {
        return store.getLatestJobForIncident(incidentId);
    }
}
