package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SystemdFailureDetector implements SignalDetector {
    @Override
    public String id() {
        return "systemd-failure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        boolean failedUnit = context.flattened().entrySet().stream().anyMatch(entry -> {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = AnalysisContext.string(entry.getValue()).toLowerCase(Locale.ROOT);
            return (key.contains("failed_units") || key.contains("failed_unit"))
                && (value.contains("failed") || value.contains("error")
                || AnalysisContext.toDouble(entry.getValue()).orElse(0) > 0);
        }) || context.contains("failed unit")
            || context.contains("activating (auto-restart)")
            || context.contains("start request repeated too quickly");
        if (!failedUnit) {
            return List.of();
        }
        return List.of(DetectorSupport.matchedSignal(
            "systemd_failed_units",
            "systemd",
            "warning",
            "failed or restarting unit",
            List.of("systemd.failed_units", "systemd logs"),
            "One or more systemd services are failed or repeatedly restarting.",
            "Inspect failed unit status, restart counters, dependencies, and journal logs.",
            "systemd"
        ));
    }
}
