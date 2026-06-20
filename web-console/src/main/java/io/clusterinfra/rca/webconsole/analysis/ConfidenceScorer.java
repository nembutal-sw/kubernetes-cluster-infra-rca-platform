package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConfidenceScorer {
    public Confidence reportConfidence(List<Signal> signals) {
        if (signals.isEmpty()) {
            return Confidence.low;
        }
        if (signals.stream().anyMatch(signal -> signal.confidence() == Confidence.high)) {
            return Confidence.high;
        }
        return Confidence.medium;
    }

    public int candidateScore(Signal signal, int sameComponentCount) {
        int score = "critical".equalsIgnoreCase(signal.severity()) ? 30 : 15;
        score += switch (signal.confidence()) {
            case high -> 25;
            case medium -> 15;
            case low -> 5;
        };
        if (sameComponentCount > 1) {
            score += 20;
        }
        if (signal.threshold() != null) {
            score += 20;
        }
        long independentSources = signal.matchedFields().stream()
            .map(ConfidenceScorer::source)
            .distinct()
            .count();
        if (independentSources > 1) {
            score += 20;
        }
        if (!signal.supportingEvidence().isEmpty()) {
            score += 10;
        }
        return Math.max(0, Math.min(100, score));
    }

    private static String source(String path) {
        int separator = path == null ? -1 : path.indexOf('.');
        return separator <= 0 ? String.valueOf(path) : path.substring(0, separator);
    }
}
