package io.clusterinfra.rca.webconsole.persistence;

import java.time.Instant;

public record AuditSearchCriteria(
    String actorType,
    String actorId,
    String eventType,
    String resourceType,
    String resourceId,
    String outcome,
    String clientIp,
    String query,
    Instant from,
    Instant to,
    int limit
) {
    public int boundedLimit(int defaultLimit, int maxLimit) {
        int requested = limit <= 0 ? defaultLimit : limit;
        return Math.max(1, Math.min(requested, maxLimit));
    }
}
