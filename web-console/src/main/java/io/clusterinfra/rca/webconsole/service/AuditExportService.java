package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.AuditSearchCriteria;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class AuditExportService {
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Set<String> EXPORT_FORMATS = Set.of("json", "csv");

    private final AuditRepository audits;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public AuditExportService(
        AuditRepository audits,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.audits = audits;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<AuditEvent> search(AuditSearchCriteria criteria, int maxLimit) {
        return audits.search(validate(criteria, maxLimit));
    }

    public AuditExport export(
        AuditSearchCriteria criteria,
        String format,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        AuditSearchCriteria safeCriteria = validate(criteria, 5000);
        String normalizedFormat = normalizeFormat(format);
        List<AuditEvent> events = audits.search(safeCriteria);
        audit.user(
            user,
            "audit.export",
            "audit_event",
            "bulk",
            "success",
            auditExportDetails(normalizedFormat, events.size(), safeCriteria),
            servletRequest
        );
        if ("csv".equals(normalizedFormat)) {
            return new AuditExport(
                csv(events).getBytes(StandardCharsets.UTF_8),
                "text/csv",
                filename("audit-events", "csv")
            );
        }
        return new AuditExport(
            json(Map.of(
                "schema_version", "1.0",
                "exported_at", Instant.now(),
                "event_count", events.size(),
                "events", events
            )),
            "application/json",
            filename("audit-events", "json")
        );
    }

    private AuditSearchCriteria validate(AuditSearchCriteria criteria, int maxLimit) {
        if (criteria.limit() < 1 || criteria.limit() > maxLimit) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be between 1 and " + maxLimit);
        }
        if (criteria.from() != null && criteria.to() != null && criteria.from().isAfter(criteria.to())) {
            throw new ResponseStatusException(BAD_REQUEST, "from must not be after to");
        }
        return criteria;
    }

    private String normalizeFormat(String format) {
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase();
        if (!EXPORT_FORMATS.contains(normalizedFormat)) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "format must be json or csv"
            );
        }
        return normalizedFormat;
    }

    private Map<String, Object> auditExportDetails(
        String format,
        int eventCount,
        AuditSearchCriteria criteria
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", format);
        details.put("event_count", eventCount);
        details.put("limit", criteria.limit());
        Map<String, Object> filters = auditFilterDetails(criteria);
        if (!filters.isEmpty()) {
            details.put("filters", filters);
        }
        return details;
    }

    private Map<String, Object> auditFilterDetails(AuditSearchCriteria criteria) {
        Map<String, Object> filters = new LinkedHashMap<>();
        putIfPresent(filters, "actor_type", criteria.actorType());
        putIfPresent(filters, "actor_id", criteria.actorId());
        putIfPresent(filters, "event_type", criteria.eventType());
        putIfPresent(filters, "resource_type", criteria.resourceType());
        putIfPresent(filters, "resource_id", criteria.resourceId());
        putIfPresent(filters, "outcome", criteria.outcome());
        putIfPresent(filters, "client_ip", criteria.clientIp());
        putIfPresent(filters, "q", criteria.query());
        if (criteria.from() != null) {
            filters.put("from", criteria.from().toString());
        }
        if (criteria.to() != null) {
            filters.put("to", criteria.to().toString());
        }
        return filters;
    }

    private void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }

    private byte[] json(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("audit export serialization failed", exception);
        }
    }

    private String csv(List<AuditEvent> events) {
        StringBuilder csv = new StringBuilder(
            "created_at,actor_type,actor_id,event_type,resource_type,resource_id,outcome,details\n"
        );
        for (AuditEvent event : events) {
            csv.append(csv(event.createdAt())).append(',')
                .append(csv(event.actorType())).append(',')
                .append(csv(event.actorId())).append(',')
                .append(csv(event.eventType())).append(',')
                .append(csv(event.resourceType())).append(',')
                .append(csv(event.resourceId())).append(',')
                .append(csv(event.outcome())).append(',')
                .append(csv(jsonText(event.details()))).append('\n');
        }
        return csv.toString();
    }

    private String jsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String filename(String prefix, String extension) {
        return prefix + "-" + FILE_TIME.format(Instant.now()) + "." + extension;
    }

    public record AuditExport(
        byte[] body,
        String mediaType,
        String filename
    ) {
    }
}
