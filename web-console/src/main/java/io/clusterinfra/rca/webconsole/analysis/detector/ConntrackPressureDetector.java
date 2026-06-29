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
public class ConntrackPressureDetector implements SignalDetector {
    @Override
    public String id() {
        return "conntrack-pressure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        detectTableFullLog(context).ifPresent(signals::add);
        detectInsertFailures(context).ifPresent(signals::add);
        detectPacketDrops(context).ifPresent(signals::add);
        detectNearLimit(context).ifPresent(signals::add);
        return signals;
    }

    private Optional<Signal> detectTableFullLog(AnalysisContext context) {
        Optional<AnalysisContext.MatchedNumber> explicit = firstPositiveNumber(
            context,
            "conntrack.table_full_detected",
            "network.conntrack.table_full_detected"
        );
        if (explicit.isPresent()) {
            AnalysisContext.MatchedNumber match = explicit.get();
            return Optional.of(DetectorSupport.matchedSignal(
                "conntrack_table_full",
                "conntrack",
                "critical",
                match.value(),
                List.of(match.field()),
                "Kernel logs indicate the conntrack table became full and packets were dropped.",
                "Inspect nf_conntrack usage, failed inserts, drops, traffic churn, and reviewed sysctl sizing.",
                "conntrack", "kernel", "network"
            ));
        }
        String searchable = context.searchable().toLowerCase(Locale.ROOT);
        if (searchable.contains("nf_conntrack: table full") || searchable.contains("conntrack table full")) {
            return Optional.of(DetectorSupport.matchedSignal(
                "conntrack_table_full",
                "conntrack",
                "critical",
                "conntrack table full log match",
                List.of("kernel.messages"),
                "Kernel logs indicate the conntrack table became full and packets were dropped.",
                "Inspect nf_conntrack usage, failed inserts, drops, traffic churn, and reviewed sysctl sizing.",
                "conntrack", "kernel", "network"
            ));
        }
        return Optional.empty();
    }

    private Optional<Signal> detectInsertFailures(AnalysisContext context) {
        return maxNumber(
            context,
            "conntrack.insert_failed",
            "conntrack.stats.insert_failed",
            "network.conntrack_insert_failed",
            "network.conntrack.insert_failed",
            "network.conntrack.stats.insert_failed"
        ).filter(match -> match.value() > 0).map(match -> DetectorSupport.matchedSignal(
            "conntrack_insert_failures",
            "conntrack",
            "critical",
            match.value(),
            List.of(match.field()),
            "Conntrack insert failures were observed, which can drop new connections.",
            "Inspect nf_conntrack table occupancy, hash buckets, connection churn, and reviewed sizing.",
            "conntrack", "network"
        ));
    }

    private Optional<Signal> detectPacketDrops(AnalysisContext context) {
        List<AnalysisContext.MatchedNumber> matches = new ArrayList<>();
        maxNumber(
            context,
            "conntrack.drop",
            "conntrack.stats.drop",
            "network.conntrack_drop_total",
            "network.conntrack.drop",
            "network.conntrack.stats.drop"
        ).filter(match -> match.value() > 0).ifPresent(matches::add);
        maxNumber(
            context,
            "conntrack.early_drop",
            "conntrack.stats.early_drop",
            "network.conntrack_early_drop_total",
            "network.conntrack.early_drop",
            "network.conntrack.stats.early_drop"
        ).filter(match -> match.value() > 0).ifPresent(matches::add);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        double total = matches.stream().mapToDouble(AnalysisContext.MatchedNumber::value).sum();
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("drop_total", total);
        matches.forEach(match -> observed.put(match.field(), match.value()));
        return Optional.of(DetectorSupport.matchedSignal(
            "conntrack_packet_drops",
            "conntrack",
            "critical",
            observed,
            matches.stream().map(AnalysisContext.MatchedNumber::field).toList(),
            "Conntrack drop counters increased, indicating packet loss at connection tracking.",
            "Inspect conntrack stats, NAT/service connection churn, DNS failures, and CNI traffic path.",
            "conntrack", "network"
        ));
    }

    private Optional<Signal> detectNearLimit(AnalysisContext context) {
        Optional<AnalysisContext.MatchedNumber> usage = usagePercent(context);
        if (usage.isEmpty()) {
            return Optional.empty();
        }
        AnalysisContext.MatchedNumber match = usage.get();
        double warning = context.thresholds().getConntrackWarningPercent();
        double critical = context.thresholds().getConntrackCriticalPercent();
        if (match.value() < warning) {
            return Optional.empty();
        }
        boolean severe = match.value() >= critical;
        return Optional.of(DetectorSupport.thresholdSignal(
            "conntrack_near_limit",
            "conntrack",
            severe ? "critical" : "warning",
            match,
            severe ? critical : warning,
            "Conntrack table occupancy is close to its configured limit.",
            "Inspect conntrack statistics, connection churn, drops, and reviewed sizing.",
            "conntrack", "network"
        ));
    }

    private Optional<AnalysisContext.MatchedNumber> usagePercent(AnalysisContext context) {
        Optional<AnalysisContext.MatchedNumber> explicit = maxNumber(
            context,
            "conntrack.usage_percent",
            "network.conntrack_usage_percent",
            "network.conntrack.usage_percent"
        );
        if (explicit.isPresent()) {
            return explicit;
        }
        Optional<AnalysisContext.MatchedNumber> count = maxNumber(
            context,
            "conntrack.count",
            "network.conntrack.count"
        );
        Optional<AnalysisContext.MatchedNumber> maximum = maxNumber(
            context,
            "conntrack.max",
            "network.conntrack.max"
        );
        if (count.isEmpty() || maximum.isEmpty() || maximum.get().value() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AnalysisContext.MatchedNumber(
            count.get().field() + " / " + maximum.get().field(),
            Math.max(0, Math.min(count.get().value() / maximum.get().value() * 100, 100))
        ));
    }

    private Optional<AnalysisContext.MatchedNumber> firstPositiveNumber(AnalysisContext context, String... fields) {
        for (String field : fields) {
            Object value = context.flattened().get(field);
            if (Boolean.TRUE.equals(value)) {
                return Optional.of(new AnalysisContext.MatchedNumber(field, 1));
            }
            Optional<Double> number = number(value);
            if (number.isPresent() && number.get() > 0) {
                return Optional.of(new AnalysisContext.MatchedNumber(field, number.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<AnalysisContext.MatchedNumber> maxNumber(AnalysisContext context, String... fields) {
        AnalysisContext.MatchedNumber best = null;
        for (String field : fields) {
            Optional<Double> number = number(context.flattened().get(field));
            if (number.isPresent()) {
                AnalysisContext.MatchedNumber candidate =
                    new AnalysisContext.MatchedNumber(field, number.get());
                if (best == null || candidate.value() > best.value()) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Double> number(Object value) {
        var parsed = AnalysisContext.toDouble(value);
        return parsed.isPresent() ? Optional.of(parsed.getAsDouble()) : Optional.empty();
    }
}
