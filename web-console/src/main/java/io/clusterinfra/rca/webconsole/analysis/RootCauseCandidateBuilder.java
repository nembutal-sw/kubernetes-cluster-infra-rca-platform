package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
        return signals.stream()
            .sorted(candidateComparator(componentCounts))
            .limit(5)
            .map(signal -> new RootCauseCandidate(
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

    private Comparator<Signal> candidateComparator(Map<String, Long> componentCounts) {
        return Comparator
            .comparingInt((Signal signal) -> confidenceScorer.candidateScore(
                signal,
                componentCounts.getOrDefault(signal.component(), 0L).intValue()
            ))
            .reversed()
            .thenComparingInt(confidenceScorer::rootCausePriority)
            .thenComparing((Signal signal) -> signal.threshold() == null)
            .thenComparingInt(RootCauseCandidateBuilder::severityRank)
            .thenComparing(Signal::name);
    }

    private static int severityRank(Signal signal) {
        return switch (signal.severity() == null ? "" : signal.severity()) {
            case "critical" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }
}
