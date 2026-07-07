package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NodePressureConditionDetector implements SignalDetector {
    private static final Map<String, ConditionSignal> CONDITIONS = Map.of(
        "diskpressure", new ConditionSignal(
            "node_disk_pressure",
            "disk",
            "Kubernetes reports DiskPressure=True for the node.",
            "Correlate node DiskPressure timing with filesystem capacity, inode, kubelet, and eviction evidence."
        ),
        "memorypressure", new ConditionSignal(
            "node_memory_pressure",
            "memory",
            "Kubernetes reports MemoryPressure=True for the node.",
            "Correlate node MemoryPressure timing with memory usage, reclaim, OOM, and cgroup evidence."
        ),
        "pidpressure", new ConditionSignal(
            "node_pid_pressure",
            "process",
            "Kubernetes reports PIDPressure=True for the node.",
            "Correlate node PIDPressure timing with process fan-out, zombie count, and runtime shim evidence."
        ),
        "networkunavailable", new ConditionSignal(
            "node_network_unavailable",
            "network",
            "Kubernetes reports NetworkUnavailable=True for the node.",
            "Correlate node NetworkUnavailable timing with NIC, route, CNI, DNS, and conntrack evidence."
        )
    );

    @Override
    public String id() {
        return "node-pressure-conditions";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        for (Map.Entry<String, Object> entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.contains("node_conditions") || !key.endsWith(".status") || !conditionTrue(entry.getValue())) {
                continue;
            }
            CONDITIONS.entrySet().stream()
                .filter(condition -> key.contains(condition.getKey()))
                .findFirst()
                .ifPresent(condition -> signals.add(signal(condition.getValue(), entry)));
        }
        return signals.stream()
            .collect(Collectors.toMap(
                Signal::name,
                signal -> signal,
                (left, right) -> left,
                java.util.LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();
    }

    private Signal signal(ConditionSignal condition, Map.Entry<String, Object> entry) {
        return DetectorSupport.matchedSignal(
            condition.name(),
            condition.component(),
            "critical",
            entry.getValue(),
            List.of(entry.getKey()),
            condition.interpretation(),
            condition.nextStep(),
            "kubernetes", "node_condition", condition.component()
        );
    }

    private static boolean conditionTrue(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        return "true".equalsIgnoreCase(AnalysisContext.string(value));
    }

    private record ConditionSignal(
        String name,
        String component,
        String interpretation,
        String nextStep
    ) {
    }
}
