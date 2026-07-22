package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    "spring.datasource.url=jdbc:h2:mem:blind-evaluation-corpus-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.pipeline.initial-delay-ms=600000",
    "rca.notification.initial-delay-ms=600000"
})
class BlindEvaluationCorpusTests {
    private static final String EVIDENCE_RESOURCE = "analysis/blind-evaluation-evidence.json";
    private static final String LABEL_RESOURCE = "analysis/blind-evaluation-labels.json";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SECRET_MARKER = Pattern.compile(
        "(?i)(authorization|bearer\\s|api[_-]?key|agent[_-]?token|node[_-]?token|password|private[_-]?key)"
    );
    private static final Set<String> FORBIDDEN_INPUT_FIELDS = Set.of(
        "expected", "expected_signals", "allowed_signals", "forbidden_signals",
        "label", "labels", "root_cause", "description", "alert_name", "category", "class"
    );
    private static final double MIN_PRECISION = 0.90;
    private static final double MIN_RECALL = 0.95;
    private static final double MIN_POSITIVE_PASS_RATE = 0.90;
    private static final double MIN_NEGATIVE_PASS_RATE = 1.0;
    private static final double MIN_TOP_1_HIT_RATE = 0.90;
    private static final double MIN_TOP_3_HIT_RATE = 0.95;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SignalDetectionEngine engine;

    @Test
    void holdoutEvidenceIsDetectedBeforeSealedLabelsAreLoaded() throws IOException {
        String rawEvidence = resourceText(EVIDENCE_RESOURCE);
        JsonNode evidenceSet = objectMapper.readTree(rawEvidence);
        JsonNode cases = evidenceSet.path("cases");

        assertEvidenceContract(rawEvidence, evidenceSet, cases);
        Map<String, List<String>> detections = detectAll(cases);

        // Labels are intentionally unavailable to the detector execution above this point.
        String rawLabels = resourceText(LABEL_RESOURCE);
        JsonNode labelSet = objectMapper.readTree(rawLabels);
        JsonNode labels = labelSet.path("labels");
        assertLabelContract(rawLabels, labelSet, labels, detections.keySet());
        assertNoExactSignalLabelLeak(evidenceSet, labels);

        Evaluation evaluation = evaluate(detections, labels);
        writeReport(evaluation.report(rawEvidence, rawLabels));

        assertThat(evaluation.precision()).as("blind corpus micro precision")
            .isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(evaluation.recall()).as("blind corpus micro recall")
            .isGreaterThanOrEqualTo(MIN_RECALL);
        assertThat(evaluation.positivePassRate()).as("blind positive scenario pass rate")
            .isGreaterThanOrEqualTo(MIN_POSITIVE_PASS_RATE);
        assertThat(evaluation.negativePassRate()).as("blind negative scenario pass rate")
            .isGreaterThanOrEqualTo(MIN_NEGATIVE_PASS_RATE);
        assertThat(evaluation.top1HitRate()).as("blind Top-1 expected signal hit rate")
            .isGreaterThanOrEqualTo(MIN_TOP_1_HIT_RATE);
        assertThat(evaluation.top3HitRate()).as("blind Top-3 expected signal hit rate")
            .isGreaterThanOrEqualTo(MIN_TOP_3_HIT_RATE);
        assertThat(evaluation.forbiddenMatchCount()).as("blind forbidden signal matches").isZero();
    }

    private Map<String, List<String>> detectAll(JsonNode cases) {
        Map<String, List<String>> detections = new LinkedHashMap<>();
        for (JsonNode input : cases) {
            String caseId = input.path("case_id").asText();
            Map<String, Object> collectors = objectMapper.convertValue(input.path("collectors"), MAP_TYPE);
            List<String> signals = engine.detect("cluster-blind-evaluation", collectors).stream()
                .map(signal -> signal.name())
                .toList();
            assertThat(detections.put(caseId, signals)).as("duplicate evidence case ID " + caseId).isNull();
        }
        return detections;
    }

