package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PidPressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "pid-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.percentage("pid", "usage", "percent").ifPresent(match -> {
            double warning = context.thresholds().getPidWarningPercent();
            double critical = context.thresholds().getPidCriticalPercent();
            if (match.value() >= warning) {
                boolean severe = match.value() >= critical;
                signals.add(DetectorSupport.thresholdSignal(
                    "pid_usage_high",
                    "process",
                    severe ? "critical" : "warning",
                    match,
                    severe ? critical : warning,
                    "PID capacity is close to exhaustion.",
                    "Inspect process fan-out, zombie processes, and container runtime shims.",
                    "process"
                ));
            }
        });
        return signals;
    }
}
