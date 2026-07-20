package io.clusterinfra.rca.webconsole.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    private static final String REDACTED = "[redacted]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "token",
        "access_token",
        "refresh_token",
        "node_token",
        "agent_token",
        "password",
        "passwd",
        "secret",
        "authorization",
        "api_key",
        "apikey",
        "cookie",
        "set_cookie"
        ,"client_certificate_data"
        ,"client_key_data"
        ,"certificate_authority_data"
    );
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)(api[_-]?key|authorization|token|password|passwd|secret|cookie)(\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern BEARER =
        Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]+");
    private static final Pattern OPENAI_KEY =
        Pattern.compile("sk-[a-zA-Z0-9_-]{8,}");
    private static final Pattern GOOGLE_API_KEY =
        Pattern.compile("(?:AIza[a-zA-Z0-9_-]{30,}|AQ\\.[a-zA-Z0-9_-]{30,})");
    private static final Pattern GITHUB_TOKEN =
        Pattern.compile("gh[pousr]_[a-zA-Z0-9]{20,}");
    private static final Pattern SLACK_TOKEN =
        Pattern.compile("xox[baprs]-[a-zA-Z0-9-]{10,}");
    private static final Pattern AWS_ACCESS_KEY =
        Pattern.compile("(?:AKIA|ASIA)[A-Z0-9]{16}");
    private static final Pattern CREDENTIAL_URL =
        Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^\\s:/]+:)[^@\\s]+(@)");

    private SensitiveDataRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        return SENSITIVE_KEYS.contains(normalized)
            || normalized.endsWith("_token")
            || normalized.endsWith("_password")
            || normalized.endsWith("_secret")
            || normalized.endsWith("_api_key");
    }

    public static String redactText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = ASSIGNMENT.matcher(value).replaceAll("$1$2" + REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        redacted = OPENAI_KEY.matcher(redacted).replaceAll("sk-" + REDACTED);
        redacted = GOOGLE_API_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = GITHUB_TOKEN.matcher(redacted).replaceAll("gh_" + REDACTED);
        redacted = SLACK_TOKEN.matcher(redacted).replaceAll("xox-" + REDACTED);
        redacted = AWS_ACCESS_KEY.matcher(redacted).replaceAll(REDACTED);
        return CREDENTIAL_URL.matcher(redacted).replaceAll("$1" + REDACTED + "$2");
    }

    public static Map<String, Object> redactMap(Map<String, ?> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) redactValue(value);
        return redacted;
    }

    public static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                redacted.put(textKey, isSensitiveKey(textKey) ? REDACTED : redactValue(item));
            });
            return redacted;
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            list.forEach(item -> redacted.add(redactValue(item)));
            return redacted;
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        return value;
    }
}
