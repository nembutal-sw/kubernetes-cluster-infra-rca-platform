package io.clusterinfra.rca.webconsole.gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GiteaGitOpsProvider implements GitOpsProvider {
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GiteaGitOpsProvider(RcaConsoleProperties properties, ObjectMapper objectMapper) {
        this(
            properties,
            objectMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getGitOps().getTimeoutSeconds()))
                .build()
        );
    }

    GiteaGitOpsProvider(
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "gitea";
    }

    @Override
    public PullRequestResult createPullRequest(
        GitOpsChange change,
        String content,
        String title,
        String body
    ) {
        requireConfigured(change);
        String[] repository = change.repository().split("/", -1);
        if (repository.length != 2) {
            throw new GitOpsProviderException("Gitea repository must use owner/repository format");
        }
        String repoPath = "/repos/" + encode(repository[0]) + "/" + encode(repository[1]);
        request(
            "POST",
            repoPath + "/branches",
            Map.of(
                "new_branch_name", change.branch(),
                "old_branch_name", change.baseBranch()
            ),
            201
        );

        String contentPath = repoPath + "/contents/" + encodePath(change.filePath());
        JsonNode existing = requestAllowNotFound(
            "GET",
            contentPath + "?ref=" + encode(change.baseBranch()),
            null
        );
        Map<String, Object> filePayload = new LinkedHashMap<>();
        filePayload.put("branch", change.branch());
        filePayload.put("message", "Update RCA operational catalog override " + change.sourceId());
        filePayload.put(
            "content",
            Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))
        );
        boolean exists = existing != null && existing.path("sha").isTextual();
        if (exists) {
            filePayload.put("sha", existing.path("sha").asText());
        }
        JsonNode commit = request(exists ? "PUT" : "POST", contentPath, filePayload, exists ? 200 : 201);
        String headSha = requiredText(commit, "/commit/sha", "commit SHA");

        JsonNode pullRequest = request(
            "POST",
            repoPath + "/pulls",
            Map.of(
                "head", change.branch(),
                "base", change.baseBranch(),
                "title", draftTitle(title),
                "body", body
            ),
            201
        );
        return new PullRequestResult(
            pullRequest.path("number").asLong(),
            requiredText(pullRequest, "/html_url", "pull request URL"),
            pullRequest.path("state").asText("open"),
            pullRequest.at("/head/sha").asText(headSha)
        );
    }

    private void requireConfigured(GitOpsChange change) {
        RcaConsoleProperties.GitOps config = properties.getGitOps();
        if (!config.isEnabled()) {
            throw new GitOpsProviderException("GitOps integration is disabled");
        }
        if (!id().equalsIgnoreCase(config.getProvider())) {
            throw new GitOpsProviderException("Configured GitOps provider is not gitea");
        }
        if (config.getApiBaseUrl().isBlank()) {
            throw new GitOpsProviderException("Gitea API base URL is not configured");
        }
        if (config.getToken().isBlank()) {
            throw new GitOpsProviderException("Gitea token is not configured");
        }
        if (!config.getRepository().equals(change.repository())) {
            throw new GitOpsProviderException("GitOps repository does not match the tracked change");
        }
    }

    private JsonNode requestAllowNotFound(String method, String path, Object payload) {
        HttpResponse<String> response = send(method, path, payload);
        if (response.statusCode() == 404) {
            return null;
        }
        ensureStatus(response, 200);
        return read(response.body());
    }

    private JsonNode request(String method, String path, Object payload, int... expectedStatuses) {
        HttpResponse<String> response = send(method, path, payload);
        ensureStatus(response, expectedStatuses);
        return read(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object payload) {
        try {
            HttpRequest.BodyPublisher requestBody = payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(payload),
                    StandardCharsets.UTF_8
                );
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getGitOps().getApiBaseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getGitOps().getTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", "token " + properties.getGitOps().getToken())
                .header("Content-Type", "application/json")
                .method(method, requestBody)
                .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GitOpsProviderException("Gitea API request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitOpsProviderException("Gitea API request interrupted", exception);
        }
    }

    private void ensureStatus(HttpResponse<String> response, int... expectedStatuses) {
        for (int expected : expectedStatuses) {
            if (response.statusCode() == expected) {
                return;
            }
        }
        String responseMessage = safeErrorMessage(response.body());
        throw new GitOpsProviderException(
            "Gitea API returned HTTP " + response.statusCode()
                + (responseMessage.isBlank() ? "" : ": " + responseMessage)
        );
    }

    private String safeErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            String message = objectMapper.readTree(responseBody).path("message").asText("").trim();
            return message.length() <= 500 ? message : message.substring(0, 500);
        } catch (IOException exception) {
            return "";
        }
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException exception) {
            throw new GitOpsProviderException("Gitea API returned invalid JSON", exception);
        }
    }

    private String requiredText(JsonNode node, String pointer, String label) {
        String value = node.at(pointer).asText("");
        if (value.isBlank()) {
            throw new GitOpsProviderException("Gitea API response is missing " + label);
        }
        return value;
    }

    private String draftTitle(String title) {
        return title.regionMatches(true, 0, "WIP:", 0, "WIP:".length())
            ? title
            : "WIP: " + title;
    }

    private String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/"))
            .filter(segment -> !segment.isBlank())
            .map(this::encode)
            .reduce((left, right) -> left + "/" + right)
            .orElseThrow(() -> new GitOpsProviderException("GitOps file path is empty"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
