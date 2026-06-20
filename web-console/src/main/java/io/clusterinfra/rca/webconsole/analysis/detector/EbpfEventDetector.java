package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EbpfEventDetector implements SignalDetector {
    @Override
    public String id() {
        return "ebpf-event";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        if (context.contains("\"event_type\":\"oom_kill\"")
            || context.contains("\"event_type\": \"oom_kill\"")) {
            signals.add(DetectorSupport.matchedSignal(
                "ebpf_oom_kill",
                "memory",
                "critical",
                "eBPF OOM kill event",
                List.of("ebpf.events[].event_type"),
                "The kernel OOM killer terminated a process.",
                "Correlate the killed process, cgroup memory limit, reclaim pressure, and node capacity.",
                "memory", "kernel", "ebpf"
            ));
        }
        if (context.contains("tcp_retransmit")) {
            signals.add(DetectorSupport.matchedSignal(
                "ebpf_tcp_retransmit",
                "network",
                "warning",
                "eBPF TCP retransmit event",
                List.of("ebpf.events[].event_type"),
                "TCP retransmissions indicate packet loss, congestion, or an unstable network path.",
                "Correlate source/destination flow, NIC counters, MTU, conntrack, and CNI path.",
                "network", "ebpf"
            ));
        }
        if (context.contains("\"event_type\":\"dns_timeout\"")
            || context.contains("\"event_type\": \"dns_timeout\"")) {
            signals.add(DetectorSupport.matchedSignal(
                "ebpf_dns_timeout",
                "dns",
                "critical",
                "eBPF DNS timeout event",
                List.of("ebpf.events[].event_type"),
                "A resolver request exceeded the configured latency threshold or timed out.",
                "Correlate resolver destination, CoreDNS/upstream latency, packet loss, MTU, and conntrack.",
                "dns", "network", "ebpf"
            ));
        }
        return signals;
    }
}