    private Evaluation evaluate(Map<String, List<String>> detections, JsonNode labels) {
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int positiveCount = 0;
        int positivePasses = 0;
        int negativeCount = 0;
        int negativePasses = 0;
        int top1Hits = 0;
        int top3Hits = 0;
        int forbiddenMatchCount = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode label : labels) {
            String caseId = label.path("case_id").asText();
            String caseClass = label.path("class").asText();
            List<String> actual = detections.get(caseId);
            Set<String> expected = strings(label.path("expected_signals"));
            Set<String> accepted = new LinkedHashSet<>(expected);
            accepted.addAll(strings(label.path("allowed_signals")));
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> matched = intersection(expected, actualSet);
            Set<String> missing = difference(expected, actualSet);
            Set<String> unexpected = difference(actualSet, accepted);
            Set<String> forbiddenMatches = intersection(strings(label.path("forbidden_signals")), actualSet);
            boolean passed = missing.isEmpty() && unexpected.isEmpty() && forbiddenMatches.isEmpty();

            truePositive += matched.size();
            falsePositive += unexpected.size();
            falseNegative += missing.size();
            forbiddenMatchCount += forbiddenMatches.size();
            if (expected.isEmpty()) {
                negativeCount++;
                negativePasses += passed && actual.isEmpty() ? 1 : 0;
            } else {
                positiveCount++;
                positivePasses += passed ? 1 : 0;
                top1Hits += !actual.isEmpty() && expected.contains(actual.getFirst()) ? 1 : 0;
                top3Hits += actual.stream().limit(3).anyMatch(expected::contains) ? 1 : 0;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("case_id", caseId);
            result.put("class", caseClass);
            result.put("expected", expected);
            result.put("actual", actual);
            result.put("matched", matched);
            result.put("missing", missing);
            result.put("unexpected", unexpected);
            result.put("forbidden_matches", forbiddenMatches);
            result.put("passed", passed);
            results.add(result);
        }

        return new Evaluation(
            detections.size(),
            truePositive,
            falsePositive,
            falseNegative,
            ratio(truePositive, truePositive + falsePositive),
            ratio(truePositive, truePositive + falseNegative),
            ratio(positivePasses, positiveCount),
            ratio(negativePasses, negativeCount),
            ratio(top1Hits, positiveCount),
            ratio(top3Hits, positiveCount),
            forbiddenMatchCount,
            results
        );
    }

    private void assertEvidenceContract(String rawEvidence, JsonNode evidenceSet, JsonNode cases) {
        assertThat(evidenceSet.path("schema_version").asText()).isEqualTo("rca-blind-evidence-set/v1");
        assertThat(evidenceSet.path("provenance").path("classification").asText())
            .isEqualTo("sanitized_holdout_reproduction");
        assertThat(evidenceSet.path("provenance").path("contains_raw_customer_data").asBoolean(true)).isFalse();
        assertThat(SECRET_MARKER.matcher(rawEvidence).find()).as("blind evidence secret markers").isFalse();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(18);

        Set<String> platforms = new LinkedHashSet<>();
        Set<String> runtimes = new LinkedHashSet<>();
        cases.forEach(input -> {
            assertThat(input.path("case_id").asText()).matches("blind-[0-9]{3}");
            assertThat(input.path("collectors").isObject()).isTrue();
            assertNoForbiddenInputField(input, input.path("case_id").asText());
            platforms.add(input.path("platform").asText());
            runtimes.add(input.path("runtime").asText());
        });
        assertThat(platforms.size()).isGreaterThanOrEqualTo(8);
        assertThat(runtimes).contains("containerd", "crio", "embedded-containerd");
    }

