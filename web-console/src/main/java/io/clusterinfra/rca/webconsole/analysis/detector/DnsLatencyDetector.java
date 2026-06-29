package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
        dnsLatency(context).ifPresent(match -> {
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

    private Optional<AnalysisContext.MatchedNumber> dnsLatency(AnalysisContext context) {
        AnalysisContext.MatchedNumber best = null;
        for (var entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!isDnsLookupLatencyField(key)) {
                continue;
            }
            var number = AnalysisContext.toDouble(entry.getValue());
            if (number.isEmpty()) {
                continue;
            }
            AnalysisContext.MatchedNumber candidate =
                new AnalysisContext.MatchedNumber(entry.getKey(), number.getAsDouble());
            if (best == null || candidate.value() > best.value()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isDnsLookupLatencyField(String key) {
        return key.equals("dns.latency_ms")
            || key.equals("dns.dns_latency_ms")
            || key.equals("dns.dns_lookup_latency_ms")
            || key.equals("dns.lookup_latency_ms");
    }
}
