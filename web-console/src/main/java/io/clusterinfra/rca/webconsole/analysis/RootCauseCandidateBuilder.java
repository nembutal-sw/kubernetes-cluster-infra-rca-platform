package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RootCauseCandidateBuilder {
    private final ConfidenceScorer confidenceScorer;

    public RootCauseCandidateBuilder(ConfidenceScorer confidenceScorer) {
        this.confidenceScorer = confidenceScorer;
    }

    public List<RootCauseCandidate> build(List<Signal> signals, String fallbackCause) {
        if (signals.isEmpty()) {
            return List.of(new RootCauseCandidate(
                fallbackCause,
                Confidence.low,
                List.of("No threshold-crossing node signal was found; verify collector completeness and incident timing."),
                15,
                List.of()
            ));
        }
        Map<String, Long> componentCounts = signals.stream()
            .collect(Collectors.groupingBy(Signal::component, Collectors.counting()));
        return signals.stream().limit(5).map(signal -> new RootCauseCandidate(
            signal.interpretation(),
            signal.confidence(),
            signal.supportingEvidence(),
            confidenceScorer.candidateScore(
                signal,
                componentCounts.getOrDefault(signal.component(), 0L).intValue()
            ),
            signal.matchedFields()
        )).toList();
    }
}
