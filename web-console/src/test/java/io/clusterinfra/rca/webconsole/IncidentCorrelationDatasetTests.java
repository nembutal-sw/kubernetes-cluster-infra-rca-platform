package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.TokenService;
import io.clusterinfra.rca.webconsole.service.IncidentCorrelationService;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncidentCorrelationDatasetTests {
    private static final Instant OBSERVED_AT = Instant.parse("2026-06-21T05:00:00Z");

    @Test
    void evaluatesCuratedCorrelationAndFalsePositiveCases() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (InputStream input = getClass().getResourceAsStream(
            "/correlation/incident-correlation-v2.json"
        )) {
            assertThat(input).isNotNull();
            List<Case> cases = objectMapper.readValue(input, new TypeReference<>() {
            });
            assertThat(cases).hasSizeGreaterThanOrEqualTo(10);
            for (Case testCase : cases) {
                evaluate(testCase);
            }
        }
    }

    private void evaluate(Case testCase) {
        IncidentRepository incidents = mock(IncidentRepository.class);
        ReportRepository reports = mock(ReportRepository.class);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        IncidentCorrelationService service = new IncidentCorrelationService(
            incidents,
            reports,
            properties,
            new TokenService(),
            new IncidentCausalityRules()
        );
        RcaReport currentReport = report(
            "report-current",
            testCase.currentAlert(),
            testCase.currentComponents()
        );
        Incident current = incident(testCase, currentReport.reportId());
        when(reports.findReport(currentReport.reportId())).thenReturn(Optional.of(currentReport));
        when(incidents.findRecentOpen(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(testCase.currentStatus() == IncidentStatus.open
            ? List.of(current)
            : List.of());
        when(incidents.findRecentResolved(
            eq("cluster-1"),
            eq("worker-a"),
            any(Instant.class),
            any(Instant.class),
            eq(20)
        )).thenReturn(testCase.currentStatus() == IncidentStatus.resolved
            ? List.of(current)
            : List.of());

        var decision = service.decide(
            report("report-incoming", testCase.incomingAlert(), testCase.incomingComponents()),
            new EvidenceBundle(
                "evidence-incoming",
                "cluster-1",
                "worker-a",
                testCase.incomingAlert(),
                OBSERVED_AT,
                Map.of()
            )
        );

        assertThat(decision.matched())
            .as(testCase.name() + " match")
            .isEqualTo(testCase.expectedMatch());
        assertThat(decision.recurrence())
            .as(testCase.name() + " recurrence")
            .isEqualTo(testCase.expectedRecurrence());
        assertThat(decision.promoteRootCause())
            .as(testCase.name() + " promotion")
            .isEqualTo(testCase.expectedPromotion());
        assertThat(decision.ruleId())
            .as(testCase.name() + " rule")
            .isEqualTo(testCase.expectedRule());
    }

    private Incident incident(Case testCase, String reportId) {
        Instant resolvedAt = testCase.currentStatus() == IncidentStatus.resolved
            ? OBSERVED_AT.minusSeconds(1800)
            : null;
        return new Incident(
            "incident-current",
            "cluster-1",
            "worker-a",
            testCase.currentAlert(),
            "Observed infrastructure signal",
            testCase.currentStatus(),
            1,
            OBSERVED_AT.minusSeconds(3600),
            OBSERVED_AT.minusSeconds(60),
            "evidence-current",
            reportId,
            resolvedAt,
            resolvedAt == null ? null : "automatic",
            resolvedAt == null ? null : "inactive",
            null,
            0
        );
    }

    private RcaReport report(String reportId, String alertName, List<String> components) {
        return new RcaReport(
            reportId,
            "cluster-1",
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", alertName),
            Map.of("nodes", List.of("worker-a"), "components", components),
            new RcaSummary(alertName, "Observed infrastructure signal", Confidence.high),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            OBSERVED_AT
        );
    }

    private record Case(
        String name,
        String currentAlert,
        List<String> currentComponents,
        String incomingAlert,
        List<String> incomingComponents,
        IncidentStatus currentStatus,
        boolean expectedMatch,
        boolean expectedRecurrence,
        boolean expectedPromotion,
        String expectedRule
    ) {
    }
}
