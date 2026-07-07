package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:analysis-concurrency-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.pipeline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
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

    @Test
    void tenConcurrentWorkersNeverClaimTheSameTaskTwice() throws Exception {
        var cluster = clusters.create(new ClusterCreateRequest("ten-worker-claim-test", "test", null));
        IntStream.range(0, 12).forEach(index -> evidence.saveAndEnqueue(
            new EvidenceBundle(
                null,
                cluster.clusterId(),
                "worker-" + index,
                "DiskPressure",
                Instant.now(),
                Map.of("disk", Map.of("root_usage_percent", 90 + index))
            ),
            "ten_worker_concurrency_test",
            false,
            3
        ));
        Instant claimAt = Instant.now();
        Instant leaseUntil = claimAt.plusSeconds(30);
        CyclicBarrier barrier = new CyclicBarrier(10);

        List<AnalysisTask> combined = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            List<Future<List<AnalysisTask>>> futures = IntStream.range(0, 10)
                .mapToObj(index -> executor.submit(workerClaim(
                    "claim-worker-" + index,
                    barrier,
                    claimAt,
                    leaseUntil
                )))
                .toList();
            for (Future<List<AnalysisTask>> future : futures) {
                combined.addAll(future.get());
            }
        }

        assertThat(combined).isNotEmpty();
        Set<String> uniqueTaskIds = new HashSet<>(combined.stream().map(AnalysisTask::taskId).toList());
        assertThat(uniqueTaskIds).hasSameSizeAs(combined);
        combined.forEach(task -> assertThat(task.attemptCount()).isEqualTo(1));
    }

    @Test
    void workerCrashRetryAndDeadLetterFlowIsLeaseOwnerBound() {
        var cluster = clusters.create(new ClusterCreateRequest("worker-crash-retry-test", "test", null));
        AnalysisTask queued = evidence.saveAndEnqueue(
            new EvidenceBundle(
                null,
                cluster.clusterId(),
                "worker-a",
                "DiskPressure",
                Instant.now(),
                Map.of("disk", Map.of("root_usage_percent", 96))
            ),
            "crash_retry_test",
            false,
            2
        );
        Instant claimAt = Instant.now();
        List<AnalysisTask> firstClaim = tasks.claim(
            "crashed-worker",
            1,
            claimAt,
            claimAt.plusSeconds(30)
        );
        assertThat(firstClaim).hasSize(1);

        assertThat(tasks.fail(
            firstClaim.getFirst(),
            "wrong-owner",
            "should not update",
            claimAt.plusSeconds(5)
        )).isFalse();
        assertThat(tasks.fail(
            firstClaim.getFirst(),
            "crashed-worker",
            "temporary failure",
            claimAt.plusSeconds(15)
        )).isTrue();
        assertThat(tasks.claim("early-worker", 1, claimAt.plusSeconds(10), claimAt.plusSeconds(40)))
            .isEmpty();

        List<AnalysisTask> retryClaim = tasks.claim(
            "retry-worker",
            1,
            claimAt.plusSeconds(16),
            claimAt.plusSeconds(46)
        );
        assertThat(retryClaim).hasSize(1);
        assertThat(retryClaim.getFirst().taskId()).isEqualTo(queued.taskId());
        assertThat(retryClaim.getFirst().attemptCount()).isEqualTo(2);

        assertThat(tasks.fail(
            retryClaim.getFirst(),
            "retry-worker",
            "provider still unavailable",
            claimAt.plusSeconds(20)
        )).isTrue();
        assertThat(tasks.find(queued.taskId()).orElseThrow().status().name()).isEqualTo("dead_letter");
        assertThat(tasks.retry(queued.taskId()).orElseThrow().status().name()).isEqualTo("queued");
    }

    private Callable<List<AnalysisTask>> workerClaim(
        String leaseOwner,
        CyclicBarrier barrier,
        Instant claimAt,
        Instant leaseUntil
    ) {
        return () -> {
            barrier.await();
            return tasks.claim(leaseOwner, 2, claimAt, leaseUntil);
        };
    }
}
