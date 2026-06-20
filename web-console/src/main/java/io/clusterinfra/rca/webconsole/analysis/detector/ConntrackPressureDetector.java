package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConntrackPressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "conntrack-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.ratio(List.of("conntrack", "count"), List.of("conntrack", "max")).ifPresent(match -> {
            double warning = context.thresholds().getConntrackWarningPercent();
            double critical = context.thresholds().getConntrackCriticalPercent();
            if (match.value() >= warning) {
                boolean severe = match.value() >= critical;
                signals.add(DetectorSupport.thresholdSignal(
                    "conntrack_near_limit",
                    "conntrack",
                    severe ? "critical" : "warning",
                    match,
                    severe ? critical : warning,
                    "Conntrack table occupancy is close to its configured limit.",
                    "Inspect conntrack statistics, connection churn, drops, and reviewed sizing.",
                    "conntrack", "network"
                ));
            }
        });
        return signals;
    }
}
