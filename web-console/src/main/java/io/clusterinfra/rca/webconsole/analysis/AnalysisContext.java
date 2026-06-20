package io.clusterinfra.rca.webconsole.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public record AnalysisContext(
    Map<String, Object> collectors,
    Map<String, Object> flattened,
    String searchable,
    RcaConsoleProperties.Thresholds thresholds
) {
    public static AnalysisContext create(
        Map<String, Object> collectors,
        RcaConsoleProperties.Thresholds thresholds,
        ObjectMapper objectMapper
    ) {
        Map<String, Object> safeCollectors = collectors == null ? Map.of() : collectors;
        return new AnalysisContext(
            safeCollectors,
            EvidenceFlattener.flatten(safeCollectors),
            safeJson(objectMapper, safeCollectors),
            thresholds
        );
    }

    public Optional<MatchedNumber> percentage(String... fragments) {
        return number(fragments).map(item -> {
            double value = item.value();
            if (value <= 1.0) {
                value *= 100;
            }
            return new MatchedNumber(item.field(), Math.max(0, Math.min(value, 100)));
        });
    }

    public Optional<MatchedNumber> number(String... fragments) {
        MatchedNumber best = null;
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String fragment : fragments) {
                if (!key.contains(fragment.toLowerCase(Locale.ROOT))) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            OptionalDouble value = toDouble(entry.getValue());
            if (value.isPresent()) {
                MatchedNumber candidate = new MatchedNumber(entry.getKey(), value.getAsDouble());
                if (best == null || candidate.value() > best.value()) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<MatchedNumber> ratio(List<String> numerator, List<String> denominator) {
        Optional<MatchedNumber> left = number(numerator.toArray(String[]::new));
        Optional<MatchedNumber> right = number(denominator.toArray(String[]::new));
        if (left.isEmpty() || right.isEmpty() || right.get().value() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MatchedNumber(
            left.get().field() + " / " + right.get().field(),
            Math.max(0, Math.min(left.get().value() / right.get().value() * 100, 100))
        ));
    }

    public Optional<MatchedValue> status(String fragment) {
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.contains(fragment.toLowerCase(Locale.ROOT))
                || !(key.contains("status") || key.contains("state") || key.contains("healthy")
                || key.contains("ready") || key.contains("active") || key.contains("ok"))) {
                continue;
            }
            String value = string(entry.getValue()).toLowerCase(Locale.ROOT);
            if (isExplicitlyUnhealthy(value, entry.getValue())) {
                return Optional.of(new MatchedValue(entry.getKey(), entry.getValue()));
            }
        }
        return Optional.empty();
    }

    public boolean contains(String value) {
        return searchable.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    public double latencyMs(double value) {
        return value > 0 && value < 10 ? value * 1000 : value;
    }

    public static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static OptionalDouble toDouble(Object value) {
        if (value instanceof Number number) {
            return OptionalDouble.of(number.doubleValue());
        }
        try {
            return OptionalDouble.of(Double.parseDouble(string(value).replace("%", "").trim()));
        } catch (NumberFormatException exception) {
            return OptionalDouble.empty();
        }
    }

    private static boolean isExplicitlyUnhealthy(String value, Object rawValue) {
        if (Boolean.FALSE.equals(rawValue)) {
            return true;
        }
        return value.equals("false")
            || value.equals("failed")
            || value.equals("inactive")
            || value.equals("unhealthy")
            || value.equals("notready")
            || value.equals("not_ready")
            || value.equals("down")
            || value.equals("error");
    }

    private static String safeJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    public record MatchedNumber(String field, double value) {
    }

    public record MatchedValue(String field, Object value) {
    }
}
