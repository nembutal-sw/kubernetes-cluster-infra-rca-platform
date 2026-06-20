package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NetworkLinkFlapDetector implements SignalDetector {
    private static final Pattern LINK_FLAP = Pattern.compile(
        "(link is down|link is up|nic link.*down|carrier.*lost|renamed from)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public String id() {
        return "network-link-flap";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        if (!LINK_FLAP.matcher(context.searchable()).find()) {
            return List.of();
        }
        return List.of(DetectorSupport.matchedSignal(
            "nic_link_flap",
            "network",
            "warning",
            "link state log match",
            List.of("network or kernel logs"),
            "NIC link state changed during the evidence window.",
            "Inspect carrier state, interface counters, driver logs, switch port events, and bonding.",
            "network", "kernel"
        ));
    }
}
