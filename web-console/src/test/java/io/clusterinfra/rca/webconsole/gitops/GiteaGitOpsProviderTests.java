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

class GiteaGitOpsProviderTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsBranchUpdatesFileAndOpensWipPullRequest() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requests, bodies));
        server.start();

        RcaConsoleProperties properties = new RcaConsoleProperties();
        var config = properties.getGitOps();
        config.setEnabled(true);
        config.setProvider("gitea");
        config.setRepository("acme/rca-config");
        config.setToken("gitea-token-secret");
        config.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        GiteaGitOpsProvider provider = new GiteaGitOpsProvider(
            properties, new ObjectMapper(), HttpClient.newHttpClient()
        );

        var result = provider.createPullRequest(change(), "{\"version\":1}", "Catalog update", "Reviewed change");

        assertThat(result.number()).isEqualTo(19);
        assertThat(result.url()).isEqualTo("https://gitea.test/acme/rca-config/pulls/19");
        assertThat(result.headSha()).isEqualTo("commit-sha");
        assertThat(requests).containsExactly(
            "GET /api/v1/repos/acme/rca-config/branches/rca%2Fcatalog-draft-1",
            "POST /api/v1/repos/acme/rca-config/branches",
            "GET /api/v1/repos/acme/rca-config/contents/ops/catalog/override.json?ref=main",
            "PUT /api/v1/repos/acme/rca-config/contents/ops/catalog/override.json",
            "POST /api/v1/repos/acme/rca-config/pulls"
        );
        assertThat(bodies.get(3)).contains("\"sha\":\"blob-sha\"").contains("eyJ2ZXJzaW9uIjoxfQ==");
        assertThat(bodies.getLast()).contains("\"title\":\"WIP: Catalog update\"");
    }

    private void respond(
        HttpExchange exchange,
        List<String> requests,
        List<String> bodies
    ) throws java.io.IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("token gitea-token-secret");
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getPath();
        int status;
        String response;
        if ("GET".equals(exchange.getRequestMethod()) && path.contains("/branches/rca/")) {
            status = 404;
            response = "{\"message\":\"branch not found\"}";
        } else if (path.endsWith("/branches")) {
            status = 201;
            response = "{\"name\":\"rca/catalog-draft-1\"}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.contains("/contents/")) {
            status = 200;
            response = "{\"path\":\"ops/catalog/override.json\",\"sha\":\"blob-sha\"}";
        } else if (path.contains("/contents/")) {
            status = 200;
            response = "{\"commit\":{\"sha\":\"commit-sha\"}}";
        } else {
            status = 201;
            response = "{\"number\":19,\"html_url\":\"https://gitea.test/acme/rca-config/pulls/19\","
                + "\"state\":\"open\",\"draft\":true,\"head\":{\"sha\":\"commit-sha\"}}";
        }
        byte[] encoded = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    private GitOpsChange change() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new GitOpsChange(
            "gitops-1", "catalog_override_draft", "draft-1", "gitea", "acme/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/override.json", null, null,
            GitOpsChangeState.creating, null, GitOpsDeploymentState.pending, null, null, null,
            "operator@example.com", now, now, null, null
        );
    }
}
