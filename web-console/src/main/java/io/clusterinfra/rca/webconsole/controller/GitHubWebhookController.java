package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsWebhookResult;
import io.clusterinfra.rca.webconsole.service.GitHubWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitHubWebhookController {
    private final GitHubWebhookService service;

    public GitHubWebhookController(GitHubWebhookService service) {
        this.service = service;
    }

    @PostMapping("/api/webhooks/gitops/github")
    public GitOpsWebhookResult webhook(
        @RequestHeader("X-GitHub-Event") String event,
        @RequestHeader("X-GitHub-Delivery") String deliveryId,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestBody byte[] body,
        HttpServletRequest servletRequest
    ) {
        return service.handle(event, deliveryId, signature, body, servletRequest);
    }
}
