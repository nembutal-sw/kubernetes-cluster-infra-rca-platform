package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IncidentLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(IncidentLifecycleService.class);

    private final IncidentRepository incidents;
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final RcaMetrics metrics;

    public IncidentLifecycleService(
        IncidentRepository incidents,
        RcaConsoleProperties properties,
        AuditService audit,
        RcaMetrics metrics
    ) {
        this.incidents = incidents;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Scheduled(
        fixedDelayString = "${rca.incident.lifecycle-scan-interval-ms:60000}",
        initialDelayString = "${rca.incident.lifecycle-initial-delay-ms:30000}"
    )
    public void resolveInactiveIncidents() {
        if (!properties.getIncident().isAutoResolveEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant inactiveBefore = now.minusSeconds(
            Math.max(1, properties.getIncident().getInactivityMinutes()) * 60L
        );
        try {
            List<Incident> resolved = incidents.resolveInactive(
                inactiveBefore,
                now,
                properties.getIncident().getLifecycleBatchSize()
            );
            for (Incident incident : resolved) {
                recordResolution(
                    incident,
                    "incident.auto_resolved",
                    "inactivity_timeout",
                    Map.of(
                        "inactive_before", inactiveBefore.toString(),
                        "last_seen_at", incident.lastSeenAt().toString()
                    )
                );
            }
            if (!resolved.isEmpty()) {
                log.info("Automatically resolved {} inactive incidents", resolved.size());
            }
        } catch (RuntimeException exception) {
            metrics.incidentLifecycle("auto_resolve_failed", 1);
            log.error("Automatic incident resolution failed", exception);
        }
    }

    public List<Incident> resolveSignal(
        String clusterId,
        String nodeName,
        String alertName,
        Instant resolvedAt,
        String source
    ) {
        Instant effectiveResolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        List<Incident> resolved = incidents.resolveBySignal(
            clusterId,
            nodeName,
            alertName,
            effectiveResolvedAt,
            source,
            "The upstream alert source reported the signal as resolved."
        );
        for (Incident incident : resolved) {
            recordResolution(
                incident,
                "incident.signal_resolved",
                "upstream_resolved",
                Map.of(
                    "cluster_id", clusterId,
                    "node_name", nodeName,
                    "alert_name", alertName,
                    "source", source
                )
            );
        }
        return resolved;
    }

    private void recordResolution(
        Incident incident,
        String eventType,
        String outcome,
        Map<String, Object> details
    ) {
        metrics.incidentLifecycle(outcome, 1);
        try {
            audit.system(
                "incident-lifecycle",
                eventType,
                "incident",
                incident.incidentId(),
                outcome,
                details
            );
        } catch (RuntimeException exception) {
            log.warn("Could not persist lifecycle audit event for {}", incident.incidentId(), exception);
        }
    }
}
