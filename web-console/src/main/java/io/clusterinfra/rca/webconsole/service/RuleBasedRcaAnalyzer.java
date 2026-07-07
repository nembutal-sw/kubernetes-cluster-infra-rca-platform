package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.ActionDefinition;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.analysis.ConfidenceScorer;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.ImpactScopeAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.RootCauseCandidateBuilder;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RuleBasedRcaAnalyzer {
    private final PolicyEngine policyEngine;
    private final LlmAnalysisService llm;
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final SignalDetectionEngine detectionEngine;
    private final ConfidenceScorer confidenceScorer;
    private final RootCauseCandidateBuilder candidateBuilder;
    private final ImpactScopeAnalyzer impactScopeAnalyzer;
    private final TopologyService topology;
    private final EvidenceQualityAnalyzer evidenceQualityAnalyzer;
    private final OperationalCatalogService catalogService;

    @Autowired
    public RuleBasedRcaAnalyzer(
        PolicyEngine policyEngine,
        LlmAnalysisService llm,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        SignalDetectionEngine detectionEngine,
        ConfidenceScorer confidenceScorer,
        RootCauseCandidateBuilder candidateBuilder,
        ImpactScopeAnalyzer impactScopeAnalyzer,
        TopologyService topology,
        EvidenceQualityAnalyzer evidenceQualityAnalyzer,
        OperationalCatalogService catalogService
    ) {
        this.policyEngine = policyEngine;
        this.llm = llm;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.detectionEngine = detectionEngine;
        this.confidenceScorer = confidenceScorer;
        this.candidateBuilder = candidateBuilder;
        this.impactScopeAnalyzer = impactScopeAnalyzer;
        this.topology = topology;
        this.evidenceQualityAnalyzer = evidenceQualityAnalyzer;
        this.catalogService = catalogService;
    }

    public RuleBasedRcaAnalyzer(
        PolicyEngine policyEngine,
        LlmAnalysisService llm,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        SignalDetectionEngine detectionEngine,
        ConfidenceScorer confidenceScorer,
        RootCauseCandidateBuilder candidateBuilder,
        ImpactScopeAnalyzer impactScopeAnalyzer,
        TopologyService topology,
        EvidenceQualityAnalyzer evidenceQualityAnalyzer
    ) {
        this(
            policyEngine,
            llm,
            properties,
            objectMapper,
            detectionEngine,
            confidenceScorer,
            candidateBuilder,
            impactScopeAnalyzer,
            topology,
            evidenceQualityAnalyzer,
            OperationalCatalogService.defaultService()
        );
    }

    public RcaReport analyze(String reportId, EvidenceBundle evidence) {
        List<Signal> signals = deriveSignals(evidence.clusterId(), evidence.collectors());
        List<RootCauseCandidate> candidates = candidates(evidence.alertName(), signals);
        List<RecommendedAction> actions = actions(evidence.alertName(), signals);
        Map<String, Object> evidenceQuality = evidenceQualityAnalyzer.assess(evidence);
        Map<String, Object> qualityGate = qualityGate(signals, candidates, evidenceQuality);
        Map<String, Object> preprocessed = preprocess(evidence, signals, candidates, actions, evidenceQuality, qualityGate);
        Map<String, Object> llmAnalysis = llm.analyze(preprocessed);
        candidates = mergeLlmCandidates(candidates, llmAnalysis);
        candidates = applyEvidenceQualityPenalty(candidates, evidenceQuality);
        actions = mergeLlmActions(actions, llmAnalysis);
        qualityGate = qualityGate(signals, candidates, evidenceQuality);
        preprocessed.put("final_quality_gate", qualityGate);

        List<Map<String, Object>> reportEvidence = new ArrayList<>();
        reportEvidence.add(Map.of(
            "type", "collector_summary",
            "collectors", new ArrayList<>(evidence.collectors().keySet()),
            "collected_at", evidence.collectedAt().toString()
        ));
        reportEvidence.add(Map.of(
            "type", "evidence_quality",
            "quality", evidenceQuality
        ));
        reportEvidence.add(Map.of(
            "type", "quality_gate",
            "gate", qualityGate
        ));
        reportEvidence.add(Map.of(
            "type", "derived_signals",
            "signals", signals.stream().map(Signal::asMap).toList()
        ));
        reportEvidence.add(Map.of(
            "type", "preprocessed_evidence",
            "payload", preprocessed
        ));
        reportEvidence.add(Map.of(
            "type", "llm_analysis",
            "analysis", llmAnalysis
        ));
        reportEvidence.add(Map.of(
            "type", "resolution_checklist",
            "items", resolutionChecklist(evidence.alertName(), signals)
        ));

        RootCauseCandidate mostLikely = candidates.getFirst();
        Confidence confidence = adjustConfidence(
            confidenceScorer.reportConfidence(signals),
            evidenceQualityAnalyzer.confidencePenalty(evidenceQuality)
        );
        Set<String> components = new LinkedHashSet<>();
        signals.forEach(signal -> components.add(signal.component()));
        if (components.isEmpty()) {
            components.add(componentForAlert(evidence.alertName()));
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("nodes", List.of(evidence.nodeName()));
        scope.put("components", List.copyOf(components));
        scope.putAll(impactScopeAnalyzer.analyze(evidence.collectors(), evidence.nodeName()));
        scope = new LinkedHashMap<>(topology.enrichScope(
            evidence.clusterId(),
            evidence.nodeName(),
            scope
        ));

        boolean demo = isDemoEvidence(evidence.collectors());
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("source", demo ? "demo" : "evidence");
        trigger.put("alert_name", evidence.alertName());
        if (demo) {
            trigger.put("demo", true);
        }
        return new RcaReport(
            reportId,
            evidence.clusterId(),
            null,
            RcaJobStatus.completed,
            Map.copyOf(trigger),
            Map.copyOf(scope),
            new RcaSummary(evidence.alertName(), mostLikely.cause(), confidence),
            reportEvidence,
            candidates,
            actions,
            actions,
            Instant.now()
        );
    }

    private boolean isDemoEvidence(Map<String, Object> collectors) {
        Object metadata = collectors.get("_meta");
        if (!(metadata instanceof Map<?, ?> values)) {
            return false;
        }
        return Boolean.TRUE.equals(values.get("demo"))
            || "demo".equalsIgnoreCase(String.valueOf(values.get("source")));
    }

    public boolean hasActionableSignals(Map<String, Object> collectors) {
        return !deriveSignals(collectors).isEmpty();
    }

    public boolean hasActionableSignals(String clusterId, Map<String, Object> collectors) {
        return !deriveSignals(clusterId, collectors).isEmpty();
    }

    public List<Map<String, Object>> deriveTimelineSignals(Map<String, Object> collectors) {
        return deriveSignals(collectors).stream().map(Signal::asMap).toList();
    }

    private List<Signal> deriveSignals(Map<String, Object> collectors) {
        return detectionEngine.detect(collectors);
    }

    private List<Signal> deriveSignals(String clusterId, Map<String, Object> collectors) {
        return detectionEngine.detect(clusterId, collectors);
    }

    private List<RootCauseCandidate> candidates(String alertName, List<Signal> signals) {
        return candidateBuilder.build(signals, fallbackCause(alertName));
    }

    private List<RecommendedAction> actions(String alertName, List<Signal> signals) {
        Set<String> names = signals.stream().map(Signal::name).collect(java.util.stream.Collectors.toSet());
        Set<String> components = signals.stream().map(Signal::component).collect(java.util.stream.Collectors.toSet());
        List<RecommendedAction> actions = new ArrayList<>();
        catalogService.recommendedActions(alertName, names, components).forEach(entry -> {
            ActionDefinition action = entry.getValue();
            actions.add(policyEngine.classify(
                entry.getKey(),
                stringOrDefault(action.action(), "Continue manual investigation using the attached RCA evidence."),
                stringOrDefault(action.reason(), "No registered safe action matches the current evidence.")
            ));
        });
        return actions.stream().collect(java.util.stream.Collectors.toMap(
            action -> action.actionKey() + ":" + action.source(),
            action -> action,
            (left, right) -> left,
            LinkedHashMap::new
        )).values().stream().toList();
    }

    private Map<String, Object> preprocess(
        EvidenceBundle evidence,
        List<Signal> signals,
        List<RootCauseCandidate> candidates,
        List<RecommendedAction> actions,
        Map<String, Object> evidenceQuality,
        Map<String, Object> qualityGate
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0");
        result.put("cluster_id", evidence.clusterId());
        result.put("node_name", evidence.nodeName());
        result.put("alert_name", evidence.alertName());
        result.put("collected_at", evidence.collectedAt().toString());
        result.put("collectors", sanitize(evidence.collectors(), 0));
        result.put("collector_status", evidenceQuality.getOrDefault("collector_status", Map.of()));
        result.put("evidence_quality", evidenceQuality);
        result.put("quality_gate", qualityGate);
        result.put("derived_signals", signals.stream().map(Signal::asMap).toList());
        result.put("rule_candidates", candidates);
        result.put("policy_classified_actions", actions);
        return result;
    }

    private Map<String, Object> qualityGate(
        List<Signal> signals,
        List<RootCauseCandidate> candidates,
        Map<String, Object> evidenceQuality
    ) {
        int signalCount = signals == null ? 0 : signals.size();
        long highConfidenceSignals = signals == null
            ? 0
            : signals.stream().filter(signal -> signal.confidence() == Confidence.high).count();
        int topCandidateScore = candidates == null || candidates.isEmpty()
            ? 0
            : candidates.getFirst().confidenceScore();
        int penalty = evidenceQualityAnalyzer.confidencePenalty(evidenceQuality);
        String evidenceQualityStatus = String.valueOf(
            evidenceQuality == null ? "unknown" : evidenceQuality.getOrDefault("status", "unknown")
        );

        List<String> reasons = new ArrayList<>();
        List<String> followUp = new ArrayList<>();
        if (signalCount == 0) {
            reasons.add("No rule-based signal crossed an RCA threshold.");
            followUp.add("Collect full node diagnostics for the incident window.");
        }
        if (topCandidateScore < 60) {
            reasons.add("Top root-cause candidate score is below the high-confidence gate.");
            followUp.add("Review matched evidence paths and collect missing subsystem evidence.");
        }
        if (penalty > 0) {
            reasons.add("Evidence quality reduced report confidence.");
            followUp.add("Refresh stale, failed, or degraded collector output before remediation.");
        }
        if (!"complete".equals(evidenceQualityStatus)) {
            reasons.add("Evidence set is " + evidenceQualityStatus + ".");
        }

        String status;
        if (signalCount == 0 || topCandidateScore < 25 || penalty >= 50) {
            status = "insufficient";
        } else if (topCandidateScore < 60 || penalty > 0 || !"complete".equals(evidenceQualityStatus)) {
            status = "limited";
        } else {
            status = "pass";
        }
        if (reasons.isEmpty()) {
            reasons.add("Rule-based evidence is sufficient for an initial RCA report.");
        }
        if (followUp.isEmpty()) {
            followUp.add("Proceed with read-only verification commands before remediation.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("rule_signal_count", signalCount);
        result.put("high_confidence_signal_count", highConfidenceSignals);
        result.put("top_candidate_score", topCandidateScore);
        result.put("confidence_penalty", penalty);
        result.put("evidence_quality_status", evidenceQualityStatus);
        result.put("rule_based_sufficient", !"insufficient".equals(status));
        result.put("additional_evidence_required", !"pass".equals(status));
        result.put("llm_diagnostic_allowed", true);
        result.put("llm_should_not_raise_confidence", !"pass".equals(status));
        result.put("reasons", reasons);
        result.put("follow_up", followUp);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object sanitize(Object value, int depth) {
        if (depth > 7) {
            return "[truncated]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
                if (Set.of("user_agent", "browser", "browser_version", "client_os", "os_version").contains(normalized)) {
                    continue;
                }
                sanitized.put(
                    key,
                    SensitiveDataRedactor.isSensitiveKey(key)
                        ? "[redacted]"
                        : sanitize(entry.getValue(), depth + 1)
                );
                if (++count >= 120) {
                    sanitized.put("_truncated", true);
                    break;
                }
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(100).map(item -> sanitize(item, depth + 1)).toList();
        }
        if (value instanceof String text) {
            String redacted = SensitiveDataRedactor.redactText(text);
            return redacted.length() > 4000
                ? redacted.substring(0, 4000) + "...[truncated]"
                : redacted;
        }
        return value;
    }

    private List<RootCauseCandidate> mergeLlmCandidates(
        List<RootCauseCandidate> ruleCandidates,
        Map<String, Object> analysis
    ) {
        List<RootCauseCandidate> merged = new ArrayList<>(ruleCandidates);
        resultList(analysis, "root_cause_candidates").stream().limit(3).forEach(item -> {
            String cause = string(item.get("cause"));
            if (cause.isBlank()) {
                return;
            }
            List<String> evidencePaths = stringList(item.get("evidence_paths"));
            List<String> supportingEvidence = new ArrayList<>(stringList(item.get("supporting_evidence")));
            String scoreReason = string(item.get("score_reason"));
            if (!scoreReason.isBlank()) {
                supportingEvidence.add(scoreReason);
            }
            supportingEvidence.add("LLM diagnostic-only candidate; rule-based evidence remains the confidence source.");
            merged.add(new RootCauseCandidate(
                cause.startsWith("LLM diagnostic: ") ? cause : "LLM diagnostic: " + cause,
                Confidence.low,
                supportingEvidence,
                evidencePaths.isEmpty() ? 15 : 25,
                evidencePaths
            ));
        });
        return merged;
    }

    private List<RootCauseCandidate> applyEvidenceQualityPenalty(
        List<RootCauseCandidate> candidates,
        Map<String, Object> quality
    ) {
        int penalty = evidenceQualityAnalyzer.confidencePenalty(quality);
        if (penalty <= 0) {
            return candidates;
        }
        return candidates.stream().map(candidate -> new RootCauseCandidate(
            candidate.cause(),
            adjustConfidence(candidate.confidence(), penalty),
            withQualityNote(candidate.supportingEvidence(), quality),
            Math.max(0, candidate.confidenceScore() - penalty),
            candidate.evidencePaths()
        )).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> withQualityNote(List<String> supportingEvidence, Map<String, Object> quality) {
        List<String> values = new ArrayList<>(supportingEvidence == null ? List.of() : supportingEvidence);
        Object notes = quality.get("notes");
        if (notes instanceof List<?> list && !list.isEmpty()) {
            values.add("Evidence quality: " + String.join("; ", list.stream().map(String::valueOf).toList()));
        }
        return values;
    }

    private Confidence adjustConfidence(Confidence confidence, int penalty) {
        if (penalty >= 40) {
            return Confidence.low;
        }
        if (penalty >= 20 && confidence == Confidence.high) {
            return Confidence.medium;
        }
        if (penalty >= 20 && confidence == Confidence.medium) {
            return Confidence.low;
        }
        return confidence;
    }

    private List<RecommendedAction> mergeLlmActions(
        List<RecommendedAction> ruleActions,
        Map<String, Object> analysis
    ) {
        List<RecommendedAction> merged = new ArrayList<>(ruleActions);
        resultList(analysis, "action_suggestions").stream().limit(5).forEach(item -> {
            String action = string(item.get("action"));
            String reason = string(item.get("reason"));
            if (action.isBlank() || reason.isBlank()) {
                return;
            }
            merged.add(policyEngine.classify(
                string(item.getOrDefault("action_key", "manual_investigation")),
                action,
                reason,
                "llm",
                Map.of()
            ));
        });
        return merged;
    }

    private List<Map<String, Object>> resolutionChecklist(String alertName, List<Signal> signals) {
        LinkedHashMap<String, Map<String, Object>> items = new LinkedHashMap<>();
        addCheck(items, "Node conditions", "kubectl describe node <node>", "Confirm pressure and readiness transition timing.");
        Set<String> names = signals.stream().map(Signal::name).collect(java.util.stream.Collectors.toSet());
        for (Signal signal : signals) {
            switch (signal.component()) {
                case "disk", "inode" ->
                    addCheck(items, "Storage capacity and latency", "df -hT; df -i; iostat -xz 1 5",
                        "Separate capacity, inode, filesystem, and device latency failures.");
                case "memory" ->
                    addCheck(items, "Memory pressure", "free -m; vmstat 1 5; dmesg -T | grep -i -E 'oom|out of memory'",
                        "Confirm reclaim pressure and OOM activity.");
                case "process" ->
                    addCheck(items, "PID pressure", "ps -eLf | wc -l; ps -eo stat,ppid,pid,cmd | grep '^Z'",
                        "Find process fan-out and zombie parents.");
                case "systemd" ->
                    addCheck(items, "Systemd failed units",
                        "systemctl --failed --no-pager; journalctl -p warning..alert --since '-30 min'",
                        "Confirm failed or restart-looping services and dependency failures.");
                case "kubelet" ->
                    addCheck(items, "Kubelet state", "systemctl status kubelet --no-pager; journalctl -u kubelet --since '-30 min'",
                        "Confirm failure, restart, runtime, or API connectivity errors.");
                case "containerd", "runtime" ->
                    addCheck(items, "Container runtime", "crictl info; systemctl status containerd --no-pager",
                        "Confirm CRI socket responsiveness and unit health.");
                case "network", "conntrack", "cni", "dns" ->
                    addCheck(items, "Node network", "ip -s link; ip route; ss -s; conntrack -S; cat /etc/resolv.conf",
                        "Confirm link, route, socket, conntrack, MTU, and resolver state.");
                case "kernel" ->
                    addCheck(items, "Kernel errors", "dmesg -T | tail -n 300",
                        "Confirm I/O, filesystem, blocked task, driver, and link errors.");
                case "etcd" ->
                    addCheck(items, "Etcd health", "etcdctl endpoint health --cluster; etcdctl endpoint status --cluster -w table",
                        "Confirm quorum, peer latency, leader state, and backend size.");
                case "api-server" ->
                    addCheck(items, "API server readiness", "kubectl get --raw='/readyz?verbose'",
                        "Identify slow or failing API server dependencies.");
                default -> {
                }
            }
        }
        if ("CoreDNSUnhealthy".equals(alertName) || "CoreDNSLatencyHigh".equals(alertName)) {
            addCheck(items, "CoreDNS endpoints", "kubectl -n kube-system get pods,svc,endpoints -l k8s-app=kube-dns -o wide",
                "Confirm pod readiness and endpoint availability.");
        }
        if (names.stream().anyMatch(Set.of(
            "kernel_io_error", "root_filesystem_read_only", "nic_link_flap", "blocked_task_detected"
        )::contains)) {
            addCheck(items, "Kernel errors", "dmesg -T | tail -n 300",
                "Confirm I/O, filesystem, blocked task, driver, and link errors.");
        }
        return List.copyOf(items.values());
    }

    private void addCheck(
        Map<String, Map<String, Object>> items,
        String title,
        String command,
        String reason
    ) {
        items.putIfAbsent(title, Map.of("title", title, "command", command, "reason", reason, "read_only", true));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultList(Map<String, Object> analysis, String key) {
        Object result = analysis.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return List.of();
        }
        Object value = resultMap.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::string).filter(item -> !item.isBlank()).limit(10).toList();
    }

    private Confidence confidence(Object value) {
        try {
            return Confidence.valueOf(string(value).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Confidence.low;
        }
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

    private String componentForAlert(String alertName) {
        String name = alertName.toLowerCase(Locale.ROOT);
        if (name.contains("disk")) return "disk";
        if (name.contains("memory")) return "memory";
        if (name.contains("pid")) return "process";
        if (name.contains("network") || name.contains("cni")) return "network";
        if (name.contains("dns")) return "dns";
        if (name.contains("etcd")) return "etcd";
        if (name.contains("api")) return "api-server";
        if (name.contains("runtime") || name.contains("containerd")) return "runtime";
        if (name.contains("kubelet")) return "kubelet";
        return "node";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String stringOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
