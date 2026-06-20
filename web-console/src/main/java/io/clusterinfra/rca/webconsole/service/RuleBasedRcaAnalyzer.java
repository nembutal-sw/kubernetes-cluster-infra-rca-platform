package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.ConfidenceScorer;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;
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

    public RuleBasedRcaAnalyzer(
        PolicyEngine policyEngine,
        LlmAnalysisService llm,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        SignalDetectionEngine detectionEngine,
        ConfidenceScorer confidenceScorer,
        RootCauseCandidateBuilder candidateBuilder
    ) {
        this.policyEngine = policyEngine;
        this.llm = llm;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.detectionEngine = detectionEngine;
        this.confidenceScorer = confidenceScorer;
        this.candidateBuilder = candidateBuilder;
    }

    public RcaReport analyze(String reportId, EvidenceBundle evidence) {
        List<Signal> signals = deriveSignals(evidence.collectors());
        List<RootCauseCandidate> candidates = candidates(evidence.alertName(), signals);
        List<RecommendedAction> actions = actions(evidence.alertName(), signals);
        Map<String, Object> preprocessed = preprocess(evidence, signals, candidates, actions);
        Map<String, Object> llmAnalysis = llm.analyze(preprocessed);
        candidates = mergeLlmCandidates(candidates, llmAnalysis);
        actions = mergeLlmActions(actions, llmAnalysis);

        List<Map<String, Object>> reportEvidence = new ArrayList<>();
        reportEvidence.add(Map.of(
            "type", "collector_summary",
            "collectors", new ArrayList<>(evidence.collectors().keySet()),
            "collected_at", evidence.collectedAt().toString()
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
        Confidence confidence = confidenceScorer.reportConfidence(signals);
        Set<String> components = new LinkedHashSet<>();
        signals.forEach(signal -> components.add(signal.component()));
        if (components.isEmpty()) {
            components.add(componentForAlert(evidence.alertName()));
        }

        return new RcaReport(
            reportId,
            evidence.clusterId(),
            null,
            RcaJobStatus.completed,
            Map.of("source", "evidence", "alert_name", evidence.alertName()),
            Map.of("nodes", List.of(evidence.nodeName()), "components", List.copyOf(components)),
            new RcaSummary(evidence.alertName(), mostLikely.cause(), confidence),
            reportEvidence,
            candidates,
            actions,
            actions,
            Instant.now()
        );
    }

    public boolean hasActionableSignals(Map<String, Object> collectors) {
        return !deriveSignals(collectors).isEmpty();
    }

    public List<Map<String, Object>> deriveTimelineSignals(Map<String, Object> collectors) {
        return deriveSignals(collectors).stream().map(Signal::asMap).toList();
    }

    private List<Signal> deriveSignals(Map<String, Object> collectors) {
        return detectionEngine.detect(collectors);
    }

    private List<RootCauseCandidate> candidates(String alertName, List<Signal> signals) {
        return candidateBuilder.build(signals, fallbackCause(alertName));
    }

    private List<RecommendedAction> actions(String alertName, List<Signal> signals) {
        Set<String> names = signals.stream().map(Signal::name).collect(java.util.stream.Collectors.toSet());
        Set<String> components = signals.stream().map(Signal::component).collect(java.util.stream.Collectors.toSet());
        List<RecommendedAction> actions = new ArrayList<>();
        actions.add(policyEngine.classify(
            "collect_more_evidence",
            "Collect additional read-only node evidence for the incident window.",
            "Read-only verification does not change node or workload state."
        ));
        if (!signals.isEmpty()) {
            actions.add(policyEngine.classify(
                "collect_linux_low_level_evidence",
                "Collect Linux kernel, systemd, process, storage, and network diagnostics.",
                "Low-level Linux inspection is read-only and should precede any remediation."
            ));
        }
        if (components.contains("disk") || components.contains("inode") || "DiskPressure".equals(alertName)) {
            actions.add(policyEngine.classify(
                "inspect_storage_state",
                "Inspect filesystem, inode, mount, block device, and kernel I/O state.",
                "Storage inspection is read-only."
            ));
        }
        if (components.stream().anyMatch(Set.of("network", "conntrack", "dns", "cni")::contains)) {
            actions.add(policyEngine.classify(
                "inspect_network_state",
                "Inspect NIC, route, socket, conntrack, resolver, and CNI state.",
                "Network inspection is read-only and should precede configuration changes."
            ));
        }
        if (names.contains("containerd_unit_unhealthy")) {
            actions.add(policyEngine.classify(
                "restart_containerd",
                "Consider an operator-approved containerd restart after confirming the runtime remains unhealthy.",
                "Runtime restart can disrupt workloads and requires approval."
            ));
        }
        if (names.contains("container_runtime_unit_unhealthy")) {
            actions.add(policyEngine.classify(
                "restart_container_runtime",
                "Consider an operator-approved runtime restart after confirming the detected CRI remains unhealthy.",
                "Runtime restart can disrupt workloads and requires approval."
            ));
        }
        if (names.contains("kubelet_unit_unhealthy")) {
            actions.add(policyEngine.classify(
                "restart_kubelet",
                "Consider an operator-approved kubelet restart after reviewing the failure evidence.",
                "Kubelet restart can affect workload lifecycle handling and requires approval."
            ));
        }
        if (names.contains("disk_usage_critical") || names.contains("inode_usage_critical")) {
            actions.add(policyEngine.classify(
                "cleanup_disk",
                "After path review and approval, clean confirmed-unused files or expand capacity.",
                "Disk cleanup can cause data loss if the target path is incorrect."
            ));
        }
        if (names.contains("memory_pressure_critical") || names.contains("kernel_oom_detected")) {
            actions.add(policyEngine.classify(
                "cordon_node",
                "If memory pressure continues, consider operator-approved node cordon or drain.",
                "This changes scheduling and requires approval."
            ));
        }
        if (names.stream().anyMatch(Set.of(
            "conntrack_near_limit", "cni_config_invalid", "dns_unconfigured", "dns_latency_high",
            "cni_mtu_values_inconsistent"
        )::contains)) {
            actions.add(policyEngine.classify(
                "open_gitops_pr",
                "Propose CNI, DNS, MTU, conntrack, or sysctl changes through a reviewed GitOps PR.",
                "Cluster configuration must not be changed directly by RCA."
            ));
        }
        if (names.stream().anyMatch(Set.of(
            "kernel_io_error", "root_filesystem_read_only", "nic_link_flap", "blocked_task_detected"
        )::contains)) {
            actions.add(policyEngine.classify(
                "manual_hardware_check",
                "Investigate storage, filesystem, NIC, driver, and physical path health.",
                "Hardware and kernel path validation requires human investigation."
            ));
        }
        if (names.contains("blocked_task_detected") || names.contains("root_filesystem_read_only")) {
            actions.add(policyEngine.classify(
                "reboot_node",
                "A node reboot may be considered only as a last-resort operator decision.",
                "Node reboot has broad impact and must never be automated."
            ));
        }
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
        List<RecommendedAction> actions
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0");
        result.put("cluster_id", evidence.clusterId());
        result.put("node_name", evidence.nodeName());
        result.put("alert_name", evidence.alertName());
        result.put("collected_at", evidence.collectedAt().toString());
        result.put("collectors", sanitize(evidence.collectors(), 0));
        result.put("derived_signals", signals.stream().map(Signal::asMap).toList());
        result.put("rule_candidates", candidates);
        result.put("policy_classified_actions", actions);
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
            merged.add(new RootCauseCandidate(
                cause,
                confidence(item.get("confidence")),
                stringList(item.get("supporting_evidence"))
            ));
        });
        return merged;
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
}
