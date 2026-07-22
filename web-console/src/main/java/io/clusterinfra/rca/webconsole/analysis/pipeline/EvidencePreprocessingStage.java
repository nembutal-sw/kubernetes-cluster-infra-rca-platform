package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.CollectorEvidenceAdapter;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.PreprocessedEvidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class EvidencePreprocessingStage {
    private static final Set<String> EXCLUDED_CLIENT_FIELDS = Set.of(
        "user_agent",
        "browser",
        "browser_version",
        "client_os",
        "os_version"
    );

    private final CollectorEvidenceAdapter evidenceAdapter;
    private final SignalDetectionEngine detectionEngine;
    private final EvidenceQualityAnalyzer evidenceQualityAnalyzer;

    public EvidencePreprocessingStage(
        CollectorEvidenceAdapter evidenceAdapter,
        SignalDetectionEngine detectionEngine,
        EvidenceQualityAnalyzer evidenceQualityAnalyzer
    ) {
        this.evidenceAdapter = evidenceAdapter;
        this.detectionEngine = detectionEngine;
        this.evidenceQualityAnalyzer = evidenceQualityAnalyzer;
    }

    public PreprocessedEvidence process(EvidenceBundle evidence) {
        CollectorEvidenceAdapter.AdaptationResult adapted = evidenceAdapter.adapt(evidence.collectors());
        return new PreprocessedEvidence(
            evidence,
            detectionEngine.detect(evidence.clusterId(), evidence.collectors()),
            evidenceQualityAnalyzer.assess(evidence),
            adapted.contract(),
            sanitizeCollectors(evidence.collectors())
        );
    }

    public boolean hasActionableSignals(Map<String, Object> collectors) {
        return !detectionEngine.detect(collectors).isEmpty();
    }

    public boolean hasActionableSignals(String clusterId, Map<String, Object> collectors) {
        return !detectionEngine.detect(clusterId, collectors).isEmpty();
    }

    public List<Map<String, Object>> timelineSignals(Map<String, Object> collectors) {
        return detectionEngine.detect(collectors).stream().map(Signal::asMap).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeCollectors(Map<String, Object> collectors) {
        return (Map<String, Object>) sanitize(collectors == null ? Map.of() : collectors, 0);
    }

    private Object sanitize(Object value, int depth) {
        if (depth > 7) {
            return "[truncated]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
                if (EXCLUDED_CLIENT_FIELDS.contains(normalized)) {
                    continue;
                }
                sanitized.put(
                    key,
                    SensitiveDataRedactor.isSensitiveKey(key)
                        ? "[redacted]"
                        : sanitize(entry.getValue(), depth + 1)
                );
                if (++count >= 120) {
                    sanitized.put("_truncated", true);
                    break;
                }
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(100).map(item -> sanitize(item, depth + 1)).toList();
        }
        if (value instanceof String text) {
            String redacted = SensitiveDataRedactor.redactText(text);
            return redacted.length() > 4000
                ? redacted.substring(0, 4000) + "...[truncated]"
                : redacted;
        }
        return value;
    }
}
