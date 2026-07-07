package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerAlert;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AlertIngestService {
    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final IncidentLifecycleService lifecycle;
    private final RcaConsoleProperties properties;
    private final RcaMetrics metrics;
    private final CollectorSelectionService collectorSelection;

    public AlertIngestService(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        IncidentLifecycleService lifecycle,
        RcaConsoleProperties properties,
        RcaMetrics metrics,
        CollectorSelectionService collectorSelection
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.evidence = evidence;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.metrics = metrics;
        this.collectorSelection = collectorSelection;
    }

    public WebhookIngestResponse ingestAlertmanager(AlertmanagerPayload payload) {
        List<RcaJob> jobs = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        List<AnalysisTask> analysisTasks = new ArrayList<>();
        List<EvidenceRequest> requests = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AlertmanagerAlert alert : payload.alertsOrEmpty()) {
            ingestAlert(payload, alert, analysisTasks, requests, skipped);
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

    private void ingestAlert(
        AlertmanagerPayload payload,
        AlertmanagerAlert alert,
        List<AnalysisTask> analysisTasks,
        List<EvidenceRequest> requests,
        List<String> skipped
    ) {
        String alertName = alert.labelsOrEmpty().getOrDefault("alertname", "UnknownAlert");
        String clusterId = firstNonBlank(
            alert.labelsOrEmpty().get("cluster_id"),
            value(payload.commonLabels(), "cluster_id"),
            value(payload.groupLabels(), "cluster_id")
        );
        if (clusterId == null) {
            skipped.add(alertName + ": cluster_id label is missing");
            return;
        }
        if (clusters.find(clusterId).isEmpty()) {
            skipped.add(alertName + ": cluster " + clusterId + " is not registered");
            return;
        }
        String nodeName = firstNonBlank(
            alert.labelsOrEmpty().get("node"),
            alert.labelsOrEmpty().get("nodename"),
            alert.labelsOrEmpty().get("instance")
        );
        if (!"firing".equalsIgnoreCase(alert.statusOrDefault())) {
            resolveOrSkip(alert, clusterId, nodeName, alertName, skipped);
            return;
        }
        if (nodeName != null && agents.find(clusterId, nodeName).isPresent()) {
            requests.add(createEvidenceRequest(clusterId, nodeName, alertName, alert));
            metrics.evidenceRequest("alertmanager", "created", 1);
            return;
        }
        analysisTasks.add(createAlertOnlyTask(clusterId, nodeName, alertName, alert));
    }

    private void resolveOrSkip(
        AlertmanagerAlert alert,
        String clusterId,
        String nodeName,
        String alertName,
        List<String> skipped
    ) {
        if ("resolved".equalsIgnoreCase(alert.statusOrDefault()) && nodeName != null) {
            lifecycle.resolveSignal(clusterId, nodeName, alertName, alert.endsAt(), "alertmanager");
            return;
        }
        skipped.add(alertName + ": unsupported alert status " + alert.statusOrDefault());
    }

    private EvidenceRequest createEvidenceRequest(
        String clusterId,
        String nodeName,
        String alertName,
        AlertmanagerAlert alert
    ) {
        return evidence.createRequest(new EvidenceRequestCreateRequest(
            clusterId,
            nodeName,
            alertName,
            collectorSelection.collectorsFor(alertName),
            timeRange(alert),
            "Alertmanager firing alert",
            Map.of(
                "labels", alert.labelsOrEmpty(),
                "annotations", alert.annotationsOrEmpty(),
                "generator_url", alert.generatorUrl() == null ? "" : alert.generatorUrl()
            )
        ));
    }

    private AnalysisTask createAlertOnlyTask(
        String clusterId,
        String nodeName,
        String alertName,
        AlertmanagerAlert alert
    ) {
        String effectiveNode = nodeName == null ? "unknown" : nodeName;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", alert.statusOrDefault());
        metadata.put("labels", alert.labelsOrEmpty());
        metadata.put("annotations", alert.annotationsOrEmpty());
        metadata.put("generator_url", alert.generatorUrl());
        metadata.put("collector_status", "alert_only_no_registered_agent");
        return evidence.saveAndEnqueue(new EvidenceBundle(
            null,
            clusterId,
            effectiveNode,
            alertName,
            Instant.now(),
            Map.of("alertmanager", metadata)
        ), "alertmanager", false, properties.getPipeline().getMaxAttempts());
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
}
