package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Signal(
    String name,
    String component,
    String severity,
    Confidence confidence,
    Object observed,
    Double threshold,
    List<String> matchedFields,
    String interpretation,
    String nextStep,
    List<String> supportingEvidence
) {
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", name);
        result.put("component", component);
        result.put("severity", severity);
        result.put("confidence", confidence.name());
        result.put("observed", observed);
        if (threshold != null) {
            result.put("threshold", threshold);
        }
        result.put("matched_fields", matchedFields);
        result.put("interpretation", interpretation);
        result.put("next_step", nextStep);
        result.put("supporting_evidence", supportingEvidence);
        return result;
    }
}
