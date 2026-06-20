package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:analysis-concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.pipeline.enabled=false"
})
class AnalysisTaskConcurrencyTests {
    @Autowired
    private ClusterRepository clusters;

    @Autowired
    private EvidenceRepository evidence;

    @Autowired
    private AnalysisTaskRepository tasks;

    @Test
    void concurrentWorkersCannotClaimTheSameTaskUntilLeaseExpires() throws Exception {
        var cluster = clusters.create(new ClusterCreateRequest("concurrency-test", "test", null));
        AnalysisTask task = evidence.saveAndEnqueue(
            new EvidenceBundle(
                null,
                cluster.clusterId(),
                "worker-a",
                "DiskPressure",
                Instant.now(),
                Map.of("disk", Map.of("root_usage_percent", 95))
            ),
            "concurrency_test",
            false,
            3
        );
        Instant claimAt = Instant.now();
        Instant leaseUntil = claimAt.plusSeconds(30);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<List<AnalysisTask>> workerA = () -> {
                barrier.await();
                return tasks.claim("worker-a", 1, claimAt, leaseUntil);
            };
            Callable<List<AnalysisTask>> workerB = () -> {
                barrier.await();
                return tasks.claim("worker-b", 1, claimAt, leaseUntil);
            };
            Future<List<AnalysisTask>> first = executor.submit(workerA);
            Future<List<AnalysisTask>> second = executor.submit(workerB);

            List<AnalysisTask> combined = new java.util.ArrayList<>(first.get());
            combined.addAll(second.get());
            assertThat(combined).hasSize(1);
            assertThat(combined.getFirst().taskId()).isEqualTo(task.taskId());
            assertThat(combined.getFirst().attemptCount()).isEqualTo(1);
        }

        assertThat(tasks.claim("worker-c", 1, claimAt.plusSeconds(10), claimAt.plusSeconds(40)))
            .isEmpty();
        List<AnalysisTask> reclaimed =
            tasks.claim("worker-c", 1, leaseUntil.plusSeconds(1), leaseUntil.plusSeconds(31));
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().taskId()).isEqualTo(task.taskId());
        assertThat(reclaimed.getFirst().attemptCount()).isEqualTo(2);
    }
}
