package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MemoryPressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "memory-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.percentage("memory", "usage", "percent").ifPresent(match -> {
            double threshold = context.thresholds().getMemoryCriticalPercent();
            if (match.value() >= threshold) {
                signals.add(DetectorSupport.thresholdSignal(
                    "memory_pressure_critical",
                    "memory",
                    "critical",
                    match,
                    threshold,
                    "Node memory consumption is critically high.",
                    "Inspect free, vmstat, cgroup usage, reclaim pressure, and top memory consumers.",
                    "memory"
                ));
            }
        });
        return signals;
    }
}
