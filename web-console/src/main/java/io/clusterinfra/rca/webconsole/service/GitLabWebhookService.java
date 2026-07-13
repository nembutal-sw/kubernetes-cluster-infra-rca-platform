package io.clusterinfra.rca.webconsole.service;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsWebhookResult;
import io.clusterinfra.rca.webconsole.persistence.GitOpsChangeRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitLabWebhookService {
    private static final String PROVIDER = "gitlab";
    private static final String MERGE_REQUEST_EVENT = "Merge Request Hook";

    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final GitOpsChangeRepository changes;
    private final AuditService audit;

    public GitLabWebhookService(
        RcaConsoleProperties properties,
        ObjectMapper objectMapper,
        GitOpsChangeRepository changes,
        AuditService audit
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.changes = changes;
        this.audit = audit;
    }

    public GitOpsWebhookResult handle(
        String event,
        String deliveryId,
        String suppliedToken,
        byte[] body,
        HttpServletRequest servletRequest
    ) {
        validateHeaders(event, deliveryId);
        try {
            verifyToken(suppliedToken);
        } catch (ResponseStatusException exception) {
            audit.record(
                "system", PROVIDER, "gitops.webhook.auth_failed", "gitops_webhook", deliveryId, "rejected",
                Map.of("event", event, "status", exception.getStatusCode().value()), servletRequest
            );
            throw exception;
        }
        JsonNode payload = parse(body);
        if (!changes.claimWebhookDelivery(deliveryId, PROVIDER, event)) {
            audit.record(
                "system", PROVIDER, "gitops.webhook.replay", "gitops_webhook", deliveryId, "rejected",
                Map.of("event", event), servletRequest
            );
            throw new ResponseStatusException(CONFLICT, "GitLab webhook delivery was already processed");
        }

        if (!MERGE_REQUEST_EVENT.equals(event)) {
            auditWebhook(deliveryId, event, "ignored", null, servletRequest);
            return new GitOpsWebhookResult(deliveryId, event, "ignored", null, null);
        }

        JsonNode attributes = payload.path("object_attributes");
        String repository = payload.at("/project/path_with_namespace").asText("");
        long mergeRequestIid = attributes.path("iid").asLong(0);
        if (repository.isBlank() || mergeRequestIid <= 0 || attributes.isMissingNode()) {
            throw new ResponseStatusException(BAD_REQUEST, "GitLab merge request payload is incomplete");
        }
        String state = attributes.path("state").asText("opened");
        boolean merged = "merged".equalsIgnoreCase(state);
        String url = attributes.path("url").asText(null);
        String headSha = attributes.at("/last_commit/id").asText(null);
        GitOpsChange change = changes.syncPullRequest(
            PROVIDER, repository, mergeRequestIid, state, merged, url, headSha
        ).orElse(null);
        String outcome = change == null ? "untracked" : "updated";
        auditWebhook(deliveryId, event, outcome, change, servletRequest);
        return new GitOpsWebhookResult(
            deliveryId,
            event,
            outcome,
            change == null ? null : change.changeId(),
            change == null ? null : change.pullRequestState()
        );
    }

    private void validateHeaders(String event, String deliveryId) {
        if (event == null || event.isBlank() || event.length() > 128) {
            throw new ResponseStatusException(BAD_REQUEST, "X-Gitlab-Event is required");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 128) {
            throw new ResponseStatusException(BAD_REQUEST, "X-Gitlab-Event-UUID is required");
        }
    }

    private void verifyToken(String suppliedToken) {
        RcaConsoleProperties.GitOps config = properties.getGitOps();
        String secret = config.getWebhookSecret();
        if (!config.isEnabled() || !PROVIDER.equalsIgnoreCase(config.getProvider()) || secret.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitLab webhook verification is not configured");
        }
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedToken == null
            ? new byte[0]
            : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid GitLab webhook token");
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "GitLab webhook body is invalid JSON");
        }
    }

    private void auditWebhook(
        String deliveryId,
        String event,
        String outcome,
        GitOpsChange change,
        HttpServletRequest servletRequest
    ) {
        audit.record(
            "system",
            PROVIDER,
            "gitops.webhook",
            change == null ? "gitops_webhook" : "gitops_change",
            change == null ? deliveryId : change.changeId(),
            outcome,
            Map.of("delivery_id", deliveryId, "event", event, "tracked", change != null),
            servletRequest
        );
    }
}
