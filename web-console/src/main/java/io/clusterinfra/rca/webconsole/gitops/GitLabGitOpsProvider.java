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
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GitLabGitOpsProvider implements GitOpsProvider {
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GitLabGitOpsProvider(RcaConsoleProperties properties, ObjectMapper objectMapper) {
        this(
            properties,
            objectMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getGitOps().getTimeoutSeconds()))
                .build()
        );
    }

    GitLabGitOpsProvider(
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
        return "gitlab";
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
        PullRequestResult existing = findMergeRequest(change);
        return existing != null ? existing : createPullRequestResources(change, content, title, body);
    }

    private PullRequestResult createPullRequestResources(
        GitOpsChange change,
        String content,
        String title,
        String body
    ) {
        String projectPath = "/projects/" + encode(change.repository());
        JsonNode branch = requestAllowNotFound(
            "GET", projectPath + "/repository/branches/" + encode(change.branch()), null
        );
        if (branch == null) {
            request(
                "POST",
                projectPath + "/repository/branches?branch=" + encode(change.branch())
                    + "&ref=" + encode(change.baseBranch()),
                null,
                201
            );
        }

        String filePath = projectPath + "/repository/files/" + encode(change.filePath());
        boolean exists = requestAllowNotFound(
            "GET",
            filePath + "?ref=" + encode(branch == null ? change.baseBranch() : change.branch()),
            null
        ) != null;
        Map<String, Object> filePayload = new LinkedHashMap<>();
        filePayload.put("branch", change.branch());
        filePayload.put("content", content);
        filePayload.put("commit_message", "Update RCA operational catalog override " + change.sourceId());
        request(exists ? "PUT" : "POST", filePath, filePayload, exists ? 200 : 201);
        JsonNode updatedBranch = request(
            "GET",
            projectPath + "/repository/branches/" + encode(change.branch()),
            null,
            200
        );
        String headSha = requiredText(updatedBranch, "/commit/id", "commit SHA");

        JsonNode mergeRequest = request(
            "POST",
            projectPath + "/merge_requests",
            Map.of(
                "source_branch", change.branch(),
                "target_branch", change.baseBranch(),
                "title", draftTitle(title),
                "description", body,
                "remove_source_branch", false
            ),
            201
        );
        return new PullRequestResult(
            mergeRequest.path("iid").asLong(),
            requiredText(mergeRequest, "/web_url", "merge request URL"),
            mergeRequest.path("state").asText("opened"),
            mergeRequest.path("sha").asText(headSha)
        );
    }

    private PullRequestResult findMergeRequest(GitOpsChange change) {
        JsonNode matches = request(
            "GET",
            "/projects/" + encode(change.repository()) + "/merge_requests?scope=all&state=all"
                + "&source_branch=" + encode(change.branch())
                + "&target_branch=" + encode(change.baseBranch()) + "&per_page=10",
            null,
            200
        );
        if (!matches.isArray() || matches.isEmpty()) {
            return null;
        }
        PullRequestResult merged = null;
        for (JsonNode mergeRequest : matches) {
            String state = mergeRequest.path("state").asText("opened");
            PullRequestResult result = new PullRequestResult(
                mergeRequest.path("iid").asLong(),
                requiredText(mergeRequest, "/web_url", "merge request URL"),
                state,
                requiredText(mergeRequest, "/sha", "head SHA")
            );
            if ("opened".equalsIgnoreCase(state) || "open".equalsIgnoreCase(state)) {
                return result;
            }
            if ("merged".equalsIgnoreCase(state) && merged == null) {
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
            throw new GitOpsProviderException("Configured GitOps provider is not gitlab");
        }
        if (config.getToken().isBlank()) {
            throw new GitOpsProviderException("GitLab token is not configured");
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
                .header("PRIVATE-TOKEN", properties.getGitOps().getToken())
                .header("Content-Type", "application/json")
                .method(method, requestBody)
                .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GitOpsProviderException("GitLab API request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitOpsProviderException("GitLab API request interrupted", exception);
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
            "GitLab API returned HTTP " + response.statusCode()
                + (responseMessage.isBlank() ? "" : ": " + responseMessage)
        );
    }

    private String safeErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("message");
            String message = error.isTextual() ? error.asText("").trim() : "";
            return message.length() <= 500 ? message : message.substring(0, 500);
        } catch (IOException exception) {
            return "";
        }
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException exception) {
            throw new GitOpsProviderException("GitLab API returned invalid JSON", exception);
        }
    }

    private String requiredText(JsonNode node, String pointer, String label) {
        String value = node.at(pointer).asText("");
        if (value.isBlank()) {
            throw new GitOpsProviderException("GitLab API response is missing " + label);
        }
        return value;
    }

    private String draftTitle(String title) {
        return title.regionMatches(true, 0, "Draft:", 0, "Draft:".length())
            ? title
            : "Draft: " + title;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
