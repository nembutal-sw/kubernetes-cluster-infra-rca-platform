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
public class GitHubGitOpsProvider implements GitOpsProvider {
    private static final String API_VERSION = "2022-11-28";

    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GitHubGitOpsProvider(RcaConsoleProperties properties, ObjectMapper objectMapper) {
        this(
            properties,
            objectMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getGitOps().getTimeoutSeconds()))
                .build()
        );
    }

    GitHubGitOpsProvider(
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
        return "github";
    }

    @Override
    public PullRequestResult createPullRequest(
        GitOpsChange change,
        String content,
        String title,
        String body
    ) {
        requireConfigured(change);
        return createPullRequestResources(change, content, title, body);
    }

    @Override
    public PullRequestResult reconcilePullRequest(
        GitOpsChange change,
        String content,
        String title,
        String body
    ) {
        requireConfigured(change);
        PullRequestResult existing = findPullRequest(change);
        return existing != null ? existing : createPullRequestResources(change, content, title, body);
    }

    private PullRequestResult createPullRequestResources(
        GitOpsChange change,
        String content,
        String title,
        String body
    ) {
        String repoPath = "/repos/" + change.repository();
        JsonNode baseRef = request(
            "GET",
            repoPath + "/git/ref/heads/" + encode(change.baseBranch()),
            null,
            200
        );
        String baseSha = requiredText(baseRef, "/object/sha", "base branch SHA");

        JsonNode branchRef = requestAllowNotFound(
            "GET", repoPath + "/git/ref/heads/" + encode(change.branch()), null
        );
        if (branchRef == null) {
            request(
                "POST",
                repoPath + "/git/refs",
                Map.of("ref", "refs/heads/" + change.branch(), "sha", baseSha),
                201
            );
        }

        String contentPath = repoPath + "/contents/" + encodePath(change.filePath());
        JsonNode existing = requestAllowNotFound(
            "GET",
            contentPath + "?ref=" + encode(branchRef == null ? change.baseBranch() : change.branch()),
            null
        );
        Map<String, Object> filePayload = new LinkedHashMap<>();
        filePayload.put("message", "Update RCA operational catalog override " + change.sourceId());
        filePayload.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        filePayload.put("branch", change.branch());
        if (existing != null && existing.path("sha").isTextual()) {
            filePayload.put("sha", existing.path("sha").asText());
        }
        JsonNode commit = request("PUT", contentPath, filePayload, 200, 201);
        String headSha = requiredText(commit, "/commit/sha", "commit SHA");

        JsonNode pullRequest = request(
            "POST",
            repoPath + "/pulls",
            Map.of(
                "title", title,
                "head", change.branch(),
                "base", change.baseBranch(),
                "body", body,
                "draft", true
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

    private PullRequestResult findPullRequest(GitOpsChange change) {
        String owner = change.repository().substring(0, change.repository().indexOf('/'));
        JsonNode matches = request(
            "GET",
            "/repos/" + change.repository() + "/pulls?state=all&head="
                + encode(owner + ":" + change.branch())
                + "&base=" + encode(change.baseBranch()) + "&per_page=10",
            null,
            200
        );
        if (!matches.isArray() || matches.isEmpty()) {
            return null;
        }
        PullRequestResult merged = null;
        for (JsonNode pullRequest : matches) {
            boolean isMerged = !pullRequest.path("merged_at").isMissingNode()
                && !pullRequest.path("merged_at").isNull();
            String state = isMerged ? "merged" : pullRequest.path("state").asText("open");
            PullRequestResult result = new PullRequestResult(
                pullRequest.path("number").asLong(),
                requiredText(pullRequest, "/html_url", "pull request URL"),
                state,
                requiredText(pullRequest, "/head/sha", "head SHA")
            );
            if ("open".equalsIgnoreCase(state)) {
                return result;
            }
            if (isMerged && merged == null) {
                merged = result;
            }
        }
        return merged;
    }

    private void requireConfigured(GitOpsChange change) {
        RcaConsoleProperties.GitOps config = properties.getGitOps();
        if (!config.isEnabled()) {
            throw new GitOpsProviderException("GitOps integration is disabled");
        }
        if (!id().equalsIgnoreCase(config.getProvider())) {
            throw new GitOpsProviderException("Configured GitOps provider is not github");
        }
        if (config.getToken().isBlank()) {
            throw new GitOpsProviderException("GitHub token is not configured");
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
            HttpRequest.BodyPublisher body = payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getGitOps().getApiBaseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getGitOps().getTimeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + properties.getGitOps().getToken())
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Content-Type", "application/json")
                .method(method, body)
                .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GitOpsProviderException("GitHub API request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitOpsProviderException("GitHub API request interrupted", exception);
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
            "GitHub API returned HTTP " + response.statusCode()
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
            throw new GitOpsProviderException("GitHub API returned invalid JSON", exception);
        }
    }

    private String requiredText(JsonNode node, String pointer, String label) {
        String value = node.at(pointer).asText("");
        if (value.isBlank()) {
            throw new GitOpsProviderException("GitHub API response is missing " + label);
        }
        return value;
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
