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
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubWebhookService {
    private static final String PROVIDER = "github";

    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final GitOpsChangeRepository changes;
    private final AuditService audit;

    public GitHubWebhookService(
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
        String signature,
        byte[] body,
        HttpServletRequest servletRequest
    ) {
        validateHeaders(event, deliveryId);
        try {
            verifySignature(signature, body);
        } catch (ResponseStatusException exception) {
            audit.record(
                "system", "github", "gitops.webhook.auth_failed", "gitops_webhook", deliveryId, "rejected",
                Map.of("event", event, "status", exception.getStatusCode().value()), servletRequest
            );
            throw exception;
        }
        JsonNode payload = parse(body);
        if (!changes.claimWebhookDelivery(deliveryId, PROVIDER, event)) {
            audit.record(
                "system", "github", "gitops.webhook.replay", "gitops_webhook", deliveryId, "rejected",
                Map.of("event", event), servletRequest
            );
            throw new ResponseStatusException(CONFLICT, "GitHub webhook delivery was already processed");
        }

        if ("ping".equals(event)) {
            auditWebhook(deliveryId, event, "accepted", null, servletRequest);
            return new GitOpsWebhookResult(deliveryId, event, "accepted", null, null);
        }
        if (!"pull_request".equals(event)) {
            auditWebhook(deliveryId, event, "ignored", null, servletRequest);
            return new GitOpsWebhookResult(deliveryId, event, "ignored", null, null);
        }

        String repository = payload.at("/repository/full_name").asText("");
        long pullRequestNumber = payload.path("number").asLong(0);
        JsonNode pullRequest = payload.path("pull_request");
        if (repository.isBlank() || pullRequestNumber <= 0 || pullRequest.isMissingNode()) {
            throw new ResponseStatusException(BAD_REQUEST, "GitHub pull_request payload is incomplete");
        }
        boolean merged = pullRequest.path("merged").asBoolean(false);
        String state = pullRequest.path("state").asText("open");
        String url = pullRequest.path("html_url").asText(null);
        String headSha = pullRequest.at("/head/sha").asText(null);
        GitOpsChange change = changes.syncPullRequest(
            PROVIDER, repository, pullRequestNumber, state, merged, url, headSha
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
        if (event == null || event.isBlank() || event.length() > 64) {
            throw new ResponseStatusException(BAD_REQUEST, "X-GitHub-Event is required");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 128) {
            throw new ResponseStatusException(BAD_REQUEST, "X-GitHub-Delivery is required");
        }
    }

    private void verifySignature(String suppliedSignature, byte[] body) {
        String secret = properties.getGitOps().getWebhookSecret();
        if (!properties.getGitOps().isEnabled() || secret.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitHub webhook verification is not configured");
        }
        if (suppliedSignature == null || !suppliedSignature.startsWith("sha256=")) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid GitHub webhook signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body == null ? new byte[0] : body);
            byte[] supplied = HexFormat.of().parseHex(suppliedSignature.substring("sha256=".length()));
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new ResponseStatusException(UNAUTHORIZED, "invalid GitHub webhook signature");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid GitHub webhook signature");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("could not verify GitHub webhook signature", exception);
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "GitHub webhook body is invalid JSON");
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
            "github",
            "gitops.webhook",
            change == null ? "gitops_webhook" : "gitops_change",
            change == null ? deliveryId : change.changeId(),
            outcome,
            Map.of(
                "delivery_id", deliveryId,
                "event", event,
                "tracked", change != null
            ),
            servletRequest
        );
    }
}
