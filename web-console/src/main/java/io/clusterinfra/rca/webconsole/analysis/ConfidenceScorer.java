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
}
