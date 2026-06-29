package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NodeReadinessDetector implements SignalDetector {
    @Override
    public String id() {
        return "node-readiness";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        return nodeReadyFailure(context)
            .map(match -> List.of(DetectorSupport.matchedSignal(
                "node_not_ready",
                "kubernetes",
                "critical",
                match.value(),
                List.of(match.field()),
                "Kubernetes reports the node as not ready.",
                "Correlate node conditions with kubelet, runtime, disk, memory, PID, and network evidence.",
                "kubernetes"
            )))
            .orElseGet(List::of);
    }

    private Optional<AnalysisContext.MatchedValue> nodeReadyFailure(AnalysisContext context) {
        for (var entry : context.flattened().entrySet()) {
            String key = entry.getKey();
            if (isNodeReadinessField(key) && unhealthyReadyValue(entry.getValue())) {
                return Optional.of(new AnalysisContext.MatchedValue(key, entry.getValue()));
            }
        }
        return Optional.empty();
    }

    private boolean isNodeReadinessField(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("kubernetes.node_ready")
            || normalized.equals("node.node_ready")
            || normalized.equals("node.ready")
            || normalized.equals("node.status")
            || normalized.equals("kubernetes.node_conditions.ready.status")
            || normalized.endsWith(".node_conditions.ready.status");
    }

    private boolean unhealthyReadyValue(Object rawValue) {
        if (Boolean.FALSE.equals(rawValue)) {
            return true;
        }
        String value = AnalysisContext.string(rawValue).toLowerCase(Locale.ROOT);
        return value.equals("false")
            || value.equals("failed")
            || value.equals("unhealthy")
            || value.equals("notready")
            || value.equals("not_ready")
            || value.equals("unknown")
            || value.equals("down")
            || value.equals("error");
    }
}