    private void assertLabelContract(
        String rawLabels,
        JsonNode labelSet,
        JsonNode labels,
        Set<String> evidenceIds
    ) {
        assertThat(labelSet.path("schema_version").asText()).isEqualTo("rca-blind-label-set/v1");
        assertThat(labelSet.path("provenance").path("contains_raw_customer_data").asBoolean(true)).isFalse();
        assertThat(SECRET_MARKER.matcher(rawLabels).find()).as("blind label secret markers").isFalse();
        Set<String> labelIds = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        labels.forEach(label -> {
            String caseId = label.path("case_id").asText();
            assertThat(labelIds.add(caseId)).as("duplicate label case ID " + caseId).isTrue();
            assertThat(label.has("collectors")).as(caseId + " label must not contain evidence").isFalse();
            assertThat(label.path("expected_signals").isArray()).isTrue();
            assertThat(label.path("allowed_signals").isArray()).isTrue();
            assertThat(label.path("forbidden_signals").isArray()).isTrue();
            classes.add(label.path("class").asText());
        });
        assertThat(labelIds).containsExactlyInAnyOrderElementsOf(evidenceIds);
        assertThat(classes).contains("negative", "boundary", "single_fault", "compound_fault", "degraded_evidence");
    }

    private void assertNoForbiddenInputField(JsonNode node, String caseId) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                assertThat(FORBIDDEN_INPUT_FIELDS).as(caseId + " forbidden input field " + entry.getKey())
                    .doesNotContain(entry.getKey().toLowerCase(java.util.Locale.ROOT));
                assertNoForbiddenInputField(entry.getValue(), caseId);
            });
        } else if (node.isArray()) {
            node.forEach(child -> assertNoForbiddenInputField(child, caseId));
        }
    }

    private void assertNoExactSignalLabelLeak(JsonNode evidenceSet, JsonNode labels) {
        Set<String> evidenceValues = new LinkedHashSet<>();
        collectTextValues(evidenceSet, evidenceValues);
        labels.forEach(label -> {
            strings(label.path("expected_signals")).forEach(signal ->
                assertThat(evidenceValues).as("exact signal label leaked into blind evidence: " + signal)
                    .doesNotContain(signal)
            );
        });
    }

    private void collectTextValues(JsonNode node, Set<String> values) {
        if (node.isTextual()) {
            values.add(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectTextValues(child, values));
        }
    }

    private String resourceText(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private void writeReport(Map<String, Object> report) throws IOException {
        Path output = Path.of("target", "blind-evaluation-report.json");
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

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Evaluation(
        int caseCount,
        int truePositive,
        int falsePositive,
        int falseNegative,
        double precision,
        double recall,
        double positivePassRate,
        double negativePassRate,
        double top1HitRate,
        double top3HitRate,
        int forbiddenMatchCount,
        List<Map<String, Object>> cases
    ) {
        Map<String, Object> report(String rawEvidence, String rawLabels) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("true_positive", truePositive);
            metrics.put("false_positive", falsePositive);
            metrics.put("false_negative", falseNegative);
            metrics.put("precision", precision);
            metrics.put("recall", recall);
            metrics.put("positive_scenario_pass_rate", positivePassRate);
            metrics.put("negative_scenario_pass_rate", negativePassRate);
            metrics.put("top_1_hit_rate", top1HitRate);
            metrics.put("top_3_hit_rate", top3HitRate);
            metrics.put("forbidden_match_count", forbiddenMatchCount);

            Map<String, Object> gates = new LinkedHashMap<>();
            gates.put("minimum_precision", MIN_PRECISION);
            gates.put("minimum_recall", MIN_RECALL);
            gates.put("minimum_positive_scenario_pass_rate", MIN_POSITIVE_PASS_RATE);
            gates.put("minimum_negative_scenario_pass_rate", MIN_NEGATIVE_PASS_RATE);
            gates.put("minimum_top_1_hit_rate", MIN_TOP_1_HIT_RATE);
            gates.put("minimum_top_3_hit_rate", MIN_TOP_3_HIT_RATE);
            gates.put("maximum_forbidden_match_count", 0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema_version", "rca-blind-evaluation-report/v1");
            result.put("corpus_classification", "sanitized_holdout_reproduction");
            result.put("label_loaded_after_detection", true);
            result.put("evidence_sha256", sha256(rawEvidence));
            result.put("label_sha256", sha256(rawLabels));
            result.put("case_count", caseCount);
            result.put("metrics", metrics);
            result.put("quality_gates", gates);
            result.put("cases", cases);
            return result;
        }
    }
}
