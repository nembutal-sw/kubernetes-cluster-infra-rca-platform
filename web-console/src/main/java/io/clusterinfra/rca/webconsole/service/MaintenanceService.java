package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository.CleanupResult;
import io.clusterinfra.rca.webconsole.persistence.RetentionRepository.RetentionCutoffs;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final RetentionRepository retention;
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final RcaMetrics metrics;

    public MaintenanceService(
        RetentionRepository retention,
        RcaConsoleProperties properties,
        AuditService audit,
        RcaMetrics metrics
    ) {
        this.retention = retention;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${rca.maintenance.cron:0 17 3 * * *}")
    public void removeExpiredOperationalData() {
        if (!properties.getMaintenance().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant startedAt = now;
        try {
            CleanupResult result = retention.cleanup(
                cutoffs(now),
                properties.getMaintenance().getBatchSize()
            );
            result.asMap().forEach(metrics::retentionCleanup);
            metrics.maintenanceRun("completed", Duration.between(startedAt, Instant.now()));
            auditSafely(
                "retention-cleanup",
                "maintenance.retention_completed",
                "completed",
                Map.of(
                    "deleted", result.asMap(),
                    "total_deleted", result.totalDeleted()
                )
            );
            log.info("Retention cleanup completed; deleted {} records", result.totalDeleted());
        } catch (RuntimeException exception) {
            metrics.maintenanceRun("failed", Duration.between(startedAt, Instant.now()));
            auditSafely(
                "retention-cleanup",
                "maintenance.retention_failed",
                "failed",
                Map.of("error_type", exception.getClass().getSimpleName())
            );
            log.error("Retention cleanup failed", exception);
        }
    }

    private RetentionCutoffs cutoffs(Instant now) {
        RcaConsoleProperties.Maintenance maintenance = properties.getMaintenance();
        return new RetentionCutoffs(
            now,
            subtractDays(now, properties.getAudit().getRetentionDays()),
            subtractDays(now, maintenance.getEvidenceRetentionDays()),
            subtractDays(now, maintenance.getEvidenceRequestRetentionDays()),
            subtractDays(now, maintenance.getAnalysisTaskRetentionDays()),
            subtractDays(now, maintenance.getRealtimeEventRetentionDays()),
            subtractDays(now, maintenance.getTopologyObservationRetentionDays()),
            subtractDays(now, maintenance.getReportRetentionDays())
        );
    }

    private Instant subtractDays(Instant now, int days) {
        return now.minus(Math.max(1, days), ChronoUnit.DAYS);
    }

    private void auditSafely(
        String actorId,
        String eventType,
        String outcome,
        Map<String, Object> details
    ) {
        try {
            audit.system(
                actorId,
                eventType,
                "platform",
                "retention",
                outcome,
                details
            );
        } catch (RuntimeException exception) {
            log.warn("Could not persist retention audit event {}", eventType, exception);
        }
    }
}
