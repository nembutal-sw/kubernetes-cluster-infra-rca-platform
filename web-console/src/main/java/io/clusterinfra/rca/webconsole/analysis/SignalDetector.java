package io.clusterinfra.rca.webconsole.analysis;

import java.util.List;

public interface SignalDetector {
    String id();

    default boolean enabled(AnalysisContext context) {
        return true;
    }

    List<Signal> detect(AnalysisContext context);
}
