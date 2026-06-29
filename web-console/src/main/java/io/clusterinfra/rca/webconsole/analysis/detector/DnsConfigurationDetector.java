package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DnsConfigurationDetector implements SignalDetector {
    @Override
    public String id() {
        return "dns-configuration";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        return dnsConfigurationFailure(context)
            .map(match -> List.of(DetectorSupport.matchedSignal(
            "dns_unconfigured",
            "dns",
                "critical",
                match.value(),
                List.of(match.field()),
            "Node resolver or cluster DNS configuration appears invalid.",
                "Inspect resolv.conf, CoreDNS endpoints, and upstream resolvers.",
                "dns"
            )))
            .orElseGet(List::of);
    }

    private Optional<AnalysisContext.MatchedValue> dnsConfigurationFailure(AnalysisContext context) {
        for (var entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if ((key.equals("dns.dns_configured") || key.equals("dns.resolv_conf_exists"))
                && Boolean.FALSE.equals(value)) {
                return Optional.of(new AnalysisContext.MatchedValue(entry.getKey(), value));
            }
            if (key.equals("dns.nameserver_count")
                && AnalysisContext.toDouble(value).isPresent()
                && AnalysisContext.toDouble(value).getAsDouble() <= 0) {
                return Optional.of(new AnalysisContext.MatchedValue(entry.getKey(), value));
            }
            if (key.equals("dns.status") && isFailureStatus(value)) {
                return Optional.of(new AnalysisContext.MatchedValue(entry.getKey(), value));
            }
        }
        return Optional.empty();
    }

    private boolean isFailureStatus(Object value) {
        String normalized = AnalysisContext.string(value).toLowerCase(Locale.ROOT);
        return normalized.equals("failed")
            || normalized.equals("unhealthy")
            || normalized.equals("down")
            || normalized.equals("error");
    }
}
