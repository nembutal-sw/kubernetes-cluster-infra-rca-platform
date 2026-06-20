package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:evidence-redaction;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.pipeline.enabled=false"
})
class EvidenceRedactionIntegrationTests {
    @Autowired
    private ClusterRepository clusters;

    @Autowired
    private EvidenceRepository evidence;

    @Test
    void persistedEvidenceDoesNotContainCredentials() {
        var cluster = clusters.create(new ClusterCreateRequest("redaction-test", "test", null));
        EvidenceBundle saved = evidence.save(new EvidenceBundle(
            null,
            cluster.clusterId(),
            "worker-a",
            "NodeNotReady",
            Instant.now(),
            Map.of(
                "runtime", Map.of(
                    "authorization", "Bearer database-token",
                    "messages", List.of("password=database-password connection failed")
                )
            )
        ));

        String stored = evidence.find(saved.evidenceId()).orElseThrow().collectors().toString();
        assertThat(stored)
            .contains("[redacted]")
            .doesNotContain("database-token")
            .doesNotContain("database-password");
    }
}
