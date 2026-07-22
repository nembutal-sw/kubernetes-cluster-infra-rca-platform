package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:production-like-corpus-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.pipeline.initial-delay-ms=600000"
})
class ProductionLikeEvidenceCorpusTests {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SECRET_MARKER = Pattern.compile(
        "(?i)(authorization|bearer\\s|api[_-]?key|agent[_-]?token|node[_-]?token|password|private[_-]?key)"
    );
    private static final double MIN_PRECISION = 0.95;
    private static final double MIN_RECALL = 0.95;
    private static final double MIN_POSITIVE_SCENARIO_PASS_RATE = 1.0;
    private static final double MIN_NEGATIVE_SCENARIO_PASS_RATE = 1.0;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SignalDetectionEngine engine;
    @Autowired
    private EvidenceQualityAnalyzer evidenceQualityAnalyzer;

    @Test
    void sanitizedProductionLikeCorpusMeetsDetectionAndSafetyGates() throws IOException {
        ClassPathResource resource = new ClassPathResource("analysis/production-like-evidence-corpus.json");
        String rawCorpus = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode corpus = objectMapper.readTree(rawCorpus);
        JsonNode provenance = corpus.path("provenance");
        JsonNode scenarios = corpus.path("scenarios");

        assertThat(corpus.path("schema_version").asText()).isEqualTo("rca-production-like-corpus/v1");
        assertThat(provenance.path("classification").asText())
            .isEqualTo("sanitized_production_like_reproduction");
        assertThat(provenance.path("contains_raw_customer_data").asBoolean(true)).isFalse();
        assertThat(SECRET_MARKER.matcher(rawCorpus).find()).as("corpus secret markers").isFalse();
        assertCorpusCoverage(scenarios);

        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int positiveScenarioCount = 0;
        int positiveScenarioPasses = 0;
        int negativeScenarioCount = 0;
        int negativeScenarioPasses = 0;
        List<Map<String, Object>> scenarioResults = new ArrayList<>();

        for (JsonNode scenario : scenarios) {
            String key = scenario.path("key").asText();
            JsonNode expectedNode = scenario.path("expected");
            Map<String, Object> collectors = objectMapper.convertValue(scenario.path("collectors"), MAP_TYPE);
            List<String> actual = engine.detect("cluster-production-like", collectors).stream()
                .map(signal -> signal.name())
                .toList();
            Set<String> expected = strings(expectedNode.path("signals"));
            Set<String> allowed = new LinkedHashSet<>(expected);
            allowed.addAll(strings(expectedNode.path("allowed_signals")));
            Set<String> forbidden = strings(expectedNode.path("forbidden_signals"));
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> matched = intersection(expected, actualSet);
            Set<String> unexpected = difference(actualSet, allowed);
            Set<String> missing = difference(expected, actualSet);
            Set<String> forbiddenMatches = intersection(forbidden, actualSet);

            truePositive += matched.size();
            falsePositive += unexpected.size();
            falseNegative += missing.size();
            boolean passed = missing.isEmpty() && unexpected.isEmpty() && forbiddenMatches.isEmpty();
            if (expected.isEmpty()) {
                negativeScenarioCount++;
                negativeScenarioPasses += passed && actualSet.isEmpty() ? 1 : 0;
            } else {
                positiveScenarioCount++;
                positiveScenarioPasses += passed ? 1 : 0;
            }

            assertThat(missing).as(key + " missing signals").isEmpty();
            assertThat(unexpected).as(key + " unexpected signals").isEmpty();
            assertThat(forbiddenMatches).as(key + " forbidden signals").isEmpty();
            assertQualityExpectation(key, scenario, collectors);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("category", scenario.path("category").asText());
            result.put("platform", scenario.path("platform").asText());
            result.put("runtime", scenario.path("runtime").asText());
            result.put("expected", expected);
            result.put("actual", actual);
            result.put("matched", matched);
            result.put("unexpected", unexpected);
            result.put("missing", missing);
            result.put("forbidden_matches", forbiddenMatches);
            result.put("passed", passed);
            scenarioResults.add(result);
        }

        double precision = ratio(truePositive, truePositive + falsePositive);
        double recall = ratio(truePositive, truePositive + falseNegative);
        double positiveScenarioPassRate = ratio(positiveScenarioPasses, positiveScenarioCount);
        double negativeScenarioPassRate = ratio(negativeScenarioPasses, negativeScenarioCount);
        writeReport(qualityReport(
            scenarios.size(), truePositive, falsePositive, falseNegative, precision, recall,
            positiveScenarioPassRate, negativeScenarioPassRate, scenarioResults
        ));

        assertThat(precision).as("production-like micro precision").isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(recall).as("production-like micro recall").isGreaterThanOrEqualTo(MIN_RECALL);
        assertThat(positiveScenarioPassRate).as("positive scenario pass rate")
            .isGreaterThanOrEqualTo(MIN_POSITIVE_SCENARIO_PASS_RATE);
        assertThat(negativeScenarioPassRate).as("negative scenario pass rate")
            .isGreaterThanOrEqualTo(MIN_NEGATIVE_SCENARIO_PASS_RATE);
    }

