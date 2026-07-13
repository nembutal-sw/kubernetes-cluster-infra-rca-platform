package io.clusterinfra.rca.webconsole.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.service.ClusterThresholdService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SignalDetectionEngine {
    private final List<SignalDetector> detectors;
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final OperationalCatalogService catalogService;
    private final ClusterThresholdService thresholdService;
    private final CollectorEvidenceAdapter evidenceAdapter;

    @Autowired
    public SignalDetectionEngine(
        List<SignalDetector> detectors,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        OperationalCatalogService catalogService,
        ClusterThresholdService thresholdService,
        CollectorEvidenceAdapter evidenceAdapter
    ) {
        this.detectors = detectors.stream().sorted(Comparator.comparing(SignalDetector::id)).toList();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.catalogService = catalogService;
        this.thresholdService = thresholdService;
        this.evidenceAdapter = evidenceAdapter;
    }

    public SignalDetectionEngine(
        List<SignalDetector> detectors,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper
    ) {
        this(
            detectors,
            properties,
            objectMapper,
            OperationalCatalogService.defaultService(),
            ClusterThresholdService.defaultsOnly(properties),
            new CollectorEvidenceAdapter(objectMapper)
        );
    }

    public SignalDetectionEngine(
        List<SignalDetector> detectors,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        OperationalCatalogService catalogService
    ) {
        this(
            detectors,
            properties,
            objectMapper,
            catalogService,
            ClusterThresholdService.defaultsOnly(properties),
            new CollectorEvidenceAdapter(objectMapper)
        );
    }

    public SignalDetectionEngine(
        List<SignalDetector> detectors,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        OperationalCatalogService catalogService,
        ClusterThresholdService thresholdService
    ) {
        this(
            detectors,
            properties,
            objectMapper,
            catalogService,
            thresholdService,
            new CollectorEvidenceAdapter(objectMapper)
        );
    }

    public List<Signal> detect(java.util.Map<String, Object> collectors) {
        return detect(null, collectors);
    }

    public List<Signal> detect(String clusterId, java.util.Map<String, Object> collectors) {
        RcaConsoleProperties.Thresholds thresholds = clusterId == null || clusterId.isBlank()
            ? properties.getThresholds()
            : thresholdService.resolve(clusterId);
        CollectorEvidenceAdapter.AdaptationResult adapted = evidenceAdapter.adapt(collectors);
        AnalysisContext context = AnalysisContext.create(adapted.collectors(), thresholds, objectMapper);
        LinkedHashMap<String, Signal> unique = new LinkedHashMap<>();
        detectors.stream()
            .filter(detector -> catalogService.detectorEnabled(detector.id()))
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
