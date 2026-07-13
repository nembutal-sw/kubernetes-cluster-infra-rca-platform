package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rule-quality-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.pipeline.initial-delay-ms=600000"
})
class RuleAnalysisQualityTests {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final double MIN_PRECISION = 0.90;
    private static final double MIN_RECALL = 0.95;
    private static final double MIN_TOP_1_HIT_RATE = 0.90;
    private static final double MIN_TOP_3_HIT_RATE = 0.95;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SignalDetectionEngine engine;

    @Test
    void goldenInfrastructureScenariosMeetSignalQualityGates() throws IOException {
        JsonNode scenarios = objectMapper.readTree(
            new ClassPathResource("analysis/rule-based-rca-regression-scenarios.json").getInputStream()
        );
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int top1Hits = 0;
        int top3Hits = 0;
        List<Map<String, Object>> scenarioResults = new ArrayList<>();

        for (JsonNode scenario : scenarios) {
            Map<String, Object> collectors = objectMapper.convertValue(scenario.path("collectors"), MAP_TYPE);
            List<String> actual = engine.detect("cluster-quality", collectors).stream().map(signal -> signal.name()).toList();
            Set<String> expected = new LinkedHashSet<>(strings(scenario.path("expected").path("signals")));
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> matched = intersection(expected, actualSet);
            Set<String> unexpected = difference(actualSet, expected);
            Set<String> missing = difference(expected, actualSet);
            truePositive += matched.size();
            falsePositive += unexpected.size();
            falseNegative += missing.size();
            boolean top1 = !actual.isEmpty() && expected.contains(actual.getFirst());
            boolean top3 = actual.stream().limit(3).anyMatch(expected::contains);
            top1Hits += top1 ? 1 : 0;
            top3Hits += top3 ? 1 : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", scenario.path("key").asText());
            result.put("expected", expected);
            result.put("actual", actual);
            result.put("matched", matched);
            result.put("unexpected", unexpected);
            result.put("missing", missing);
            result.put("top_1_hit", top1);
            result.put("top_3_hit", top3);
            scenarioResults.add(result);
        }

        double precision = ratio(truePositive, truePositive + falsePositive);
        double recall = ratio(truePositive, truePositive + falseNegative);
        double top1 = ratio(top1Hits, scenarios.size());
        double top3 = ratio(top3Hits, scenarios.size());
        Map<String, Object> report = qualityReport(
            scenarios.size(), truePositive, falsePositive, falseNegative, precision, recall, top1, top3, scenarioResults
        );
        writeReport(report);

        assertThat(precision).as("micro precision; see target/analysis-quality-report.json")
            .isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(recall).as("micro recall; see target/analysis-quality-report.json")
            .isGreaterThanOrEqualTo(MIN_RECALL);
        assertThat(top1).as("top-1 expected-signal hit rate").isGreaterThanOrEqualTo(MIN_TOP_1_HIT_RATE);
        assertThat(top3).as("top-3 expected-signal hit rate").isGreaterThanOrEqualTo(MIN_TOP_3_HIT_RATE);
    }

    private Map<String, Object> qualityReport(
        int scenarioCount,
        int truePositive,
        int falsePositive,
        int falseNegative,
        double precision,
        double recall,
        double top1,
        double top3,
        List<Map<String, Object>> scenarios
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("true_positive", truePositive);
        metrics.put("false_positive", falsePositive);
        metrics.put("false_negative", falseNegative);
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("top_1_hit_rate", top1);
        metrics.put("top_3_hit_rate", top3);
        Map<String, Object> gates = Map.of(
            "minimum_precision", MIN_PRECISION,
            "minimum_recall", MIN_RECALL,
            "minimum_top_1_hit_rate", MIN_TOP_1_HIT_RATE,
            "minimum_top_3_hit_rate", MIN_TOP_3_HIT_RATE
        );
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "rca-analysis-quality/v1");
        report.put("scenario_count", scenarioCount);
        report.put("metrics", metrics);
        report.put("quality_gates", gates);
        report.put("scenarios", scenarios);
        return report;
    }

    private void writeReport(Map<String, Object> report) throws IOException {
        Path output = Path.of("target", "analysis-quality-report.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
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
