package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceService {
    private final RcaRepository repository;
    private final RcaConsoleProperties properties;

    public MaintenanceService(RcaRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${rca.maintenance.cron:0 17 3 * * *}")
    public void removeExpiredOperationalData() {
        Instant now = Instant.now();
        repository.deleteExpiredSessions(now);
        int retentionDays = Math.max(1, properties.getAudit().getRetentionDays());
        repository.deleteAuditEventsBefore(now.minus(retentionDays, ChronoUnit.DAYS));
    }
}
