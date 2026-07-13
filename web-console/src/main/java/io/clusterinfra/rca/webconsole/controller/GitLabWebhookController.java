package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsWebhookResult;
import io.clusterinfra.rca.webconsole.service.GitLabWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitLabWebhookController {
    private final GitLabWebhookService service;

    public GitLabWebhookController(GitLabWebhookService service) {
        this.service = service;
    }

    @PostMapping("/api/webhooks/gitops/gitlab")
    public GitOpsWebhookResult webhook(
        @RequestHeader("X-Gitlab-Event") String event,
        @RequestHeader("X-Gitlab-Event-UUID") String deliveryId,
        @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
        @RequestBody byte[] body,
        HttpServletRequest servletRequest
    ) {
        return service.handle(event, deliveryId, token, body, servletRequest);
    }
}
