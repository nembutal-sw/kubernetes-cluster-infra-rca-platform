package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.LlmEvidenceCatalog;
import io.clusterinfra.rca.webconsole.analysis.RootCauseCandidateBuilder;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.PreprocessedEvidence;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.RuleAnalysis;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.ActionDefinition;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.service.PolicyEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RuleAnalysisStage {
    private final PolicyEngine policyEngine;
    private final RootCauseCandidateBuilder candidateBuilder;
    private final OperationalCatalogService catalogService;
    private final RcaQualityGateEvaluator qualityGateEvaluator;

    public RuleAnalysisStage(
        PolicyEngine policyEngine,
        RootCauseCandidateBuilder candidateBuilder,
        OperationalCatalogService catalogService,
        RcaQualityGateEvaluator qualityGateEvaluator
    ) {
        this.policyEngine = policyEngine;
        this.candidateBuilder = candidateBuilder;
        this.catalogService = catalogService;
        this.qualityGateEvaluator = qualityGateEvaluator;
    }

    public RuleAnalysis process(PreprocessedEvidence preprocessed) {
        String alertName = preprocessed.evidence().alertName();
        List<RootCauseCandidate> candidates = candidateBuilder.build(
            preprocessed.signals(),
            fallbackCause(alertName)
        );
        List<RecommendedAction> actions = actions(alertName, preprocessed.signals());
        Map<String, Object> qualityGate = qualityGateEvaluator.evaluate(
            preprocessed.signals(),
            candidates,
            preprocessed.evidenceQuality()
        );
        return new RuleAnalysis(
            preprocessed,
            candidates,
            actions,
            qualityGate,
            llmPayload(preprocessed, candidates, actions, qualityGate)
        );
    }

    private List<RecommendedAction> actions(String alertName, List<Signal> signals) {
        Set<String> names = signals.stream().map(Signal::name).collect(Collectors.toSet());
        Set<String> components = signals.stream().map(Signal::component).collect(Collectors.toSet());
        List<RecommendedAction> actions = new ArrayList<>();
        catalogService.recommendedActions(alertName, names, components).forEach(entry -> {
            ActionDefinition action = entry.getValue();
            actions.add(policyEngine.classify(
                entry.getKey(),
                stringOrDefault(action.action(), "Continue manual investigation using the attached RCA evidence."),
                stringOrDefault(action.reason(), "No registered safe action matches the current evidence.")
            ));
        });
        return actions.stream().collect(Collectors.toMap(
            action -> action.actionKey() + ":" + action.source(),
            action -> action,
            (left, right) -> left,
            LinkedHashMap::new
        )).values().stream().toList();
    }

    private Map<String, Object> llmPayload(
        PreprocessedEvidence preprocessed,
        List<RootCauseCandidate> candidates,
        List<RecommendedAction> actions,
        Map<String, Object> qualityGate
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0");
        result.put("cluster_id", preprocessed.evidence().clusterId());
        result.put("node_name", preprocessed.evidence().nodeName());
        result.put("alert_name", preprocessed.evidence().alertName());
        result.put("collected_at", preprocessed.evidence().collectedAt().toString());
        result.put("collectors", preprocessed.sanitizedCollectors());
        result.put("collector_status", preprocessed.evidenceQuality().getOrDefault("collector_status", Map.of()));
        result.put("evidence_quality", preprocessed.evidenceQuality());
        result.put("evidence_contract", preprocessed.evidenceContract());
        result.put("quality_gate", qualityGate);
        result.put("derived_signals", preprocessed.signals().stream().map(Signal::asMap).toList());
        List<Map<String, Object>> evidenceCatalog = LlmEvidenceCatalog.fromSignals(preprocessed.signals());
        result.put("evidence_catalog", evidenceCatalog);
        result.put("llm_evidence_policy", Map.of(
            "reference_field", "supporting_evidence_ids",
            "allowed_evidence_ids", evidenceCatalog.stream().map(item -> item.get("evidence_id")).toList(),
            "free_form_evidence_allowed", false
        ));
        result.put("rule_candidates", candidates);
        result.put("policy_classified_actions", actions);
        return result;
    }

    private String fallbackCause(String alertName) {
        return switch (alertName) {
            case "NodeNotReady" -> "Kubelet, container runtime, resource pressure, or node-to-control-plane connectivity failure";
            case "DiskPressure" -> "Filesystem capacity, inode exhaustion, or storage I/O degradation";
            case "MemoryPressure" -> "Node memory exhaustion, reclaim pressure, or kernel OOM activity";
            case "PIDPressure" -> "PID exhaustion caused by process fan-out, zombies, or runtime shim leakage";
            case "NetworkUnavailable" -> "NIC, route, CNI, DNS, MTU, or conntrack failure";
            case "KubeletDown", "KubeletUnhealthy" -> "Kubelet unit failure, deadlock, runtime dependency, or API connectivity issue";
            case "ContainerdDown", "ContainerRuntimeUnhealthy" -> "Container runtime unit, socket, storage, or kernel failure";
            case "CoreDNSUnhealthy", "CoreDNSLatencyHigh" -> "CoreDNS endpoint, upstream resolver, CNI, or conntrack problem";
            case "EtcdLatencyHigh" -> "Etcd disk fsync, peer network, resource, or quorum degradation";
            case "APIServerLatencyHigh" -> "API server dependency, etcd, admission, resource, or network latency";
            default -> "Node or Linux subsystem failure requiring additional evidence";
        };
    }

    private String stringOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
