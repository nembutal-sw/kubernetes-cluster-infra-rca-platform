package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.service.IncidentCorrelationService.CorrelationDecision;
import io.clusterinfra.rca.webconsole.service.IncidentPersistenceService.PersistedIncident;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AnalysisPipelineService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisPipelineService.class);

    private final EvidenceRepository evidence;
    private final IncidentPersistenceService persistence;
    private final RuleBasedRcaAnalyzer analyzer;
    private final IncidentCorrelationService correlation;
    private final AuditService audit;
    private final RcaMetrics metrics;
    private final TopologyService topology;

    public AnalysisPipelineService(
        EvidenceRepository evidence,
        IncidentPersistenceService persistence,
        RuleBasedRcaAnalyzer analyzer,
        IncidentCorrelationService correlation,
        AuditService audit,
        RcaMetrics metrics,
        TopologyService topology
    ) {
        this.evidence = evidence;
        this.persistence = persistence;
        this.analyzer = analyzer;
        this.correlation = correlation;
        this.audit = audit;
        this.metrics = metrics;
        this.topology = topology;
    }

    public RcaJob processAnalysisTask(AnalysisTask task, String leaseOwner) {
        EvidenceBundle evidence = this.evidence.find(task.evidenceId()).orElse(null);
        if (evidence == null) {
            throw new IllegalStateException("analysis evidence not found: " + task.evidenceId());
        }
        topology.observe(evidence);
        if (task.skipIfHealthy() && !analyzer.hasActionableSignals(evidence.clusterId(), evidence.collectors())) {
            return null;
        }
        return createCompletedJob(task, evidence, leaseOwner);
    }

    private RcaJob createCompletedJob(
        AnalysisTask task,
        EvidenceBundle evidence,
        String leaseOwner
    ) {
        Instant startedAt = Instant.now();
        RcaReport report;
        RcaJob job;
        CorrelationDecision decision;
        PersistedIncident persisted;
        try {
            String reportId = id("report");
            report = analyzer.analyze(reportId, evidence);
            job = new RcaJob(
                id("job"),
                evidence.clusterId(),
                evidence.alertName(),
                evidence.nodeName(),
                RcaJobStatus.completed,
                reportId,
                evidence.evidenceId(),
                Instant.now()
            );
            decision = correlation.decide(report, evidence);
            persisted = persistence.saveCorrelatedAndCompleteTask(
                report,
                job,
                decision.dedupKey(),
                decision.matchedIncidentId(),
                decision.promoteRootCause(),
                decision.recurrenceOfIncidentId(),
                decision.recurrenceSequence(),
                evidence,
                task,
                leaseOwner
            );
        } catch (RuntimeException exception) {
            safeMetric(
                "report_generation_failed",
                () -> metrics.reportGenerated("failed", Duration.between(startedAt, Instant.now()))
            );
            throw exception;
        }
        safePostCommit(
            "pipeline_observability",
            () -> recordPostCommitObservability(evidence, decision, persisted, startedAt)
        );
        return persisted.job();
    }

    private void recordPostCommitObservability(
        EvidenceBundle evidence,
        CorrelationDecision decision,
        PersistedIncident persisted,
        Instant startedAt
    ) {
        RcaJob saved = persisted.job();
        boolean duplicate = persisted.duplicate();
        String savedIncidentId = persisted.report().incidentId();
        boolean promoted = decision.matched()
            && decision.promoteRootCause()
            && !duplicate
            && decision.matchedIncidentId().equals(savedIncidentId);
        String correlationResult = decision.recurrence()
            ? "recurred"
            : promoted
            ? "root_cause_promoted"
            : duplicate ? "correlated" : "created";
        safeMetric("incident_" + correlationResult, () -> metrics.incident(correlationResult));

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("cluster_id", evidence.clusterId());
        auditDetails.put("node_name", evidence.nodeName());
        auditDetails.put("alert_name", evidence.alertName());
        auditDetails.put("evidence_id", evidence.evidenceId());
        auditDetails.put("report_id", saved.reportId());
        auditDetails.put("correlation_rule", decision.ruleId());
        auditDetails.put("correlation_relationship", decision.relationship());
        auditDetails.put("correlation_score", decision.score());
        auditDetails.put("signal_family", decision.primaryFamily());
        auditDetails.put("cross_node", decision.crossNode());
        auditDetails.put("topology_rule", decision.topologyRule());
        auditDetails.put("shared_services", decision.sharedServices());
        auditDetails.put("notification_events_queued", persisted.notificationEvents().size());
        if (decision.recurrence()) {
            auditDetails.put("recurrence_of_incident_id", decision.recurrenceOfIncidentId());
            auditDetails.put("recurrence_sequence", decision.recurrenceSequence());
        }
        safePostCommit(
            "incident_audit",
            () -> audit.system(
                "rca-pipeline",
                decision.recurrence()
                    ? "incident.recurred"
                    : promoted
                    ? "incident.root_cause_promoted"
                    : duplicate ? "incident.correlated" : "incident.created",
                "incident",
                savedIncidentId,
                decision.recurrence()
                    ? "new_incident_linked_to_resolved_incident"
                    : promoted
                    ? "canonical_report_replaced"
                    : duplicate ? "suppressed_duplicate_report" : "report_created",
                auditDetails
            )
        );
        persisted.notificationEvents().forEach(event ->
            safeMetric(
                "notification_queued",
                () -> metrics.notification("queued", event.severity())
            )
        );
        safeMetric(
            "report_generation_" + correlationResult,
            () -> metrics.reportGenerated(
                correlationResult,
                Duration.between(startedAt, Instant.now())
            )
        );
    }

    private void safePostCommit(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("RCA post-commit operation failed: {}", operation, exception);
            safeMetric(
                "post_commit_" + operation,
                () -> metrics.postCommitFailure(operation)
            );
        }
    }

    private void safeMetric(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("RCA metric recording failed: {}", operation, exception);
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
