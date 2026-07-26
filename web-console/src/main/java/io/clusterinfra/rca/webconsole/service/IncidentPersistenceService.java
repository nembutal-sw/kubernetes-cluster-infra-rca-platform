package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationOutboxEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentPersistenceService {
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final IncidentNotificationService notifications;
    private final AnalysisTaskRepository analysisTasks;

    public IncidentPersistenceService(
        IncidentRepository incidents,
        ReportRepository reports,
        IncidentNotificationService notifications,
        AnalysisTaskRepository analysisTasks
    ) {
        this.incidents = incidents;
        this.reports = reports;
        this.notifications = notifications;
        this.analysisTasks = analysisTasks;
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
        return saveCorrelatedInternal(
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

    @Transactional
    public PersistedIncident saveCorrelatedAndCompleteTask(
        RcaReport report,
        RcaJob job,
        String dedupKey,
        String matchedIncidentId,
        boolean promoteRootCause,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        EvidenceBundle evidence,
        AnalysisTask task,
        String leaseOwner
    ) {
        PersistedIncident persisted = saveCorrelatedInternal(
            report,
            job,
            dedupKey,
            matchedIncidentId,
            promoteRootCause,
            recurrenceOfIncidentId,
            recurrenceSequence,
            evidence
        );
        Instant completedAt = Instant.now();
        boolean completed = analysisTasks.complete(
            task,
            leaseOwner,
            AnalysisTaskStatus.completed,
            persisted.job().reportId(),
            persisted.job().jobId(),
            completedAt
        );
        if (!completed) {
            throw new AnalysisTaskLeaseLostException(task.taskId());
        }
        return persisted;
    }

    private PersistedIncident saveCorrelatedInternal(
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

    public static final class AnalysisTaskLeaseLostException extends IllegalStateException {
        public AnalysisTaskLeaseLostException(String taskId) {
            super("analysis task lease was lost before persistence commit: " + taskId);
        }
    }

    public record PersistedIncident(
        RcaJob job,
        RcaReport report,
        boolean duplicate,
        List<NotificationOutboxEvent> notificationEvents
    ) {
    }
}
