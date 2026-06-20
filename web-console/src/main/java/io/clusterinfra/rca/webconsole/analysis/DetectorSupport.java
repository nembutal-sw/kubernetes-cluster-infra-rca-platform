package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import java.util.List;

public final class DetectorSupport {
    private DetectorSupport() {
    }

    public static Signal thresholdSignal(
        String name,
        String component,
        String severity,
        AnalysisContext.MatchedNumber match,
        double threshold,
        String interpretation,
        String nextStep,
        String... evidenceTags
    ) {
        return new Signal(
            name,
            component,
            severity,
            "critical".equals(severity) ? Confidence.high : Confidence.medium,
            match.value(),
            threshold,
            List.of(match.field()),
            interpretation,
            nextStep,
            List.of(match.field() + "=" + match.value() + " >= threshold " + threshold, String.join(", ", evidenceTags))
        );
    }

    public static Signal matchedSignal(
        String name,
        String component,
        String severity,
        Object observed,
        List<String> fields,
        String interpretation,
        String nextStep,
        String... evidenceTags
    ) {
        return new Signal(
            name,
            component,
            severity,
            "critical".equals(severity) ? Confidence.high : Confidence.medium,
            observed,
            null,
            fields,
            interpretation,
            nextStep,
            List.of(String.valueOf(observed), String.join(", ", evidenceTags))
        );
    }
}
