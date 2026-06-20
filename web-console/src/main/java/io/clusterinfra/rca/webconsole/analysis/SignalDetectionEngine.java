package io.clusterinfra.rca.webconsole.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SignalDetectionEngine {
    private final List<SignalDetector> detectors;
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;

    public SignalDetectionEngine(
        List<SignalDetector> detectors,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper
    ) {
        this.detectors = detectors.stream().sorted(Comparator.comparing(SignalDetector::id)).toList();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<Signal> detect(java.util.Map<String, Object> collectors) {
        AnalysisContext context = AnalysisContext.create(collectors, properties.getThresholds(), objectMapper);
        LinkedHashMap<String, Signal> unique = new LinkedHashMap<>();
        detectors.stream()
            .filter(detector -> detector.enabled(context))
            .flatMap(detector -> detector.detect(context).stream())
            .forEach(signal -> unique.putIfAbsent(signal.name(), signal));
        return unique.values().stream()
            .sorted(Comparator.comparingInt(SignalDetectionEngine::severityRank).thenComparing(Signal::name))
            .toList();
    }

    public List<String> detectorIds() {
        return detectors.stream().map(SignalDetector::id).toList();
    }

    private static int severityRank(Signal signal) {
        return switch (signal.severity()) {
            case "critical" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }
}
