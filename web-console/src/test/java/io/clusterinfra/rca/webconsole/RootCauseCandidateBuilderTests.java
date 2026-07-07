package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.analysis.ConfidenceScorer;
import io.clusterinfra.rca.webconsole.analysis.RootCauseCandidateBuilder;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootCauseCandidateBuilderTests {
    private final RootCauseCandidateBuilder builder = new RootCauseCandidateBuilder(new ConfidenceScorer());

    @Test
    void ranksDirectInfrastructureCauseAboveDownstreamNodeSymptom() {
        Signal nodeNotReady = new Signal(
            "node_not_ready",
            "kubernetes",
            "critical",
            Confidence.high,
            "False",
            null,
            List.of("kubernetes.node_conditions.Ready.status"),
            "Kubernetes reports the node as not ready.",
            "Correlate node conditions with kubelet, runtime, disk, memory, PID, and network evidence.",
            List.of("False", "kubernetes")
        );
        Signal diskLatency = new Signal(
            "disk_io_latency_high",
            "disk",
            "warning",
            Confidence.medium,
            55.0,
            20.0,
            List.of("disk.await_ms"),
            "Block device latency exceeds the RCA threshold.",
            "Inspect queue depth, await, utilization, filesystem, and kernel I/O error evidence.",
            List.of("disk.await_ms=55.0 >= threshold 20.0", "disk, io")
        );

        List<RootCauseCandidate> candidates = builder.build(List.of(nodeNotReady, diskLatency), "fallback");

        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst().cause()).contains("Block device latency");
        assertThat(candidates.getFirst().evidencePaths()).containsExactly("disk.await_ms");
        assertThat(candidates.get(1).cause()).contains("node as not ready");
    }
}
