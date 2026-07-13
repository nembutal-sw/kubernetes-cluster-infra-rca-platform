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

class GitLabGitOpsProviderTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsBranchCommitAndDraftMergeRequestForNestedProject() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requests, bodies));
        server.start();

        RcaConsoleProperties properties = new RcaConsoleProperties();
        var config = properties.getGitOps();
        config.setEnabled(true);
        config.setProvider("gitlab");
        config.setRepository("acme/platform/rca-config");
        config.setToken("gitlab-token-secret");
        config.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GitLabGitOpsProvider provider = new GitLabGitOpsProvider(
            properties, new ObjectMapper(), HttpClient.newHttpClient()
        );

        var result = provider.createPullRequest(change(), "{\"version\":1}", "Catalog update", "Reviewed change");

        assertThat(result.number()).isEqualTo(73);
        assertThat(result.url()).isEqualTo("https://gitlab.test/acme/platform/rca-config/-/merge_requests/73");
        assertThat(result.headSha()).isEqualTo("commit-sha");
        assertThat(requests).containsExactly(
            "POST /projects/acme%2Fplatform%2Frca-config/repository/branches?branch=rca%2Fcatalog-draft-1&ref=main",
            "GET /projects/acme%2Fplatform%2Frca-config/repository/files/ops%2Fcatalog%2Foverride.json?ref=main",
            "POST /projects/acme%2Fplatform%2Frca-config/repository/files/ops%2Fcatalog%2Foverride.json",
            "GET /projects/acme%2Fplatform%2Frca-config/repository/branches/rca%2Fcatalog-draft-1",
            "POST /projects/acme%2Fplatform%2Frca-config/merge_requests"
        );
        assertThat(bodies.getLast()).contains("\"title\":\"Draft: Catalog update\"");
    }

    private void respond(
        HttpExchange exchange,
        List<String> requests,
        List<String> bodies
    ) throws java.io.IOException {
        assertThat(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN")).isEqualTo("gitlab-token-secret");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getRawPath();
        int status;
        String response;
        if (path.endsWith("/repository/branches")) {
            status = 201;
            response = "{\"name\":\"rca/catalog-draft-1\"}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.contains("/repository/files/")) {
            status = 404;
            response = "{\"message\":\"404 File Not Found\"}";
        } else if (path.contains("/repository/files/")) {
            status = 201;
            response = "{\"file_path\":\"ops/catalog/override.json\",\"branch\":\"rca/catalog-draft-1\"}";
        } else if (path.contains("/repository/branches/")) {
            status = 200;
            response = "{\"name\":\"rca/catalog-draft-1\",\"commit\":{\"id\":\"commit-sha\"}}";
        } else {
            status = 201;
            response = "{\"iid\":73,\"web_url\":\"https://gitlab.test/acme/platform/rca-config/"
                + "-/merge_requests/73\",\"state\":\"opened\",\"sha\":\"commit-sha\"}";
        }
        byte[] encoded = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    private GitOpsChange change() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new GitOpsChange(
            "gitops-1", "catalog_override_draft", "draft-1", "gitlab", "acme/platform/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", null, null,
            GitOpsChangeState.creating, null, GitOpsDeploymentState.pending, null, null, null,
            "operator@example.com", now, now, null, null
        );
    }
}
