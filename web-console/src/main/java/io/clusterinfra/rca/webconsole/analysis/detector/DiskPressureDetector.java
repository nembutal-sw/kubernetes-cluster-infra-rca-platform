package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DiskPressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "disk-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.percentage("disk", "usage", "percent").ifPresent(match -> {
            double warning = context.thresholds().getDiskWarningPercent();
            double critical = context.thresholds().getDiskCriticalPercent();
            if (match.value() >= warning) {
                boolean severe = match.value() >= critical;
                signals.add(DetectorSupport.thresholdSignal(
                    severe ? "disk_usage_critical" : "disk_usage_high",
                    "disk",
                    severe ? "critical" : "warning",
                    match,
                    severe ? critical : warning,
                    "Filesystem capacity is near or above the configured threshold.",
                    "Inspect df, mount usage, large files, runtime image storage, and log growth.",
                    "disk"
                ));
            }
        });
        AnalysisContext.MatchedNumber latency = context.number("await", "ms")
            .or(() -> context.number("io", "latency"))
            .orElse(null);
        if (latency != null && latency.value() >= context.thresholds().getDiskAwaitWarningMs()) {
            signals.add(DetectorSupport.thresholdSignal(
                "disk_io_latency_high",
                "disk",
                "warning",
                latency,
                context.thresholds().getDiskAwaitWarningMs(),
                "Block device latency exceeds the configured threshold.",
                "Inspect iostat, device queue depth, kernel I/O errors, and storage backend latency.",
                "disk", "kernel"
            ));
        }
        return signals;
    }
}
