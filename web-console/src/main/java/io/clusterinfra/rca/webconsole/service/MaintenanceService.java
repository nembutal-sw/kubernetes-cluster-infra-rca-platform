package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceService {
    private final AuditRepository audit;
    private final UserSessionRepository sessions;
    private final RcaConsoleProperties properties;

    public MaintenanceService(
        AuditRepository audit,
        UserSessionRepository sessions,
        RcaConsoleProperties properties
    ) {
        this.audit = audit;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Scheduled(cron = "${rca.maintenance.cron:0 17 3 * * *}")
    public void removeExpiredOperationalData() {
        Instant now = Instant.now();
        sessions.deleteExpiredBefore(now);
        int retentionDays = Math.max(1, properties.getAudit().getRetentionDays());
        audit.deleteBefore(now.minus(retentionDays, ChronoUnit.DAYS));
    }
}
