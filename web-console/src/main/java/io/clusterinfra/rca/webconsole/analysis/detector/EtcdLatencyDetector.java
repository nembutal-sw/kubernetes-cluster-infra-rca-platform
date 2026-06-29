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
public class EtcdLatencyDetector implements SignalDetector {
    @Override
    public String id() {
        return "etcd-latency";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        detectLatency(context).ifPresent(signals::add);
        detectReadyzFailure(context).ifPresent(signals::add);
        detectPodUnhealthy(context).ifPresent(signals::add);
        return signals;
    }

    private Optional<Signal> detectLatency(AnalysisContext context) {
        Optional<AnalysisContext.MatchedNumber> match = maxNumber(
            context,
            "etcd.latency_ms",
            "etcd.request_latency_ms",
            "etcd.apply_latency_ms",
            "etcd.commit_latency_ms",
            "etcd.fsync_latency_ms",
            "etcd.wal_fsync_latency_ms",
            "etcd.peer_round_trip_time_ms",
            "kubernetes.etcd_latency_ms",
            "kubernetes.etcd_fsync_latency_ms",
            "kubernetes.etcd_request_latency_ms"
        );
        if (match.isEmpty()) {
            match = context.number("etcd", "latency");
        }
        if (match.isEmpty()) {
            return Optional.empty();
        }
        double latency = context.latencyMs(match.get().value());
        double threshold = context.thresholds().getEtcdLatencyWarningMs();
        if (latency < threshold) {
            return Optional.empty();
        }
        return Optional.of(DetectorSupport.thresholdSignal(
            "etcd_latency_high",
            "etcd",
            "critical",
            new AnalysisContext.MatchedNumber(match.get().field(), latency),
            threshold,
            interpretationFor(match.get().field()),
            "Inspect etcd endpoint health, fsync latency, peer network latency, leader changes, and quorum state.",
            "kubernetes", "disk", "network"
        ));
    }

    private Optional<Signal> detectReadyzFailure(AnalysisContext context) {
        Object healthy = context.flattened().get("kubernetes.etcd_readyz_healthy");
        if (Boolean.FALSE.equals(healthy) || "false".equalsIgnoreCase(String.valueOf(healthy))) {
            return Optional.of(DetectorSupport.matchedSignal(
                "etcd_readyz_failed",
                "etcd",
                "critical",
                healthy,
                List.of("kubernetes.etcd_readyz_healthy"),
                "API server readyz reports the etcd dependency as unhealthy.",
                "Check etcd endpoint health, quorum, leader changes, backend DB size, disk latency, and API server logs.",
                "kubernetes", "etcd"
            ));
        }
        for (String field : context.flattened().keySet()) {
            String key = field.toLowerCase(Locale.ROOT);
            if (key.startsWith("kubernetes.api_readyz_failed_checks[")
                && String.valueOf(context.flattened().get(field)).toLowerCase(Locale.ROOT).contains("etcd")) {
                return Optional.of(DetectorSupport.matchedSignal(
                    "etcd_readyz_failed",
                    "etcd",
                    "critical",
                    context.flattened().get(field),
                    List.of(field),
                    "API server readyz reports the etcd dependency as unhealthy.",
                    "Check etcd endpoint health, quorum, leader changes, backend DB size, disk latency, and API server logs.",
                    "kubernetes", "etcd"
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<Signal> detectPodUnhealthy(AnalysisContext context) {
        List<String> fields = new ArrayList<>();
        positiveNumber(context, "kubernetes.etcd_non_running_pods[0].restart_count").ifPresent(match -> fields.add(match.field()));
        positiveNumber(context, "kubernetes.etcd_high_restart_pods[0].restart_count").ifPresent(match -> fields.add(match.field()));
        positiveNumber(context, "kubernetes.etcd_non_running_pods[0].phase").ifPresent(match -> fields.add(match.field()));
        if (fields.isEmpty()) {
            boolean hasNonRunning = context.flattened().keySet().stream()
                .anyMatch(field -> field.startsWith("kubernetes.etcd_non_running_pods["));
            boolean hasHighRestart = context.flattened().keySet().stream()
                .anyMatch(field -> field.startsWith("kubernetes.etcd_high_restart_pods["));
            if (!hasNonRunning && !hasHighRestart) {
                return Optional.empty();
            }
            if (hasNonRunning) {
                fields.add("kubernetes.etcd_non_running_pods");
            }
            if (hasHighRestart) {
                fields.add("kubernetes.etcd_high_restart_pods");
            }
        }
        return Optional.of(DetectorSupport.matchedSignal(
            "etcd_pod_unhealthy",
            "etcd",
            "critical",
            "etcd pod is non-running or restarting",
            fields,
            "Etcd pod health on the observed node is degraded.",
            "Inspect the etcd static pod, kubelet logs, disk latency, certificates, and quorum before making changes.",
            "kubernetes", "kubelet", "disk"
        ));
    }

    private Optional<AnalysisContext.MatchedNumber> maxNumber(AnalysisContext context, String... fields) {
        AnalysisContext.MatchedNumber best = null;
        for (String field : fields) {
            Optional<Double> number = number(context.flattened().get(field));
            if (number.isEmpty()) {
                continue;
            }
            AnalysisContext.MatchedNumber candidate = new AnalysisContext.MatchedNumber(field, number.get());
            if (best == null || candidate.value() > best.value()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<AnalysisContext.MatchedNumber> positiveNumber(AnalysisContext context, String field) {
        Optional<Double> number = number(context.flattened().get(field));
        if (number.isEmpty() || number.get() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AnalysisContext.MatchedNumber(field, number.get()));
    }

    private Optional<Double> number(Object value) {
        var parsed = AnalysisContext.toDouble(value);
        return parsed.isPresent() ? Optional.of(parsed.getAsDouble()) : Optional.empty();
    }

    private String interpretationFor(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        if (normalized.contains("fsync") || normalized.contains("wal")) {
            return "Etcd fsync latency is high enough to affect control-plane writes.";
        }
        if (normalized.contains("peer")) {
            return "Etcd peer round-trip latency is high enough to affect quorum operations.";
        }
        return "Etcd request latency is high enough to affect control-plane responsiveness.";
    }
}
