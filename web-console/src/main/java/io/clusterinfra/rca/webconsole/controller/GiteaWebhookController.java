package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsWebhookResult;
import io.clusterinfra.rca.webconsole.service.GiteaWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GiteaWebhookController {
    private final GiteaWebhookService service;

    public GiteaWebhookController(GiteaWebhookService service) {
        this.service = service;
    }

    @PostMapping("/api/webhooks/gitops/gitea")
    public GitOpsWebhookResult webhook(
        @RequestHeader("X-Gitea-Event") String event,
        @RequestHeader("X-Gitea-Delivery") String deliveryId,
        @RequestHeader(value = "X-Gitea-Signature", required = false) String signature,
        @RequestBody byte[] body,
        HttpServletRequest servletRequest
    ) {
        return service.handle(event, deliveryId, signature, body, servletRequest);
    }
}
