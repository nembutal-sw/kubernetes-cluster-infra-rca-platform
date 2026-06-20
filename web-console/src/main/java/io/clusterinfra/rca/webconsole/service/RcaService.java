package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerAlert;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class RcaService {
    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final RuleBasedRcaAnalyzer analyzer;
    private final RcaConsoleProperties properties;
    private final TokenService tokens;
    private final AuditService audit;
    private final IncidentNotificationService notifications;
    private final RcaMetrics metrics;

    public RcaService(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        IncidentRepository incidents,
        ReportRepository reports,
        RuleBasedRcaAnalyzer analyzer,
        RcaConsoleProperties properties,
        TokenService tokens,
        AuditService audit,
        IncidentNotificationService notifications,
        RcaMetrics metrics
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.evidence = evidence;
        this.incidents = incidents;
        this.reports = reports;
        this.analyzer = analyzer;
        this.properties = properties;
        this.tokens = tokens;
        this.audit = audit;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    public WebhookIngestResponse ingestAlertmanager(AlertmanagerPayload payload) {
        List<RcaJob> jobs = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        List<AnalysisTask> analysisTasks = new ArrayList<>();
        List<EvidenceRequest> requests = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AlertmanagerAlert alert : payload.alertsOrEmpty()) {
            String alertName = alert.labelsOrEmpty().getOrDefault("alertname", "UnknownAlert");
            if (!"firing".equalsIgnoreCase(alert.statusOrDefault())) {
                skipped.add(alertName + ": alert is not firing");
                continue;
            }
            String clusterId = firstNonBlank(
                alert.labelsOrEmpty().get("cluster_id"),
                value(payload.commonLabels(), "cluster_id"),
                value(payload.groupLabels(), "cluster_id")
            );
            if (clusterId == null) {
                skipped.add(alertName + ": cluster_id label is missing");
                continue;
            }
            if (clusters.find(clusterId).isEmpty()) {
                skipped.add(alertName + ": cluster " + clusterId + " is not registered");
                continue;
            }
            String nodeName = firstNonBlank(
                alert.labelsOrEmpty().get("node"),
                alert.labelsOrEmpty().get("nodename"),
                alert.labelsOrEmpty().get("instance")
            );
            if (nodeName != null && agents.find(clusterId, nodeName).isPresent()) {
                EvidenceRequest request = evidence.createRequest(new EvidenceRequestCreateRequest(
                    clusterId,
                    nodeName,
                    alertName,
                    collectorsFor(alertName),
                    timeRange(alert),
                    "Alertmanager firing alert",
                    Map.of(
                        "labels", alert.labelsOrEmpty(),
                        "annotations", alert.annotationsOrEmpty(),
                        "generator_url", alert.generatorUrl() == null ? "" : alert.generatorUrl()
                    )
                ));
                requests.add(request);
                metrics.evidenceRequest("alertmanager", "created", 1);
                continue;
            }

            String effectiveNode = nodeName == null ? "unknown" : nodeName;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("status", alert.statusOrDefault());
            metadata.put("labels", alert.labelsOrEmpty());
            metadata.put("annotations", alert.annotationsOrEmpty());
            metadata.put("generator_url", alert.generatorUrl());
            metadata.put("collector_status", "alert_only_no_registered_agent");
            AnalysisTask task = evidence.saveAndEnqueue(new EvidenceBundle(
                null,
                clusterId,
                effectiveNode,
                alertName,
                Instant.now(),
                Map.of("alertmanager", metadata)
            ), "alertmanager", false, properties.getPipeline().getMaxAttempts());
            analysisTasks.add(task);
        }
        String result = skipped.isEmpty()
            ? "accepted"
            : skipped.size() == payload.alertsOrEmpty().size() ? "rejected" : "partial";
        metrics.webhookIngest(result, 1, payload.alertsOrEmpty().size());
        return new WebhookIngestResponse(
            payload.alertsOrEmpty().size(),
            jobs,
            reports,
            analysisTasks,
            requests,
            skipped
        );
    }

    public RcaJob processAnalysisTask(AnalysisTask task) {
        EvidenceBundle evidence = this.evidence.find(task.evidenceId()).orElse(null);
        if (evidence == null) {
            throw new IllegalStateException("analysis evidence not found: " + task.evidenceId());
        }
        if (task.skipIfHealthy() && !analyzer.hasActionableSignals(evidence.collectors())) {
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
            String dedupKey = incidentDedupKey(report, evidence);
            RcaJob saved = incidents.saveCorrelated(report, job, dedupKey, evidence);
            boolean duplicate = !saved.reportId().equals(reportId);
            metrics.incident(duplicate ? "correlated" : "created");
            audit.system(
                "rca-pipeline",
                duplicate ? "incident.correlated" : "incident.created",
                "incident",
                reports.findReport(saved.reportId()).map(RcaReport::incidentId).orElse(null),
                duplicate ? "suppressed_duplicate_report" : "report_created",
                Map.of(
                    "cluster_id", evidence.clusterId(),
                    "node_name", evidence.nodeName(),
                    "alert_name", evidence.alertName(),
                    "evidence_id", evidence.evidenceId(),
                    "report_id", saved.reportId()
                )
            );
            if (!duplicate) {
                reports.findReport(saved.reportId()).ifPresent(savedReport ->
                    notifications.notifyIncident(savedReport, evidence)
                );
            }
            metrics.reportGenerated(
                duplicate ? "correlated" : "created",
                Duration.between(startedAt, Instant.now())
            );
            return saved;
        } catch (RuntimeException exception) {
            metrics.reportGenerated("failed", Duration.between(startedAt, Instant.now()));
            throw exception;
        }
    }

    private String incidentDedupKey(RcaReport report, EvidenceBundle evidence) {
        long windowSeconds = Math.max(60L, properties.getIncident().getCorrelationWindowMinutes() * 60L);
        long bucket = evidence.collectedAt().getEpochSecond() / windowSeconds;
        String cause = report.summary().mostLikelyCause()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
        return tokens.sha256(String.join(
            "|",
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName().toLowerCase(Locale.ROOT),
            cause,
            Long.toString(bucket)
        ));
    }

    public List<String> collectorsFor(String alertName) {
        return switch (alertName) {
            case "NodeNotReady", "KubeletDown", "KubeletUnhealthy" ->
                List.of("node", "kubernetes", "systemd", "runtime", "kubelet", "kernel", "network", "conntrack");
            case "DiskPressure" -> List.of("node", "disk", "inode", "kernel", "systemd");
            case "MemoryPressure" -> List.of("node", "memory", "kernel", "systemd", "process");
            case "PIDPressure" -> List.of("node", "process", "systemd", "kernel");
            case "NetworkUnavailable" ->
                List.of("node", "kubernetes", "network", "cni", "dns", "conntrack", "kernel");
            case "ContainerdDown", "ContainerRuntimeUnhealthy" ->
                List.of("runtime", "systemd", "kernel", "disk");
            case "CoreDNSUnhealthy", "CoreDNSLatencyHigh" ->
                List.of("dns", "network", "cni", "conntrack", "kubernetes");
            case "EtcdLatencyHigh", "APIServerLatencyHigh" ->
                List.of("node", "kubernetes", "network", "dns", "systemd", "kernel", "disk");
            default -> List.of("node", "systemd", "runtime", "disk", "inode", "memory", "process", "network", "kernel");
        };
    }

    private Map<String, Object> timeRange(AlertmanagerAlert alert) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (alert.startsAt() != null) {
            range.put("from", alert.startsAt().toString());
        }
        if (alert.endsAt() != null) {
            range.put("to", alert.endsAt().toString());
        }
        return range;
    }

    private String value(Map<String, String> values, String key) {
        return values == null ? null : values.get(key);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
