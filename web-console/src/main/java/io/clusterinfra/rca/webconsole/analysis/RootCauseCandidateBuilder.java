package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RootCauseCandidateBuilder {
    public List<RootCauseCandidate> build(List<Signal> signals, String fallbackCause) {
        if (signals.isEmpty()) {
            return List.of(new RootCauseCandidate(
                fallbackCause,
                Confidence.low,
                List.of("No threshold-crossing node signal was found; verify collector completeness and incident timing.")
            ));
        }
        return signals.stream().limit(5).map(signal -> new RootCauseCandidate(
            signal.interpretation(),
            signal.confidence(),
            signal.supportingEvidence()
        )).toList();
    }
}
