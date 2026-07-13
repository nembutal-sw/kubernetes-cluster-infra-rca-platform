package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rule-regression-fixture-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.pipeline.initial-delay-ms=600000"
})
class RuleBasedRegressionFixtureTests {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RuleBasedRcaAnalyzer analyzer;

    @Test
    void plannedInfrastructureFailureFixturesProduceOperationalReports() throws IOException {
        JsonNode scenarios = objectMapper.readTree(
            new ClassPathResource("analysis/rule-based-rca-regression-scenarios.json").getInputStream()
        );

        assertThat(scenarios).isNotEmpty();
        for (JsonNode scenario : scenarios) {
            assertScenario(scenario);
        }
    }

    private void assertScenario(JsonNode scenario) {
        String key = scenario.path("key").asText();
        JsonNode expected = scenario.path("expected");
        Map<String, Object> collectors = objectMapper.convertValue(scenario.path("collectors"), MAP_TYPE);

        RcaReport report = analyzer.analyze(
            "report-" + key,
            new EvidenceBundle(
                "evidence-" + key,
                "cluster-regression",
                "worker-regression-01",
                scenario.path("alert_name").asText(),
                Instant.now(),
                collectors
            )
        );

        List<Map<String, Object>> signalMaps = evidenceList(report, "derived_signals", "signals");
        List<String> signalNames = signalMaps.stream()
            .map(signal -> String.valueOf(signal.get("signal")))
            .toList();
        List<String> signalComponents = signalMaps.stream()
            .map(signal -> String.valueOf(signal.get("component")))
            .distinct()
            .toList();
        assertThat(signalNames).as(key + " signals").containsAll(strings(expected, "signals"));
        assertThat(signalComponents).as(key + " signal components").containsAll(strings(expected, "components"));

        assertThat(report.summary().confidence().name()).as(key + " summary confidence")
            .isEqualToIgnoringCase(expected.path("summary_confidence").asText());
        assertThat(report.rootCauseCandidates()).as(key + " root cause candidates").isNotEmpty();
        assertThat(report.rootCauseCandidates().getFirst().confidenceScore()).as(key + " top candidate score")
            .isGreaterThanOrEqualTo(expected.path("min_top_candidate_score").asInt());
        assertThat(report.rootCauseCandidates()).as(key + " candidate evidence paths")
            .allSatisfy(candidate -> assertThat(candidate.evidencePaths()).isNotNull());

        Map<String, Object> qualityGate = evidenceMap(report, "quality_gate", "gate");
        assertThat(qualityGate).as(key + " quality gate").containsKeys(
            "status",
            "rule_signal_count",
            "top_candidate_score",
            "evidence_quality_status",
            "rule_based_sufficient",
            "additional_evidence_required"
        );
        assertThat(String.valueOf(qualityGate.get("status"))).as(key + " quality gate status").isNotBlank();
        assertThat(number(qualityGate.get("rule_signal_count"))).as(key + " gate signal count")
            .isGreaterThanOrEqualTo(strings(expected, "signals").size());
        assertThat(String.valueOf(qualityGate.get("status"))).as(key + " sufficient fixture gate")
            .isNotEqualTo("insufficient");

        Map<String, Object> evidenceContract = evidenceMap(report, "evidence_contract", "contract");
        assertThat(evidenceContract).as(key + " evidence contract")
            .containsEntry("schema_version", "collector-evidence/v1")
            .containsKey("collectors");
        assertThat(evidenceContract.get("status")).as(key + " evidence contract status")
            .isNotEqualTo("invalid");

        List<String> actionKeys = report.recommendedActions().stream()
            .map(RecommendedAction::actionKey)
            .toList();
        assertThat(actionKeys).as(key + " action keys").containsAll(strings(expected, "actions"));
        assertManualOnlyActions(key, report.recommendedActions(), strings(expected, "manual_only_actions"));

        List<String> checklistTitles = evidenceList(report, "resolution_checklist", "items").stream()
            .map(item -> String.valueOf(item.get("title")))
            .toList();
        assertThat(checklistTitles).as(key + " resolution checklist")
            .containsAll(strings(expected, "checklists"));

        assertThat(list(report.scope().get("components"))).as(key + " scope components")
            .containsAll(strings(expected, "components"));
    }

    private void assertManualOnlyActions(
        String key,
        List<RecommendedAction> actions,
        List<String> expectedManualOnlyKeys
    ) {
        for (String actionKey : expectedManualOnlyKeys) {
            RecommendedAction action = actions.stream()
                .filter(item -> actionKey.equals(item.actionKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " missing manual-only action " + actionKey));

            assertThat(action.automationAllowed()).as(key + " " + actionKey + " automation_allowed").isFalse();
            assertThat(action.policy()).as(key + " " + actionKey + " policy")
                .isNotEqualTo(PolicyLevel.AUTO_SAFE);
            if (action.executionPlan() != null) {
                assertThat(action.executionPlan().executable()).as(key + " " + actionKey + " executable").isFalse();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evidenceList(RcaReport report, String sectionType, String field) {
        return report.evidence().stream()
            .filter(section -> sectionType.equals(section.get("type")))
            .findFirst()
            .map(section -> (List<Map<String, Object>>) section.get(field))
            .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidenceMap(RcaReport report, String sectionType, String field) {
        return report.evidence().stream()
            .filter(section -> sectionType.equals(section.get("type")))
            .findFirst()
            .map(section -> (Map<String, Object>) section.get(field))
            .orElseThrow(() -> new AssertionError("missing evidence section " + sectionType));
    }

    private int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private List<String> strings(JsonNode node, String field) {
        return list(node.path(field));
    }

    private List<String> list(Object value) {
        if (value instanceof JsonNode node) {
            return node.isArray()
                ? objectMapper.convertValue(node, new TypeReference<List<String>>() {
                })
                : List.of();
        }
        if (value instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
