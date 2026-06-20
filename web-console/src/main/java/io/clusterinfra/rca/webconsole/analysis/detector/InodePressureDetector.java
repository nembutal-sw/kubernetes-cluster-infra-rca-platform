package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InodePressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "inode-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.percentage("inode", "usage", "percent").ifPresent(match -> {
            double warning = context.thresholds().getInodeWarningPercent();
            double critical = context.thresholds().getInodeCriticalPercent();
            if (match.value() >= warning) {
                boolean severe = match.value() >= critical;
                signals.add(DetectorSupport.thresholdSignal(
                    severe ? "inode_usage_critical" : "inode_usage_high",
                    "inode",
                    severe ? "critical" : "warning",
                    match,
                    severe ? critical : warning,
                    "Filesystem inode consumption is near exhaustion.",
                    "Inspect df -i and identify directories creating many small files.",
                    "inode"
                ));
            }
        });
        return signals;
    }
}
