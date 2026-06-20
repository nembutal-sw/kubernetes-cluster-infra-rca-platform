package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CniFailureDetector extends AbstractStatusDetector {
    public CniFailureDetector() {
        super(
            "cni-failure",
            "cni",
            "cni_config_invalid",
            "cni",
            "CNI configuration or health checks indicate an error.",
            "Inspect CNI configuration, plugin logs, routes, MTU, and node network state."
        );
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>(super.detect(context));
        context.flattened().entrySet().stream()
            .filter(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                String value = AnalysisContext.string(entry.getValue());
                return key.contains("mtu")
                    && (key.contains("mismatch") || key.contains("inconsistent"))
                    && (Boolean.TRUE.equals(entry.getValue()) || "true".equalsIgnoreCase(value));
            })
            .findFirst()
            .ifPresent(entry -> signals.add(DetectorSupport.matchedSignal(
                "cni_mtu_values_inconsistent",
                "cni",
                "warning",
                entry.getValue(),
                List.of(entry.getKey()),
                "Host and CNI interface MTU values are inconsistent.",
                "Verify overlay overhead and propose reviewed CNI MTU changes through GitOps.",
                "cni", "network"
            )));
        if (signals.stream().noneMatch(signal -> "cni_mtu_values_inconsistent".equals(signal.name()))
            && (context.contains("mtu mismatch") || context.contains("inconsistent mtu")
            || context.contains("mtu values are different"))) {
            signals.add(DetectorSupport.matchedSignal(
                "cni_mtu_values_inconsistent",
                "cni",
                "warning",
                "MTU mismatch log match",
                List.of("collector text"),
                "Host and CNI interface MTU values are inconsistent.",
                "Verify overlay overhead and propose reviewed CNI MTU changes through GitOps.",
                "cni", "network"
            ));
        }
        return signals;
    }
}
