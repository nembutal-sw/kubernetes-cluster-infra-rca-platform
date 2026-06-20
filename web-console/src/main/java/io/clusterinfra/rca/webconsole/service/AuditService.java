package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public AuditEvent user(
        UserAccount user,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return record(
            "user",
            user == null ? "unknown" : user.email(),
            eventType,
            resourceType,
            resourceId,
            outcome,
            details
        );
    }

    public AuditEvent system(
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return record("system", actorId, eventType, resourceType, resourceId, outcome, details);
    }

    public AuditEvent record(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return repository.save(
            actorType,
            actorId == null || actorId.isBlank() ? "unknown" : actorId,
            eventType,
            resourceType,
            resourceId,
            outcome,
            details == null ? Map.of() : details
        );
    }
}
