package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.analysis.LlmEvidenceCatalog;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmEvidenceCatalogTests {
    @Test
    void createsStableReferencesAndResolvesOnlyCatalogEvidence() {
        Signal signal = new Signal(
            "inode_exhaustion",
            "disk",
            "critical",
            Confidence.high,
            98.5,
            95.0,
            List.of("filesystem.inode_used_percent", "filesystem.inode_free"),
            "inode usage crossed the critical threshold",
            "Inspect high-cardinality directories.",
            List.of("inode used=98.5%")
        );

        List<Map<String, Object>> first = LlmEvidenceCatalog.fromSignals(List.of(signal));
        List<Map<String, Object>> second = LlmEvidenceCatalog.fromSignals(List.of(signal));
        Map<String, Map<String, Object>> index = LlmEvidenceCatalog.index(first);
        String evidenceId = String.valueOf(first.getFirst().get("evidence_id"));

        assertThat(evidenceId).matches("ev-[a-f0-9]{16}");
        assertThat(second.getFirst().get("evidence_id")).isEqualTo(evidenceId);
        assertThat(LlmEvidenceCatalog.evidencePaths(List.of(evidenceId), index))
            .containsExactly("filesystem.inode_used_percent", "filesystem.inode_free");
        assertThat(LlmEvidenceCatalog.descriptions(List.of(evidenceId), index).getFirst())
            .contains(evidenceId)
            .contains("inode_exhaustion")
            .contains("critical threshold");
        assertThat(LlmEvidenceCatalog.evidencePaths(List.of("ev-unknown"), index)).isEmpty();
    }
}
