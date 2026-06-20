package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.util.List;
import java.util.Optional;
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
        EvidenceBundle evidence
    ) {
        return store.saveCorrelatedReportAndJob(report, job, dedupKey, evidence);
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

    public Optional<Incident> findByDedupKey(String dedupKey) {
        return store.findIncidentByDedupKey(dedupKey);
    }

    public Optional<RcaJob> latestJob(String incidentId) {
        return store.getLatestJobForIncident(incidentId);
    }
}