    private void assertCorpusCoverage(JsonNode scenarios) {
        assertThat(scenarios).isNotEmpty();
        assertThat(scenarios.size()).isGreaterThanOrEqualTo(12);
        Set<String> categories = new LinkedHashSet<>();
        Set<String> platforms = new LinkedHashSet<>();
        Set<String> runtimes = new LinkedHashSet<>();
        scenarios.forEach(scenario -> {
            categories.add(scenario.path("category").asText());
            platforms.add(scenario.path("platform").asText());
            runtimes.add(scenario.path("runtime").asText());
        });
        assertThat(categories).contains(
            "negative", "boundary", "single_fault", "compound_fault", "degraded_evidence",
            "temporal_anomaly", "distro_runtime_variant"
        );
        assertThat(platforms.size()).isGreaterThanOrEqualTo(5);
        assertThat(runtimes).contains("containerd", "crio", "embedded-containerd");
    }

    @SuppressWarnings("unchecked")
    private void assertQualityExpectation(String key, JsonNode scenario, Map<String, Object> collectors) {
        JsonNode expected = scenario.path("expected");
        if (!expected.hasNonNull("quality_status")) {
            return;
        }
        Map<String, Object> quality = evidenceQualityAnalyzer.assess(new EvidenceBundle(
            "evidence-" + key,
            "cluster-production-like",
            "node-production-like",
            scenario.path("alert_name").asText(),
            Instant.now(),
            collectors
        ));
        assertThat(String.valueOf(quality.get("status"))).as(key + " quality status")
            .isEqualTo(expected.path("quality_status").asText());
        Map<String, Object> collectorStatus = (Map<String, Object>) quality.get("collector_status");
        assertThat((List<String>) collectorStatus.get("degraded")).as(key + " degraded collectors")
            .containsAll(strings(expected.path("degraded_collectors")));
    }

    private Map<String, Object> qualityReport(
        int scenarioCount,
        int truePositive,
        int falsePositive,
        int falseNegative,
        double precision,
        double recall,
        double positiveScenarioPassRate,
        double negativeScenarioPassRate,
        List<Map<String, Object>> scenarios
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("true_positive", truePositive);
        metrics.put("false_positive", falsePositive);
        metrics.put("false_negative", falseNegative);
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("positive_scenario_pass_rate", positiveScenarioPassRate);
        metrics.put("negative_scenario_pass_rate", negativeScenarioPassRate);
        Map<String, Object> gates = Map.of(
            "minimum_precision", MIN_PRECISION,
            "minimum_recall", MIN_RECALL,
            "minimum_positive_scenario_pass_rate", MIN_POSITIVE_SCENARIO_PASS_RATE,
            "minimum_negative_scenario_pass_rate", MIN_NEGATIVE_SCENARIO_PASS_RATE
        );
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "rca-production-like-corpus-report/v1");
        report.put("corpus_classification", "sanitized_production_like_reproduction");
        report.put("scenario_count", scenarioCount);
        report.put("metrics", metrics);
        report.put("quality_gates", gates);
        report.put("scenarios", scenarios);
        return report;
    }

    private void writeReport(Map<String, Object> report) throws IOException {
        Path output = Path.of("target", "production-like-evidence-corpus-report.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private Set<String> strings(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(item -> result.add(item.asText()));
        return result;
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }
}
