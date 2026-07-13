package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RuntimeFailureDetector extends AbstractStatusDetector {
    public RuntimeFailureDetector() {
        super(
            "runtime-failure",
            "runtime",
            "container_runtime_unit_unhealthy",
            "runtime",
            "Container runtime health checks failed.",
            "Inspect detected CRI runtime unit, socket, storage, and logs."
        );
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        if (hasHealthyRuntimeSocket(context)) {
            return List.of();
        }
        List<Signal> signals = new ArrayList<>(super.detect(context));
        context.status("containerd").ifPresent(match -> signals.add(DetectorSupport.matchedSignal(
            "containerd_unit_unhealthy",
            "containerd",
            "critical",
            match.value(),
            List.of(match.field()),
            "Containerd is not active or its socket is unhealthy.",
            "Inspect runtime unit logs, socket responsiveness, disk, and kernel state.",
            "containerd"
        )));
        return signals;
    }

    private boolean hasHealthyRuntimeSocket(AnalysisContext context) {
        return context.flattened().entrySet().stream().anyMatch(entry -> {
            String field = entry.getKey().toLowerCase(Locale.ROOT);
            return (field.endsWith("runtime_socket_healthy") || field.endsWith("containerd_socket_healthy"))
                && Boolean.TRUE.equals(entry.getValue());
        });
    }
}
