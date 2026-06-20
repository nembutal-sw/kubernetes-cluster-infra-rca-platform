package io.clusterinfra.rca.webconsole.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvidenceFlattener {
    private EvidenceFlattener() {
    }

    public static Map<String, Object> flatten(Map<String, Object> collectors) {
        Map<String, Object> output = new LinkedHashMap<>();
        append("", collectors, output, 0);
        return output;
    }

    private static void append(String prefix, Object value, Map<String, Object> output, int depth) {
        if (value == null || depth > 10) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                String path = prefix.isBlank() ? String.valueOf(key) : prefix + "." + key;
                append(path, child, output, depth + 1);
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < Math.min(list.size(), 200); index++) {
                append(prefix + "[" + index + "]", list.get(index), output, depth + 1);
            }
            return;
        }
        output.put(prefix, value);
    }
}
