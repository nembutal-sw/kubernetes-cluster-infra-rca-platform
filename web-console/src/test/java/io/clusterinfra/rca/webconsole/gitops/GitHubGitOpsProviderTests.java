package io.clusterinfra.rca.webconsole.gitops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsDeploymentState;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubGitOpsProviderTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsBranchCommitAndDraftPullRequestWithoutLeakingToken() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requests));
        server.start();

        RcaConsoleProperties properties = new RcaConsoleProperties();
        var config = properties.getGitOps();
        config.setEnabled(true);
        config.setRepository("acme/rca-config");
        config.setToken("github-token-secret");
        config.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubGitOpsProvider provider = new GitHubGitOpsProvider(
            properties, new ObjectMapper(), HttpClient.newHttpClient()
        );

        var result = provider.createPullRequest(change(), "{\"version\":1}", "Catalog update", "Reviewed change");

        assertThat(result.number()).isEqualTo(42);
        assertThat(result.url()).isEqualTo("https://github.test/acme/rca-config/pull/42");
        assertThat(result.headSha()).isEqualTo("commit-sha");
        assertThat(requests).containsExactly(
            "GET /repos/acme/rca-config/git/ref/heads/main",
            "GET /repos/acme/rca-config/git/ref/heads/rca%2Fcatalog-draft-1",
            "POST /repos/acme/rca-config/git/refs",
            "GET /repos/acme/rca-config/contents/ops/catalog/override.json?ref=main",
            "PUT /repos/acme/rca-config/contents/ops/catalog/override.json",
            "POST /repos/acme/rca-config/pulls"
        );
    }

    private void respond(HttpExchange exchange, List<String> requests) throws java.io.IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        assertThat(authorization).isEqualTo("Bearer github-token-secret");
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String body;
        if (path.endsWith("/git/ref/heads/main")) {
            body = "{\"object\":{\"sha\":\"base-sha\"}}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.contains("/git/ref/heads/rca/")) {
            status = 404;
            body = "{\"message\":\"Not Found\"}";
        } else if (path.endsWith("/git/refs")) {
            status = 201;
            body = "{}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.contains("/contents/")) {
            status = 404;
            body = "{\"message\":\"Not Found\"}";
        } else if ("PUT".equals(exchange.getRequestMethod()) && path.contains("/contents/")) {
            status = 201;
            body = "{\"commit\":{\"sha\":\"commit-sha\"}}";
        } else {
            status = 201;
            body = "{\"number\":42,\"html_url\":\"https://github.test/acme/rca-config/pull/42\","
                + "\"state\":\"open\",\"head\":{\"sha\":\"commit-sha\"}}";
        }
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    @Test
    void reconciliationReturnsAnExistingPullRequestWithoutCreatingResources() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body = "[{\"number\":41,\"html_url\":\"https://github.test/acme/rca-config/pull/41\","
                + "\"state\":\"closed\",\"merged_at\":null,\"head\":{\"sha\":\"closed-sha\"}},"
                + "{\"number\":42,\"html_url\":\"https://github.test/acme/rca-config/pull/42\","
                + "\"state\":\"open\",\"merged_at\":null,\"head\":{\"sha\":\"existing-sha\"}}]";
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        });
        server.start();
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getGitOps().setEnabled(true);
        properties.getGitOps().setRepository("acme/rca-config");
        properties.getGitOps().setToken("github-token-secret");
        properties.getGitOps().setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubGitOpsProvider provider = new GitHubGitOpsProvider(
            properties, new ObjectMapper(), HttpClient.newHttpClient()
        );

        var result = provider.reconcilePullRequest(change(), "{}", "Catalog update", "Reviewed change");

        assertThat(result.number()).isEqualTo(42);
        assertThat(result.headSha()).isEqualTo("existing-sha");
        assertThat(requests).singleElement().asString().contains("GET /repos/acme/rca-config/pulls?");
    }

    private GitOpsChange change() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new GitOpsChange(
            "gitops-1", "catalog_override_draft", "draft-1", "github", "acme/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", null, null,
            GitOpsChangeState.creating, null, GitOpsDeploymentState.pending, null, null, null,
            "operator@example.com", now, now, null, null
        );
    }
}
