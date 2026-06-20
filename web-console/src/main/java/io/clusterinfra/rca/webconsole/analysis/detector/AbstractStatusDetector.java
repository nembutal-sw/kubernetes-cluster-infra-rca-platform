package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.List;

abstract class AbstractStatusDetector implements SignalDetector {
    private final String id;
    private final String fragment;
    private final String signalName;
    private final String component;
    private final String interpretation;
    private final String nextStep;

    AbstractStatusDetector(
        String id,
        String fragment,
        String signalName,
        String component,
        String interpretation,
        String nextStep
    ) {
        this.id = id;
        this.fragment = fragment;
        this.signalName = signalName;
        this.component = component;
        this.interpretation = interpretation;
        this.nextStep = nextStep;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        return context.status(fragment)
            .map(match -> List.of(DetectorSupport.matchedSignal(
                signalName,
                component,
                "critical",
                match.value(),
                List.of(match.field()),
                interpretation,
                nextStep,
                component
            )))
            .orElseGet(List::of);
    }
}
