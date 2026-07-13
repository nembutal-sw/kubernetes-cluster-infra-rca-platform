package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.persistence.GitOpsChangeRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class GitLabWebhookServiceTests {
    private static final String SECRET = "gitlab-webhook-secret";

    private GitOpsChangeRepository repository;
    private GitLabWebhookService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:gitlab-webhook-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new GitOpsChangeRepository(new JdbcTemplate(dataSource));
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getGitOps().setEnabled(true);
        properties.getGitOps().setProvider("gitlab");
        properties.getGitOps().setWebhookSecret(SECRET);
        service = new GitLabWebhookService(
            properties,
            new ObjectMapper(),
            repository,
            mock(AuditService.class)
        );
    }

    @Test
    void verifiesTokenUpdatesTrackedMergeRequestAndRejectsReplay() {
        var claim = repository.createPending(
            "catalog_override_draft", "draft-1", "gitlab", "acme/platform/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", "operator@example.com"
        );
        repository.markOpened(
            claim.change().changeId(),
            73,
            "https://gitlab.test/acme/platform/rca-config/-/merge_requests/73",
            "old-sha",
            "opened"
        );
        byte[] payload = payload();

        var result = service.handle(
            "Merge Request Hook", "delivery-1", SECRET, payload, mock(HttpServletRequest.class)
        );

        assertThat(result.outcome()).isEqualTo("updated");
        assertThat(result.state()).isEqualTo(GitOpsChangeState.merged);
        assertThat(repository.find(claim.change().changeId()).orElseThrow().headSha()).isEqualTo("new-sha");
        assertThatThrownBy(() -> service.handle(
            "Merge Request Hook", "delivery-1", SECRET, payload, mock(HttpServletRequest.class)
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already processed");
    }

    @Test
    void rejectsInvalidTokenBeforeClaimingDelivery() {
        byte[] payload = payload();

        assertThatThrownBy(() -> service.handle(
            "Merge Request Hook", "delivery-invalid", "wrong", payload, mock(HttpServletRequest.class)
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("invalid GitLab webhook token");
        assertThat(repository.claimWebhookDelivery("delivery-invalid", "gitlab", "Merge Request Hook")).isTrue();
    }

    private byte[] payload() {
        return ("{\"object_kind\":\"merge_request\","
            + "\"project\":{\"path_with_namespace\":\"acme/platform/rca-config\"},"
            + "\"object_attributes\":{\"iid\":73,\"state\":\"merged\","
            + "\"url\":\"https://gitlab.test/acme/platform/rca-config/-/merge_requests/73\","
            + "\"last_commit\":{\"id\":\"new-sha\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
