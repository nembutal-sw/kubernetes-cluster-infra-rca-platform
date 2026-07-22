package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.ConfidenceScorer;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.ImpactScopeAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.EnrichedAnalysis;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.service.TopologyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ReportAssemblyStage {
    private final ConfidenceScorer confidenceScorer;
    private final EvidenceQualityAnalyzer evidenceQualityAnalyzer;
    private final ImpactScopeAnalyzer impactScopeAnalyzer;
    private final TopologyService topology;

    public ReportAssemblyStage(
        ConfidenceScorer confidenceScorer,
        EvidenceQualityAnalyzer evidenceQualityAnalyzer,
        ImpactScopeAnalyzer impactScopeAnalyzer,
        TopologyService topology
    ) {
        this.confidenceScorer = confidenceScorer;
        this.evidenceQualityAnalyzer = evidenceQualityAnalyzer;
        this.impactScopeAnalyzer = impactScopeAnalyzer;
        this.topology = topology;
    }

    public RcaReport assemble(String reportId, EnrichedAnalysis analysis) {
        EvidenceBundle evidence = analysis.preprocessed().evidence();
        List<Signal> signals = analysis.preprocessed().signals();
        List<Map<String, Object>> reportEvidence = reportEvidence(analysis);
        RootCauseCandidate mostLikely = analysis.candidates().getFirst();
        Confidence confidence = adjustConfidence(
            confidenceScorer.reportConfidence(signals),
            evidenceQualityAnalyzer.confidencePenalty(analysis.preprocessed().evidenceQuality())
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
            analysis.candidates(),
            analysis.actions(),
            analysis.actions(),
            Instant.now()
        );
    }

    private List<Map<String, Object>> reportEvidence(EnrichedAnalysis analysis) {
        EvidenceBundle evidence = analysis.preprocessed().evidence();
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of(
            "type", "collector_summary",
            "collectors", new ArrayList<>(evidence.collectors().keySet()),
            "collected_at", evidence.collectedAt().toString()
        ));
        result.add(Map.of(
            "type", "evidence_quality",
            "quality", analysis.preprocessed().evidenceQuality()
        ));
        result.add(Map.of(
            "type", "evidence_contract",
            "contract", analysis.preprocessed().evidenceContract()
        ));
        result.add(Map.of(
            "type", "quality_gate",
            "gate", analysis.qualityGate()
        ));
        result.add(Map.of(
            "type", "derived_signals",
            "signals", analysis.preprocessed().signals().stream().map(Signal::asMap).toList()
        ));
        result.add(Map.of(
            "type", "preprocessed_evidence",
            "payload", analysis.llmPayload()
        ));
        result.add(Map.of(
            "type", "llm_analysis",
            "analysis", analysis.llmAnalysis()
        ));
        result.add(Map.of(
            "type", "resolution_checklist",
            "items", resolutionChecklist(evidence.alertName(), analysis.preprocessed().signals())
        ));
        return List.copyOf(result);
    }

    private boolean isDemoEvidence(Map<String, Object> collectors) {
        Object metadata = collectors.get("_meta");
        if (!(metadata instanceof Map<?, ?> values)) {
            return false;
        }
        return Boolean.TRUE.equals(values.get("demo"))
            || "demo".equalsIgnoreCase(String.valueOf(values.get("source")));
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

    private List<Map<String, Object>> resolutionChecklist(String alertName, List<Signal> signals) {
        LinkedHashMap<String, Map<String, Object>> items = new LinkedHashMap<>();
        addCheck(items, "Node conditions", "kubectl describe node <node>",
            "Confirm pressure and readiness transition timing.");
        Set<String> names = signals.stream().map(Signal::name).collect(Collectors.toSet());
        for (Signal signal : signals) {
            switch (signal.component()) {
                case "disk", "inode" ->
                    addCheck(items, "Storage capacity and latency", "df -hT; df -i; iostat -xz 1 5",
                        "Separate capacity, inode, filesystem, and device latency failures.");
                case "memory" ->
                    addCheck(items, "Memory pressure",
                        "free -m; vmstat 1 5; dmesg -T | grep -i -E 'oom|out of memory'",
                        "Confirm reclaim pressure and OOM activity.");
                case "process" ->
                    addCheck(items, "PID pressure", "ps -eLf | wc -l; ps -eo stat,ppid,pid,cmd | grep '^Z'",
                        "Find process fan-out and zombie parents.");
                case "systemd" ->
                    addCheck(items, "Systemd failed units",
                        "systemctl --failed --no-pager; journalctl -p warning..alert --since '-30 min'",
                        "Confirm failed or restart-looping services and dependency failures.");
                case "kubelet" ->
                    addCheck(items, "Kubelet state",
                        "systemctl status kubelet --no-pager; journalctl -u kubelet --since '-30 min'",
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
                    addCheck(items, "Etcd health",
                        "etcdctl endpoint health --cluster; etcdctl endpoint status --cluster -w table",
                        "Confirm quorum, peer latency, leader state, and backend size.");
                case "api-server" ->
                    addCheck(items, "API server readiness", "kubectl get --raw='/readyz?verbose'",
                        "Identify slow or failing API server dependencies.");
                default -> {
                }
            }
        }
        if ("CoreDNSUnhealthy".equals(alertName) || "CoreDNSLatencyHigh".equals(alertName)) {
            addCheck(items, "CoreDNS endpoints",
                "kubectl -n kube-system get pods,svc,endpoints -l k8s-app=kube-dns -o wide",
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
}
