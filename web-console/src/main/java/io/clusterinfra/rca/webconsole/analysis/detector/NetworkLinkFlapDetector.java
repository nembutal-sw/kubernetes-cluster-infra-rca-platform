package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
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
        List<String> fields = new ArrayList<>();
        fields.add("kernel.messages");
        context.number("carrier", "changes")
            .ifPresent(match -> fields.add(match.field()));
        context.number("rx", "dropped")
            .ifPresent(match -> fields.add(match.field()));
        context.number("tx", "errors")
            .ifPresent(match -> fields.add(match.field()));
        return List.of(DetectorSupport.matchedSignal(
            "nic_link_flap",
            "network",
            "warning",
            "link state log match",
            fields.stream().distinct().toList(),
            "NIC link state changed during the evidence window.",
            "Inspect carrier state, interface counters, driver logs, switch port events, and bonding.",
            "network", "kernel"
        ));
    }
}
