package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConfidenceScorer {
    public Confidence reportConfidence(List<Signal> signals) {
        if (signals.isEmpty()) {
            return Confidence.low;
        }
        if (signals.stream().anyMatch(signal -> signal.confidence() == Confidence.high)) {
            return Confidence.high;
        }
        return Confidence.medium;
    }

    public int candidateScore(Signal signal, int sameComponentCount) {
        int score = "critical".equalsIgnoreCase(signal.severity()) ? 30 : 15;
        score += switch (signal.confidence()) {
            case high -> 25;
            case medium -> 15;
            case low -> 5;
        };
        if (sameComponentCount > 1) {
            score += 20;
        }
        if (signal.threshold() != null) {
            score += 20;
        }
        long independentSources = signal.matchedFields().stream()
            .map(ConfidenceScorer::source)
            .distinct()
            .count();
        if (independentSources > 1) {
            score += 20;
        }
        if (isExplicitFailureState(signal)) {
            score += 15;
        }
        if (isExplicitConfigurationOrEvent(signal)) {
            score += 15;
        }
        if (!signal.supportingEvidence().isEmpty()) {
            score += 10;
        }
        return Math.max(0, Math.min(100, score));
    }

    private static boolean isExplicitFailureState(Signal signal) {
        String name = signal.name();
        return name.endsWith("_unit_unhealthy")
            || name.endsWith("_readyz_failed")
            || name.endsWith("_livez_failed")
            || name.endsWith("_pod_unhealthy")
            || name.endsWith("_pod_not_running")
            || name.endsWith("_not_scheduled")
            || name.endsWith("_unavailable")
            || name.equals("node_not_ready")
            || name.equals("systemd_failed_units")
            || name.equals("container_runtime_unit_unhealthy");
    }

    private static boolean isExplicitConfigurationOrEvent(Signal signal) {
        String name = signal.name();
        return name.equals("cni_config_invalid")
            || name.equals("cni_mtu_values_inconsistent")
            || name.equals("dns_unconfigured")
            || name.equals("conntrack_table_full")
            || name.equals("kernel_io_error")
            || name.equals("root_filesystem_read_only")
            || name.equals("nic_link_flap")
            || name.startsWith("ebpf_");
    }

    private static String source(String path) {
        int separator = path == null ? -1 : path.indexOf('.');
        return separator <= 0 ? String.valueOf(path) : path.substring(0, separator);
    }
}
