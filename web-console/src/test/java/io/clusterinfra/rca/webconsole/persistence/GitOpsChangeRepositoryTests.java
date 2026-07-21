package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsDeploymentState;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GitOpsChangeRepositoryTests {
    private GitOpsChangeRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:gitops-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new GitOpsChangeRepository(new JdbcTemplate(dataSource));
    }

    @Test
    void sourceClaimIsIdempotentAndTracksPullRequestLifecycle() {
        var first = repository.createPending(
            "catalog_override_draft", "draft-1", "github", "acme/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", "operator@example.com"
        );
        var duplicate = repository.createPending(
            "catalog_override_draft", "draft-1", "github", "acme/rca-config",
            "rca/catalog-draft-1-other", "main", "ops/catalog/override.json", "operator@example.com"
        );

        assertThat(first.claimed()).isTrue();
        assertThat(duplicate.claimed()).isFalse();
        assertThat(duplicate.change().changeId()).isEqualTo(first.change().changeId());

        var opened = repository.markOpened(first.change().changeId(), 42, "https://github.test/pr/42", "abc123", "open");
        assertThat(opened.pullRequestState()).isEqualTo(GitOpsChangeState.open);

        var merged = repository.syncPullRequest(
            "github", "acme/rca-config", 42, "closed", true, "https://github.test/pr/42", "def456"
        ).orElseThrow();
        assertThat(merged.pullRequestState()).isEqualTo(GitOpsChangeState.merged);
        assertThat(merged.headSha()).isEqualTo("def456");

        var deploying = repository.updateOutcome(
            merged.changeId(), GitOpsDeploymentState.in_progress, "canary started", null
        ).orElseThrow();
        assertThat(deploying.deploymentStartedAt()).isNotNull();
        var succeeded = repository.updateOutcome(
            merged.changeId(), GitOpsDeploymentState.succeeded, "checksum verified", null
        ).orElseThrow();
        assertThat(succeeded.deploymentCompletedAt()).isNotNull();
        assertThat(succeeded.verificationResult()).isEqualTo("checksum verified");
    }

    @Test
    void webhookDeliveryCanOnlyBeClaimedOnce() {
        assertThat(repository.claimWebhookDelivery("delivery-1", "github", "pull_request")).isTrue();
        assertThat(repository.claimWebhookDelivery("delivery-1", "github", "pull_request")).isFalse();
    }

    @Test
    void failedChangeCanBeClaimedForOneReconciliationAttempt() {
        var pending = repository.createPending(
            "catalog_override_draft", "draft-retry", "github", "acme/rca-config",
            "rca/catalog-draft-retry", "main", "ops/catalog/override.json", "operator@example.com"
        ).change();
        var failed = repository.markFailed(pending.changeId(), "temporary provider failure");
        assertThat(failed.pullRequestState()).isEqualTo(GitOpsChangeState.failed);
        assertThat(failed.lastFailureAt()).isNotNull();

        var first = repository.claimRetry(pending.changeId());
        var duplicate = repository.claimRetry(pending.changeId());

        assertThat(first.claimed()).isTrue();
        assertThat(first.change().pullRequestState()).isEqualTo(GitOpsChangeState.reconciling);
        assertThat(first.change().retryCount()).isEqualTo(1);
        assertThat(duplicate.claimed()).isFalse();
        assertThat(duplicate.change().retryCount()).isEqualTo(1);
    }

    @Test
    void concurrentSourceClaimsProduceOneOwnerAndOneChange() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            Callable<GitOpsChangeRepository.PendingClaim> task = () -> repository.createPending(
                "catalog_override_draft", "draft-concurrent", "github", "acme/rca-config",
                "rca/catalog-draft-concurrent", "main", "ops/catalog/override.json", "operator@example.com"
            );
            var futures = executor.invokeAll(java.util.Collections.nCopies(8, task));
            var claims = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();

            assertThat(claims).filteredOn(GitOpsChangeRepository.PendingClaim::claimed).hasSize(1);
            assertThat(claims).extracting(claim -> claim.change().changeId()).containsOnly(
                claims.getFirst().change().changeId()
            );
        }
    }
}
