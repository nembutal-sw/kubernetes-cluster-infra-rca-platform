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
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class GitHubWebhookServiceTests {
    private static final String SECRET = "webhook-secret-value";

    private GitOpsChangeRepository repository;
    private GitHubWebhookService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:github-webhook-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new GitOpsChangeRepository(new JdbcTemplate(dataSource));
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getGitOps().setEnabled(true);
        properties.getGitOps().setWebhookSecret(SECRET);
        service = new GitHubWebhookService(
            properties,
            new ObjectMapper(),
            repository,
            mock(AuditService.class)
        );
    }

    @Test
    void verifiesSignatureUpdatesTrackedPullRequestAndRejectsReplay() throws Exception {
        var claim = repository.createPending(
            "catalog_override_draft", "draft-1", "github", "acme/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", "operator@example.com"
        );
        repository.markOpened(claim.change().changeId(), 42, "https://github.test/pr/42", "old-sha", "open");
        byte[] payload = payload();
        String signature = signature(payload);

        var result = service.handle(
            "pull_request", "delivery-1", signature, payload, mock(HttpServletRequest.class)
        );

        assertThat(result.outcome()).isEqualTo("updated");
        assertThat(result.state()).isEqualTo(GitOpsChangeState.merged);
        assertThat(repository.find(claim.change().changeId()).orElseThrow().headSha()).isEqualTo("new-sha");
        assertThatThrownBy(() -> service.handle(
            "pull_request", "delivery-1", signature, payload, mock(HttpServletRequest.class)
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already processed");
    }

    @Test
    void rejectsInvalidSignatureBeforeClaimingDelivery() {
        byte[] payload = payload();

        assertThatThrownBy(() -> service.handle(
            "pull_request", "delivery-invalid", "sha256=00", payload, mock(HttpServletRequest.class)
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("invalid GitHub webhook signature");
        assertThat(repository.claimWebhookDelivery("delivery-invalid", "github", "pull_request")).isTrue();
    }

    private byte[] payload() {
        return ("{\"action\":\"closed\",\"number\":42,"
            + "\"repository\":{\"full_name\":\"acme/rca-config\"},"
            + "\"pull_request\":{\"state\":\"closed\",\"merged\":true,"
            + "\"html_url\":\"https://github.test/pr/42\",\"head\":{\"sha\":\"new-sha\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
