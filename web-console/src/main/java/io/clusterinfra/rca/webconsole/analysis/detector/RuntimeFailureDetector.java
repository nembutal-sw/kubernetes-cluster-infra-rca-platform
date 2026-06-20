package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import java.util.ArrayList;
import java.util.List;
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
}
