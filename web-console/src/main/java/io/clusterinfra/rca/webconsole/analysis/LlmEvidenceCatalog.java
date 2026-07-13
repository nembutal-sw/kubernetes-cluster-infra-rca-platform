package io.clusterinfra.rca.webconsole.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LlmEvidenceCatalog {
    private static final int ID_HASH_LENGTH = 16;

    private LlmEvidenceCatalog() {
    }

    public static List<Map<String, Object>> fromSignals(List<Signal> signals) {
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        if (signals == null) {
            return List.of();
        }
        for (Signal signal : signals) {
            if (signal == null) {
                continue;
            }
            List<String> evidencePaths = normalizedStrings(signal.matchedFields());
            String evidenceId = evidenceId(signal, evidencePaths);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("evidence_id", evidenceId);
            entry.put("signal", safe(signal.name()));
            entry.put("component", safe(signal.component()));
            entry.put("severity", safe(signal.severity()));
            entry.put("confidence", signal.confidence() == null ? "low" : signal.confidence().name());
            if (signal.observed() != null) {
                entry.put("observed", signal.observed());
            }
            if (signal.threshold() != null) {
                entry.put("threshold", signal.threshold());
            }
            entry.put("evidence_paths", evidencePaths);
            entry.put("supporting_evidence", normalizedStrings(signal.supportingEvidence()));
            entry.put("interpretation", safe(signal.interpretation()));
            catalog.putIfAbsent(evidenceId, Map.copyOf(entry));
        }
        return List.copyOf(catalog.values());
    }

    public static Map<String, Map<String, Object>> index(Object catalogValue) {
        if (!(catalogValue instanceof List<?> values)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) {
                continue;
            }
            String evidenceId = safe(raw.get("evidence_id"));
            if (evidenceId.isBlank()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (key != null && item != null) {
                    entry.put(String.valueOf(key), item);
                }
            });
            indexed.putIfAbsent(evidenceId, Map.copyOf(entry));
        }
        return Map.copyOf(indexed);
    }

    public static List<String> evidencePaths(
        List<String> evidenceIds,
        Map<String, Map<String, Object>> catalog
    ) {
        Set<String> paths = new LinkedHashSet<>();
        for (String evidenceId : evidenceIds) {
            Map<String, Object> entry = catalog.get(evidenceId);
            if (entry != null) {
                paths.addAll(normalizedStrings(entry.get("evidence_paths")));
            }
        }
        return List.copyOf(paths);
    }

    public static List<String> descriptions(
        List<String> evidenceIds,
        Map<String, Map<String, Object>> catalog
    ) {
        List<String> descriptions = new ArrayList<>();
        for (String evidenceId : evidenceIds) {
            Map<String, Object> entry = catalog.get(evidenceId);
            if (entry == null) {
                continue;
            }
            String signal = safe(entry.get("signal"));
            String interpretation = safe(entry.get("interpretation"));
            String description = evidenceId + (signal.isBlank() ? "" : " [" + signal + "]")
                + (interpretation.isBlank() ? "" : " " + interpretation);
            descriptions.add(description);
        }
        return List.copyOf(descriptions);
    }

    public static List<String> normalizedStrings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(LlmEvidenceCatalog::safe)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private static String evidenceId(Signal signal, List<String> evidencePaths) {
        String canonical = String.join("|",
            safe(signal.name()),
            safe(signal.component()),
            String.join(",", evidencePaths.stream().sorted().toList())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "ev-" + HexFormat.of().formatHex(digest).substring(0, ID_HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
