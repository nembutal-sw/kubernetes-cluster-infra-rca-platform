package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final RcaConsoleProperties properties;

    public AuditService(AuditRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public AuditEvent user(
        UserAccount user,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return record(
            "user",
            user == null ? "unknown" : user.email(),
            eventType,
            resourceType,
            resourceId,
            outcome,
            details
        );
    }

    public AuditEvent user(
        UserAccount user,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details,
        HttpServletRequest request
    ) {
        return record(
            "user",
            user == null ? "unknown" : user.email(),
            eventType,
            resourceType,
            resourceId,
            outcome,
            withRequest(details, request)
        );
    }

    public AuditEvent system(
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        return record("system", actorId, eventType, resourceType, resourceId, outcome, details);
    }

    public AuditEvent record(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details,
        HttpServletRequest request
    ) {
        return record(actorType, actorId, eventType, resourceType, resourceId, outcome, withRequest(details, request));
    }

    public AuditEvent record(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        if (!properties.getAudit().isEnabled()) {
            return null;
        }
        return repository.save(
            actorType,
            actorId == null || actorId.isBlank() ? "unknown" : actorId,
            eventType,
            resourceType,
            resourceId,
            outcome,
            details == null ? Map.of() : details
        );
    }

    public Map<String, Object> withRequest(Map<String, Object> details, HttpServletRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        if (request == null) {
            return merged;
        }

        ClientAddress address = clientAddress(request);
        if (address.value() != null) {
            merged.put("client_ip", address.value());
            merged.put("client_ip_source", address.source());
        }
        putIfPresent(merged, "remote_addr", clean(request.getRemoteAddr(), 128));
        putIfPresent(merged, "method", clean(request.getMethod(), 16));
        putIfPresent(merged, "path", clean(request.getRequestURI(), 512));
        List<String> queryKeys = queryKeys(request);
        if (!queryKeys.isEmpty()) {
            merged.put("query_keys", queryKeys);
            merged.put("query_values_redacted", true);
        }
        putIfPresent(merged, "user_agent", clean(request.getHeader("User-Agent"), 512));
        putIfPresent(merged, "origin", clean(request.getHeader("Origin"), 256));
        putIfPresent(merged, "referer_path", refererPath(request.getHeader("Referer")));
        putIfPresent(merged, "request_id", clean(firstHeader(request, "X-Request-ID", "X-Correlation-ID"), 128));
        return merged;
    }

    private ClientAddress clientAddress(HttpServletRequest request) {
        String forwarded = clean(request.getHeader("Forwarded"), 512);
        String parsedForwarded = parseForwardedFor(forwarded);
        if (parsedForwarded != null) {
            return new ClientAddress(parsedForwarded, "Forwarded");
        }
        String xForwardedFor = clean(request.getHeader("X-Forwarded-For"), 512);
        if (xForwardedFor != null) {
            String first = firstForwardedAddress(xForwardedFor);
            if (first != null) {
                return new ClientAddress(first, "X-Forwarded-For");
            }
        }
        String realIp = clean(firstHeader(request, "X-Real-IP", "CF-Connecting-IP"), 128);
        if (realIp != null) {
            return new ClientAddress(realIp, "direct-header");
        }
        return new ClientAddress(clean(request.getRemoteAddr(), 128), "remote_addr");
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstForwardedAddress(String value) {
        for (String part : value.split(",")) {
            String candidate = clean(part, 128);
            if (candidate != null && !"unknown".equalsIgnoreCase(candidate)) {
                return stripAddressDecorations(candidate);
            }
        }
        return null;
    }

    private String parseForwardedFor(String forwarded) {
        if (forwarded == null) {
            return null;
        }
        for (String section : forwarded.split(",")) {
            for (String token : section.split(";")) {
                String trimmed = token.trim();
                if (trimmed.regionMatches(true, 0, "for=", 0, 4)) {
                    String value = clean(trimmed.substring(4), 128);
                    if (value != null && !"unknown".equalsIgnoreCase(value)) {
                        return stripAddressDecorations(value);
                    }
                }
            }
        }
        return null;
    }

    private String stripAddressDecorations(String value) {
        String result = value.trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 1) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.startsWith("[") && result.contains("]")) {
            return result.substring(1, result.indexOf(']'));
        }
        int colon = result.indexOf(':');
        if (colon > 0 && result.indexOf(':', colon + 1) < 0) {
            return result.substring(0, colon);
        }
        return result;
    }

    private List<String> queryKeys(HttpServletRequest request) {
        List<String> keys = new ArrayList<>();
        for (String key : request.getParameterMap().keySet()) {
            String cleaned = clean(key, 96);
            if (cleaned != null) {
                keys.add(cleaned);
            }
        }
        keys.sort(String::compareTo);
        return keys.stream().limit(40).toList();
    }

    private String refererPath(String referer) {
        String cleaned = clean(referer, 512);
        if (cleaned == null) {
            return null;
        }
        try {
            URI uri = new URI(cleaned);
            return clean(uri.getPath(), 512);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private record ClientAddress(String value, String source) {}
}
