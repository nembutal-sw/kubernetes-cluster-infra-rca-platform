package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class IncidentWorkflowServiceTests {
    private static final Instant NOW = Instant.parse("2026-06-21T04:00:00Z");
    private static final UserAccount USER = new UserAccount(
        "user-1",
        "operator@example.com",
        "Operator",
        UserRole.operator,
        UserRole.operator,
        UserStatus.active,
        null,
        null,
        null,
        NOW,
        NOW
    );

    private IncidentRepository incidents;
    private AuditService audit;
    private IncidentWorkflowService service;

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentRepository.class);
        audit = mock(AuditService.class);
        service = new IncidentWorkflowService(incidents, audit);
    }

    @Test
    void listAndFindDelegateToRepository() {
        Incident incident = incident(IncidentStatus.open);
        when(incidents.list("cluster-a")).thenReturn(List.of(incident));
        when(incidents.find("incident-1")).thenReturn(Optional.of(incident));

        assertThat(service.list("cluster-a")).containsExactly(incident);
        assertThat(service.requireIncident("incident-1")).isEqualTo(incident);
    }

    @Test
    void missingIncidentReturns404() {
        when(incidents.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireIncident("missing"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void resolveRequiresConfirmationBeforeUpdating() {
        assertThatThrownBy(() -> service.resolve(
            "incident-1",
            new ActionDecisionRequest(false, "done"),
            USER,
            new MockHttpServletRequest()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(incidents, never()).updateStatus(any(), any(), any(), any(), any());
        verify(audit, never()).user(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveUpdatesIncidentAndWritesAuditEvent() {
        Incident resolved = incident(IncidentStatus.resolved);
        when(incidents.updateStatus(
            eq("incident-1"),
            eq(IncidentStatus.resolved),
            eq("manual"),
            eq("fixed manually"),
            any(Instant.class)
        )).thenReturn(Optional.of(resolved));

        Incident result = service.resolve(
            "incident-1",
            new ActionDecisionRequest(true, "fixed manually"),
            USER,
            new MockHttpServletRequest()
        );

        assertThat(result.status()).isEqualTo(IncidentStatus.resolved);
        verify(audit).user(
            eq(USER),
            eq("incident.status_change"),
            eq("incident"),
            eq("incident-1"),
            eq("resolved"),
            eq(Map.of("note", "fixed manually")),
            any()
        );
    }

    @Test
    void reopenNormalizesNullNoteAndWritesAuditEvent() {
        Incident open = incident(IncidentStatus.open);
        when(incidents.updateStatus(
            eq("incident-1"),
            eq(IncidentStatus.open),
            eq("manual"),
            eq(""),
            any(Instant.class)
        )).thenReturn(Optional.of(open));

        Incident result = service.reopen(
            "incident-1",
            new ActionDecisionRequest(true, null),
            USER,
            new MockHttpServletRequest()
        );

        assertThat(result.status()).isEqualTo(IncidentStatus.open);
        verify(audit).user(
            eq(USER),
            eq("incident.status_change"),
            eq("incident"),
            eq("incident-1"),
            eq("open"),
            eq(Map.of("note", "")),
            any()
        );
    }

    @Test
    void statusUpdateMissingIncidentReturns404WithoutAudit() {
        when(incidents.updateStatus(
            eq("missing"),
            eq(IncidentStatus.resolved),
            eq("manual"),
            eq("done"),
            any(Instant.class)
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(
            "missing",
            new ActionDecisionRequest(true, "done"),
            USER,
            new MockHttpServletRequest()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(audit, never()).user(any(), any(), any(), any(), any(), any(), any());
    }

    private Incident incident(IncidentStatus status) {
        return new Incident(
            "incident-1",
            "cluster-a",
            "worker-a",
            "DiskPressure",
            "Inode exhaustion",
            status,
            1,
            NOW.minusSeconds(300),
            NOW,
            "evidence-1",
            "report-1",
            status == IncidentStatus.resolved ? NOW : null,
            status == IncidentStatus.resolved ? "manual" : null,
            status == IncidentStatus.resolved ? "fixed manually" : null,
            null,
            0,
            List.of("worker-a")
        );
    }
}
