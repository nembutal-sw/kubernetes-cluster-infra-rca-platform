package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.AuditRepository;
import io.clusterinfra.rca.webconsole.persistence.AuditSearchCriteria;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class AuditExportServiceTests {
    private static final Instant NOW = Instant.parse("2026-06-21T04:00:00Z");
    private static final UserAccount USER = new UserAccount(
        "user-1",
        "auditor@example.com",
        "Auditor",
        UserRole.auditor,
        UserRole.auditor,
        UserStatus.active,
        null,
        null,
        null,
        NOW,
        NOW
    );

    private AuditRepository audits;
    private AuditService audit;
    private ObjectMapper objectMapper;
    private AuditExportService service;

    @BeforeEach
    void setUp() {
        audits = mock(AuditRepository.class);
        audit = mock(AuditService.class);
        objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        service = new AuditExportService(audits, audit, objectMapper);
    }

    @Test
    void searchValidatesLimitAndDateRange() {
        AuditSearchCriteria tooLarge = criteria(null, null, 1001);
        assertThatThrownBy(() -> service.search(tooLarge, 1000))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        AuditSearchCriteria invertedRange = new AuditSearchCriteria(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW.plusSeconds(60),
            NOW,
            100
        );
        assertThatThrownBy(() -> service.search(invertedRange, 1000))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exportJsonIncludesEventsAndAuditDetails() throws Exception {
        AuditSearchCriteria criteria = new AuditSearchCriteria(
            " user ",
            null,
            "auth.login",
            null,
            null,
            "success",
            "203.0.113.10",
            " PlatformHttpTests ",
            NOW.minusSeconds(60),
            NOW,
            100
        );
        AuditEvent event = event("audit-1", "user", "admin", "auth.login", Map.of("client_ip", "203.0.113.10"));
        when(audits.search(criteria)).thenReturn(List.of(event));

        var export = service.export(criteria, "json", USER, new MockHttpServletRequest());

        JsonNode payload = objectMapper.readTree(new String(export.body(), StandardCharsets.UTF_8));
        assertThat(payload.path("schema_version").asText()).isEqualTo("1.0");
        assertThat(payload.path("event_count").asInt()).isEqualTo(1);
        assertThat(payload.path("events").get(0).path("audit_event_id").asText()).isEqualTo("audit-1");
        assertThat(export.mediaType()).isEqualTo("application/json");
        assertThat(export.filename()).startsWith("audit-events-").endsWith(".json");
        verify(audit).user(
            eq(USER),
            eq("audit.export"),
            eq("audit_event"),
            eq("bulk"),
            eq("success"),
            eq(Map.of(
                "format", "json",
                "event_count", 1,
                "limit", 100,
                "filters", Map.of(
                    "actor_type", "user",
                    "event_type", "auth.login",
                    "outcome", "success",
                    "client_ip", "203.0.113.10",
                    "q", "PlatformHttpTests",
                    "from", NOW.minusSeconds(60).toString(),
                    "to", NOW.toString()
                )
            )),
            any()
        );
    }

    @Test
    void exportCsvQuotesValuesAndSerializesDetails() {
        AuditSearchCriteria criteria = criteria(null, null, 100);
        when(audits.search(criteria)).thenReturn(List.of(
            event("audit-1", "user", "admin,one", "auth.login", Map.of("message", "hello \"world\""))
        ));

        var export = service.export(criteria, "csv", USER, new MockHttpServletRequest());

        String body = new String(export.body(), StandardCharsets.UTF_8);
        assertThat(export.mediaType()).isEqualTo("text/csv");
        assertThat(export.filename()).startsWith("audit-events-").endsWith(".csv");
        assertThat(body).startsWith("created_at,actor_type,actor_id,event_type");
        assertThat(body).contains("\"admin,one\"");
        assertThat(body).contains("\"auth.login\"");
        assertThat(body).contains("\"{\"\"message\"\":\"\"hello \\\"\"world\\\"\"\"\"}\"");
    }

    @Test
    void exportRejectsUnsupportedFormatBeforeReadingEvents() {
        assertThatThrownBy(() -> service.export(
            criteria(null, null, 100),
            "xml",
            USER,
            new MockHttpServletRequest()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(audits, never()).search(any());
    }

    private AuditSearchCriteria criteria(Instant from, Instant to, int limit) {
        return new AuditSearchCriteria(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            from,
            to,
            limit
        );
    }

    private AuditEvent event(
        String auditEventId,
        String actorType,
        String actorId,
        String eventType,
        Map<String, Object> details
    ) {
        return new AuditEvent(
            auditEventId,
            actorType,
            actorId,
            eventType,
            "auth",
            "session-1",
            "success",
            details,
            NOW
        );
    }
}
