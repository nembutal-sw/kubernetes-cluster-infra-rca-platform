package io.clusterinfra.rca.webconsole.analysis.detector;

import org.springframework.stereotype.Component;

@Component
public class DnsConfigurationDetector extends AbstractStatusDetector {
    public DnsConfigurationDetector() {
        super(
            "dns-configuration",
            "dns",
            "dns_unconfigured",
            "dns",
            "Node resolver or cluster DNS configuration appears invalid.",
            "Inspect resolv.conf, CoreDNS endpoints, and upstream resolvers."
        );
    }
}
