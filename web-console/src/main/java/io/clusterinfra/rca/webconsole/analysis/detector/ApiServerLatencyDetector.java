package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ApiServerLatencyDetector implements SignalDetector {
    @Override
    public String id() {
        return "api-server-latency";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        detectLatency(context).ifPresent(signals::add);
        detectReadyzFailure(context).ifPresent(signals::add);
        detectLivezFailure(context).ifPresent(signals::add);
        detectRequestErrors(context).ifPresent(signals::add);
        return signals;
    }

    private Optional<Signal> detectLatency(AnalysisContext context) {
        Optional<AnalysisContext.MatchedNumber> match = maxLatency(
            context,
            "kubernetes.api_server_latency_ms",
            "kubernetes.api_readyz_latency_ms",
            "kubernetes.api_livez_latency_ms",
            "kubernetes.apiserver_latency_ms",
            "kubernetes.api_server_request_latency_ms",
            "kubernetes.api_server_request_p99_ms"
        ).or(() -> maxApiRequestLatency(context));
        if (match.isEmpty()) {
            return Optional.empty();
        }
        double latency = context.latencyMs(match.get().value());
        double threshold = context.thresholds().getApiServerLatencyWarningMs();
        if (latency < threshold) {
            return Optional.empty();
        }
        return Optional.of(DetectorSupport.thresholdSignal(
            "api_server_latency_high",
            "api-server",
            "warning",
            new AnalysisContext.MatchedNumber(match.get().field(), latency),
            threshold,
            "Kubernetes API server latency exceeds the configured threshold.",
            "Correlate API latency with etcd, admission, control-plane CPU, and node network reachability.",
            "kubernetes", "network", "etcd"
        ));
    }

    private Optional<Signal> detectReadyzFailure(AnalysisContext context) {
        return positiveCount(context, "kubernetes.api_readyz_failed_check_count")
            .map(match -> DetectorSupport.matchedSignal(
                "api_server_readyz_failed",
                "api-server",
                "critical",
                match.value(),
                List.of(match.field()),
                "Kubernetes API server readyz checks are failing.",
                "Inspect /readyz?verbose output, especially etcd, post-start hooks, admission, and informer sync checks.",
                "kubernetes", "etcd"
            ));
    }

    private Optional<Signal> detectLivezFailure(AnalysisContext context) {
        return positiveCount(context, "kubernetes.api_livez_failed_check_count")
            .map(match -> DetectorSupport.matchedSignal(
                "api_server_livez_failed",
                "api-server",
                "critical",
                match.value(),
                List.of(match.field()),
                "Kubernetes API server livez checks are failing.",
                "Inspect API server process health, logs, and local control-plane resource pressure.",
                "kubernetes", "systemd", "kernel"
            ));
    }

    private Optional<Signal> detectRequestErrors(AnalysisContext context) {
        List<AnalysisContext.MatchedNumber> matches = new ArrayList<>();
        positiveCount(context, "kubernetes.api_request_error_count").ifPresent(matches::add);
        if (Boolean.TRUE.equals(context.flattened().get("kubernetes.api_timeout_detected"))) {
            matches.add(new AnalysisContext.MatchedNumber("kubernetes.api_timeout_detected", 1));
        }
        context.flattened().forEach((field, value) -> {
            String key = field.toLowerCase(Locale.ROOT);
            if (key.startsWith("kubernetes.api_request_latencies[")
                && key.endsWith(".ok")
                && Boolean.FALSE.equals(value)) {
                matches.add(new AnalysisContext.MatchedNumber(field, 1));
            }
        });
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        matches.forEach(match -> observed.put(match.field(), match.value()));
        return Optional.of(DetectorSupport.matchedSignal(
            "api_server_request_errors",
            "api-server",
            "warning",
            observed,
            matches.stream().map(AnalysisContext.MatchedNumber::field).toList(),
            "Kubernetes API requests from the node agent are failing or timing out.",
            "Correlate API reachability with node network, API server readiness, certificates, and RBAC/API aggregation errors.",
            "kubernetes", "network"
        ));
    }

    private Optional<AnalysisContext.MatchedNumber> maxLatency(AnalysisContext context, String... fields) {
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

    private Optional<AnalysisContext.MatchedNumber> maxApiRequestLatency(AnalysisContext context) {
        AnalysisContext.MatchedNumber best = null;
        for (Map.Entry<String, Object> entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.startsWith("kubernetes.api_request_latencies[") || !key.endsWith(".latency_ms")) {
                continue;
            }
            String requestPrefix = entry.getKey().substring(0, entry.getKey().length() - ".latency_ms".length());
            if (Boolean.FALSE.equals(context.flattened().get(requestPrefix + ".ok"))) {
                continue;
            }
            Optional<Double> number = number(entry.getValue());
            if (number.isEmpty()) {
                continue;
            }
            AnalysisContext.MatchedNumber candidate = new AnalysisContext.MatchedNumber(entry.getKey(), number.get());
            if (best == null || candidate.value() > best.value()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<AnalysisContext.MatchedNumber> positiveCount(AnalysisContext context, String field) {
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
}
