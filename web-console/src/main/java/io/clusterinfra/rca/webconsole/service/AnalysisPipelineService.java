package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.IncidentCorrelationService.CorrelationDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalysisPipelineService {
    private final EvidenceRepository evidence;
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final RuleBasedRcaAnalyzer analyzer;
    private final IncidentCorrelationService correlation;
    private final AuditService audit;
    private final IncidentNotificationService notifications;
    private final RcaMetrics metrics;
    private final TopologyService topology;

    public AnalysisPipelineService(
        EvidenceRepository evidence,
        IncidentRepository incidents,
        ReportRepository reports,
        RuleBasedRcaAnalyzer analyzer,
        IncidentCorrelationService correlation,
        AuditService audit,
        IncidentNotificationService notifications,
        RcaMetrics metrics,
        TopologyService topology
    ) {
        this.evidence = evidence;
        this.incidents = incidents;
        this.reports = reports;
        this.analyzer = analyzer;
        this.correlation = correlation;
        this.audit = audit;
        this.notifications = notifications;
        this.metrics = metrics;
        this.topology = topology;
    }

    public RcaJob processAnalysisTask(AnalysisTask task) {
        EvidenceBundle evidence = this.evidence.find(task.evidenceId()).orElse(null);
        if (evidence == null) {
            throw new IllegalStateException("analysis evidence not found: " + task.evidenceId());
        }
        topology.observe(evidence);
        if (task.skipIfHealthy() && !analyzer.hasActionableSignals(evidence.clusterId(), evidence.collectors())) {
            return null;
        }
        return createCompletedJob(evidence);
    }

    private RcaJob createCompletedJob(EvidenceBundle evidence) {
        Instant startedAt = Instant.now();
        try {
            String reportId = id("report");
            RcaReport report = analyzer.analyze(reportId, evidence);
            RcaJob job = new RcaJob(
                id("job"),
                evidence.clusterId(),
                evidence.alertName(),
                evidence.nodeName(),
                RcaJobStatus.completed,
                reportId,
                evidence.evidenceId(),
                Instant.now()
            );
            CorrelationDecision decision = correlation.decide(report, evidence);
            RcaJob saved = incidents.saveCorrelated(
                report,
                job,
                decision.dedupKey(),
                decision.matchedIncidentId(),
                decision.promoteRootCause(),
                decision.recurrenceOfIncidentId(),
                decision.recurrenceSequence(),
                evidence
            );
            boolean duplicate = !saved.reportId().equals(reportId);
            String savedIncidentId = reports.findReport(saved.reportId())
                .map(RcaReport::incidentId)
                .orElse(null);
            boolean promoted = decision.matched()
                && decision.promoteRootCause()
                && !duplicate
                && decision.matchedIncidentId().equals(savedIncidentId);
            String correlationResult = decision.recurrence()
                ? "recurred"
                : promoted
                ? "root_cause_promoted"
                : duplicate ? "correlated" : "created";
            metrics.incident(correlationResult);
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
            if (decision.recurrence()) {
                auditDetails.put("recurrence_of_incident_id", decision.recurrenceOfIncidentId());
                auditDetails.put("recurrence_sequence", decision.recurrenceSequence());
            }
            audit.system(
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
            );
            if (!duplicate) {
                reports.findReport(saved.reportId()).ifPresent(savedReport ->
                    notifications.notifyIncident(savedReport, evidence)
                );
            }
            metrics.reportGenerated(
                correlationResult,
                Duration.between(startedAt, Instant.now())
            );
            return saved;
        } catch (RuntimeException exception) {
            metrics.reportGenerated("failed", Duration.between(startedAt, Instant.now()));
            throw exception;
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
