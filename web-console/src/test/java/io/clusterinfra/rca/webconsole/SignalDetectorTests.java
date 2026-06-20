package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.detector.DiskPressureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.KernelLogDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.KubeletFailureDetector;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalDetectorTests {
    private final RcaConsoleProperties properties = new RcaConsoleProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void diskDetectorExplainsThresholdAndMatchedField() {
        AnalysisContext context = context(Map.of(
            "disk", Map.of(
                "root_usage_percent", 96.0,
                "secondary_usage_percent", 42.0
            )
        ));

        List<Signal> signals = new DiskPressureDetector().detect(context);

        assertThat(signals).extracting(Signal::name).contains("disk_usage_critical");
        Signal signal = signals.stream()
            .filter(item -> "disk_usage_critical".equals(item.name()))
            .findFirst()
            .orElseThrow();
        assertThat(signal.threshold()).isEqualTo(properties.getThresholds().getDiskCriticalPercent());
        assertThat(signal.matchedFields()).contains("disk.root_usage_percent");
        assertThat(signal.supportingEvidence().getFirst()).contains(">= threshold");
    }

    @Test
    void unknownStatusDoesNotBecomeCriticalFailure() {
        AnalysisContext context = context(Map.of(
            "kubelet", Map.of("status", "unknown", "health_check", "not collected")
        ));

        assertThat(new KubeletFailureDetector().detect(context)).isEmpty();
    }

    @Test
    void unrelatedErrorTextDoesNotBecomeKernelIoSignal() {
        AnalysisContext context = context(Map.of(
            "application", Map.of("message", "request error rate is 1 percent"),
            "kernel", Map.of("messages", List.of("device initialized successfully"))
        ));

        assertThat(new KernelLogDetector().detect(context)).isEmpty();
    }

    private AnalysisContext context(Map<String, Object> collectors) {
        return AnalysisContext.create(collectors, properties.getThresholds(), objectMapper);
    }
}
