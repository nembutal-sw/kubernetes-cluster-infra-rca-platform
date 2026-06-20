package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EtcdLatencyDetector implements SignalDetector {
    @Override
    public String id() {
        return "etcd-latency";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.number("etcd", "latency").ifPresent(match -> {
            double latency = context.latencyMs(match.value());
            double threshold = context.thresholds().getEtcdLatencyWarningMs();
            if (latency >= threshold) {
                signals.add(DetectorSupport.thresholdSignal(
                    "etcd_latency_high",
                    "etcd",
                    "critical",
                    new AnalysisContext.MatchedNumber(match.field(), latency),
                    threshold,
                    "Etcd request latency is high enough to affect control-plane responsiveness.",
                    "Inspect etcd endpoint health, fsync latency, peer network latency, and quorum state.",
                    "kubernetes", "disk", "network"
                ));
            }
        });
        return signals;
    }
}
