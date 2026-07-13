package io.clusterinfra.rca.webconsole.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CollectorEvidenceAdapter {
    public static final String CONTRACT_VERSION = "collector-evidence/v1";
    private static final Set<String> TYPES = Set.of(
        "number", "integer", "boolean", "string", "text", "array", "object"
    );
    private final SchemaDocument document;

    public CollectorEvidenceAdapter(ObjectMapper objectMapper) {
        this.document = load(objectMapper);
        validate(document);
    }

    public AdaptationResult adapt(Map<String, Object> collectors) {
        Map<String, Object> source = collectors == null ? Map.of() : collectors;
        Map<String, Object> normalized = new LinkedHashMap<>(source);
        Map<String, CollectorContractResult> results = new LinkedHashMap<>();

        source.forEach((collectorName, rawValue) -> {
            CollectorSchema schema = document.collectors().get(collectorName);
            if (schema == null) {
                if (!collectorName.startsWith("_")) {
                    results.put(collectorName, CollectorContractResult.unknown());
                }
                return;
            }
            if (!(rawValue instanceof Map<?, ?> rawMap)) {
                results.put(collectorName, new CollectorContractResult(
                    schema.version(), "invalid", 0, List.of(), List.of("collector payload must be an object")
                ));
                return;
            }

            Map<String, Object> collector = stringMap(rawMap);
            List<String> missing = new ArrayList<>();
            List<String> invalid = new ArrayList<>();
            int matched = 0;
            for (Map.Entry<String, FieldSchema> entry : schema.fields().entrySet()) {
                String canonical = entry.getKey();
                FieldSchema field = entry.getValue();
                LocatedValue located = locate(collector, canonical, field.aliases());
                if (!located.present()) {
                    if (field.required()) {
                        missing.add(canonical);
                    }
                    continue;
                }
                Conversion converted = convert(located.value(), field.type());
                if (!converted.valid()) {
                    invalid.add(canonical + " expected " + field.type() + " at " + located.path());
                    continue;
                }
                matched++;
                collector.put(canonical, converted.value());
            }
            normalized.put(collectorName, Collections.unmodifiableMap(collector));
            String status = invalid.isEmpty() ? (missing.isEmpty() ? "valid" : "partial") : "invalid";
            results.put(collectorName, new CollectorContractResult(
                schema.version(), status, matched, List.copyOf(missing), List.copyOf(invalid)
            ));
        });

        return new AdaptationResult(
            Collections.unmodifiableMap(normalized),
            contractSummary(results)
        );
    }

    public Map<String, Object> schemas() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", document.schemaVersion());
        result.put("collector_count", document.collectors().size());
        result.put("collectors", document.collectors());
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> contractSummary(Map<String, CollectorContractResult> results) {
        long invalid = results.values().stream().filter(item -> "invalid".equals(item.status())).count();
        long partial = results.values().stream().filter(item -> "partial".equals(item.status())).count();
        long unknown = results.values().stream().filter(item -> "unknown".equals(item.status())).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", document.schemaVersion());
        summary.put("status", invalid > 0 ? "invalid" : partial > 0 || unknown > 0 ? "partial" : "valid");
        summary.put("validated_collector_count", results.size() - unknown);
        summary.put("invalid_collector_count", invalid);
        summary.put("partial_collector_count", partial);
        summary.put("unknown_collector_count", unknown);
        summary.put("collectors", Collections.unmodifiableMap(new LinkedHashMap<>(results)));
        return Collections.unmodifiableMap(summary);
    }

    private static LocatedValue locate(Map<String, Object> collector, String canonical, List<String> aliases) {
        for (String path : joined(canonical, aliases)) {
            Object value = valueAt(collector, path);
            if (value != MissingValue.INSTANCE && value != null) {
                return new LocatedValue(true, path, value);
            }
        }
        return new LocatedValue(false, canonical, null);
    }

    private static List<String> joined(String canonical, List<String> aliases) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(canonical);
        if (aliases != null) {
            paths.addAll(aliases);
        }
        return List.copyOf(paths);
    }

    private static Object valueAt(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return MissingValue.INSTANCE;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static Conversion convert(Object value, String type) {
        return switch (type) {
            case "number" -> number(value, false);
            case "integer" -> number(value, true);
            case "boolean" -> bool(value);
            case "string" -> value instanceof String ? Conversion.valid(value) : Conversion.invalid();
            case "text" -> value instanceof String || value instanceof List<?> ? Conversion.valid(value) : Conversion.invalid();
            case "array" -> value instanceof List<?> ? Conversion.valid(value) : Conversion.invalid();
            case "object" -> value instanceof Map<?, ?> ? Conversion.valid(value) : Conversion.invalid();
            default -> Conversion.invalid();
        };
    }

    private static Conversion number(Object value, boolean integer) {
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                parsed = Double.parseDouble(text.replace("%", "").trim());
            } catch (NumberFormatException exception) {
                return Conversion.invalid();
            }
        } else {
            return Conversion.invalid();
        }
        if (!Double.isFinite(parsed) || integer && parsed != Math.rint(parsed)) {
            return Conversion.invalid();
        }
        return Conversion.valid(integer ? (long) parsed : parsed);
    }

    private static Conversion bool(Object value) {
        if (value instanceof Boolean) {
            return Conversion.valid(value);
        }
        if (value instanceof Number number && (number.intValue() == 0 || number.intValue() == 1)) {
            return Conversion.valid(number.intValue() == 1);
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes" -> Conversion.valid(true);
                case "false", "0", "no" -> Conversion.valid(false);
                default -> Conversion.invalid();
            };
        }
        return Conversion.invalid();
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static SchemaDocument load(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(
                new ClassPathResource("evidence/collector-evidence-schemas.json").getInputStream(),
                SchemaDocument.class
            );
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load collector evidence schemas", exception);
        }
    }

    private static void validate(SchemaDocument value) {
        if (value == null || !CONTRACT_VERSION.equals(value.schemaVersion()) || value.collectors().isEmpty()) {
            throw new IllegalStateException("collector evidence schema document is missing or unsupported");
        }
        Set<String> aliases = new LinkedHashSet<>();
        value.collectors().forEach((collector, schema) -> {
            if (collector.isBlank() || schema.version().isBlank() || schema.fields().isEmpty()) {
                throw new IllegalStateException("collector schema is incomplete: " + collector);
            }
            schema.fields().forEach((field, definition) -> {
                if (field.isBlank() || !TYPES.contains(definition.type())) {
                    throw new IllegalStateException("unsupported evidence field type: " + collector + "." + field);
                }
                aliases.clear();
                for (String alias : definition.aliases()) {
                    if (alias.isBlank() || field.equals(alias) || !aliases.add(alias)) {
                        throw new IllegalStateException("invalid evidence alias: " + collector + "." + field);
                    }
                }
            });
        });
    }

    public record AdaptationResult(Map<String, Object> collectors, Map<String, Object> contract) {
    }

    public record CollectorContractResult(
        String schemaVersion,
        String status,
        int matchedFieldCount,
        List<String> missingRequiredFields,
        List<String> invalidFields
    ) {
        static CollectorContractResult unknown() {
            return new CollectorContractResult("unregistered", "unknown", 0, List.of(), List.of());
        }
    }

    public record SchemaDocument(
        @JsonProperty("schema_version") String schemaVersion,
        Map<String, CollectorSchema> collectors
    ) {
        public SchemaDocument {
            collectors = collectors == null ? Map.of() : Map.copyOf(collectors);
        }
    }

    public record CollectorSchema(String version, Map<String, FieldSchema> fields) {
        public CollectorSchema {
            version = version == null ? "" : version;
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record FieldSchema(String type, List<String> aliases, boolean required, String unit) {
        public FieldSchema {
            type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    private record LocatedValue(boolean present, String path, Object value) {
    }

    private record Conversion(boolean valid, Object value) {
        static Conversion valid(Object value) {
            return new Conversion(true, value);
        }

        static Conversion invalid() {
            return new Conversion(false, null);
        }
    }

    private enum MissingValue {
        INSTANCE
    }
}
