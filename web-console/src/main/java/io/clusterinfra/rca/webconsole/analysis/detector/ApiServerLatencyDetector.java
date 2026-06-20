package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ApiServerLatencyDetector implements SignalDetector {
    @Override
    public String id() {
        return "api-server-latency";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.number("api", "server", "latency").ifPresent(match -> {
            double latency = context.latencyMs(match.value());
            double threshold = context.thresholds().getApiServerLatencyWarningMs();
            if (latency >= threshold) {
                signals.add(DetectorSupport.thresholdSignal(
                    "api_server_latency_high",
                    "api-server",
                    "warning",
                    new AnalysisContext.MatchedNumber(match.field(), latency),
                    threshold,
                    "Kubernetes API server latency exceeds the configured threshold.",
                    "Correlate API latency with etcd, admission, control-plane CPU, and node network reachability.",
                    "kubernetes", "network"
                ));
            }
        });
        return signals;
    }
}
