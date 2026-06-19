package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerAlert;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RcaService {
    private final RcaRepository repository;
    private final RuleBasedRcaAnalyzer analyzer;

    public RcaService(RcaRepository repository, RuleBasedRcaAnalyzer analyzer) {
        this.repository = repository;
        this.analyzer = analyzer;
    }

    public WebhookIngestResponse ingestAlertmanager(AlertmanagerPayload payload) {
        List<RcaJob> jobs = new ArrayList<>();
        List<String> reports = new ArrayList<>();
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
            if (repository.getCluster(clusterId).isEmpty()) {
                skipped.add(alertName + ": cluster " + clusterId + " is not registered");
                continue;
            }
            String nodeName = firstNonBlank(
                alert.labelsOrEmpty().get("node"),
                alert.labelsOrEmpty().get("nodename"),
                alert.labelsOrEmpty().get("instance")
            );
            if (nodeName != null && repository.getAgent(clusterId, nodeName).isPresent()) {
                EvidenceRequest request = repository.createEvidenceRequest(new EvidenceRequestCreateRequest(
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
                continue;
            }

            String effectiveNode = nodeName == null ? "unknown" : nodeName;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("status", alert.statusOrDefault());
            metadata.put("labels", alert.labelsOrEmpty());
            metadata.put("annotations", alert.annotationsOrEmpty());
            metadata.put("generator_url", alert.generatorUrl());
            metadata.put("collector_status", "alert_only_no_registered_agent");
            EvidenceBundle evidence = repository.saveEvidence(new EvidenceBundle(
                null,
                clusterId,
                effectiveNode,
                alertName,
                Instant.now(),
                Map.of("alertmanager", metadata)
            ));
            RcaJob job = createCompletedJob(evidence);
            jobs.add(job);
            reports.add(job.reportId());
        }
        return new WebhookIngestResponse(payload.alertsOrEmpty().size(), jobs, reports, requests, skipped);
    }

    public RcaJob createReportFromEvidenceRequest(EvidenceRequest request) {
        if (request.status() != EvidenceRequestStatus.completed || request.evidenceId() == null) {
            return null;
        }
        EvidenceBundle evidence = repository.getEvidence(request.evidenceId()).orElse(null);
        if (evidence == null) {
            return null;
        }
        if ("scheduled_monitoring".equals(request.context().get("trigger"))
            && !analyzer.hasActionableSignals(evidence.collectors())) {
            return null;
        }
        return createCompletedJob(evidence);
    }

    private RcaJob createCompletedJob(EvidenceBundle evidence) {
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
        return repository.saveReportAndJob(report, job);
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
