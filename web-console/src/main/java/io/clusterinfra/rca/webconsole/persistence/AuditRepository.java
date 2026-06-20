package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcRcaStore store;

    public AuditRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public AuditEvent save(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return store.saveAuditEvent(
            actorType, actorId, eventType, resourceType, resourceId, outcome, details
        );
    }

    public List<AuditEvent> list(Integer limit) {
        return store.listAuditEvents(limit);
    }

    public int deleteBefore(Instant cutoff) {
        return store.deleteAuditEventsBefore(cutoff);
    }
}
