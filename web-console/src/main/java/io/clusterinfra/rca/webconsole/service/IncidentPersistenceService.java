package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentPersistenceService {
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final IncidentNotificationService notifications;

    public IncidentPersistenceService(
        IncidentRepository incidents,
        ReportRepository reports,
        IncidentNotificationService notifications
    ) {
        this.incidents = incidents;
        this.reports = reports;
        this.notifications = notifications;
    }

    @Transactional
    public PersistedIncident saveCorrelated(
        RcaReport report,
        RcaJob job,
        String dedupKey,
        String matchedIncidentId,
        boolean promoteRootCause,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        EvidenceBundle evidence
    ) {
        RcaJob savedJob = incidents.saveCorrelated(
            report,
            job,
            dedupKey,
            matchedIncidentId,
            promoteRootCause,
            recurrenceOfIncidentId,
            recurrenceSequence,
            evidence
        );
        boolean duplicate = !savedJob.reportId().equals(report.reportId());
        RcaReport savedReport = reports.findReport(savedJob.reportId())
            .orElseThrow(() -> new IllegalStateException(
                "correlated report was not persisted: " + savedJob.reportId()
            ));
        List<NotificationOutboxEvent> notificationEvents = duplicate
            ? List.of()
            : notifications.enqueueIncident(savedReport, evidence);
        return new PersistedIncident(savedJob, savedReport, duplicate, notificationEvents);
    }

    public record PersistedIncident(
        RcaJob job,
        RcaReport report,
        boolean duplicate,
        List<NotificationOutboxEvent> notificationEvents
    ) {
    }
}
