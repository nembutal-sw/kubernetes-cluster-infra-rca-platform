package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DnsLatencyDetector implements SignalDetector {
    @Override
    public String id() {
        return "dns-latency";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        context.number("dns", "latency").ifPresent(match -> {
            double latency = context.latencyMs(match.value());
            double threshold = context.thresholds().getDnsLatencyWarningMs();
            if (latency >= threshold) {
                signals.add(DetectorSupport.thresholdSignal(
                    "dns_latency_high",
                    "dns",
                    "warning",
                    new AnalysisContext.MatchedNumber(match.field(), latency),
                    threshold,
                    "DNS query latency exceeds the configured threshold.",
                    "Inspect resolv.conf, CoreDNS health, upstream latency, CNI path, and conntrack pressure.",
                    "dns", "network"
                ));
            }
        });
        return signals;
    }
}
