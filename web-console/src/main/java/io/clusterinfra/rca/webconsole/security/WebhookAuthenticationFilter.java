package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WebhookAuthenticationFilter extends OncePerRequestFilter {
    private static final String ALERTMANAGER_PATH = "/api/webhooks/alertmanager";
    private static final String GITHUB_GITOPS_PATH = "/api/webhooks/gitops/github";
    private static final String GITLAB_GITOPS_PATH = "/api/webhooks/gitops/gitlab";
    private static final String GITEA_GITOPS_PATH = "/api/webhooks/gitops/gitea";

    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public WebhookAuthenticationFilter(AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public static Set<String> protectedPaths() {
        return Set.of(ALERTMANAGER_PATH, GITHUB_GITOPS_PATH, GITLAB_GITOPS_PATH, GITEA_GITOPS_PATH);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !protectedPaths().contains(SecurityFilterSupport.path(request));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            if (GITHUB_GITOPS_PATH.equals(SecurityFilterSupport.path(request))) {
                requireGitHubHeaders(request);
                filterChain.doFilter(request, response);
                return;
            }
            if (GITLAB_GITOPS_PATH.equals(SecurityFilterSupport.path(request))) {
                requireGitLabHeaders(request);
                filterChain.doFilter(request, response);
                return;
            }
            if (GITEA_GITOPS_PATH.equals(SecurityFilterSupport.path(request))) {
                requireGiteaHeaders(request);
                filterChain.doFilter(request, response);
                return;
            }
            access.verifyWebhookToken(
                request.getHeader(HttpHeaders.AUTHORIZATION),
                request.getHeader("X-Webhook-Token")
            );
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            auditFailure(request, exception.getReason());
            SecurityFilterSupport.writeError(
                objectMapper,
                request,
                response,
                exception.getStatusCode().value(),
                exception.getReason() == null ? "webhook authentication failed" : exception.getReason()
            );
        }
    }

    private void requireGitHubHeaders(HttpServletRequest request) {
        if (blank(request.getHeader("X-GitHub-Event"))
            || blank(request.getHeader("X-GitHub-Delivery"))
            || blank(request.getHeader("X-Hub-Signature-256"))) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "GitHub webhook signature headers are required"
            );
        }
    }

    private void requireGitLabHeaders(HttpServletRequest request) {
        if (blank(request.getHeader("X-Gitlab-Event"))
            || blank(request.getHeader("X-Gitlab-Event-UUID"))
            || blank(request.getHeader("X-Gitlab-Token"))) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "GitLab webhook authentication headers are required"
            );
        }
    }

    private void requireGiteaHeaders(HttpServletRequest request) {
        if (blank(request.getHeader("X-Gitea-Event"))
            || blank(request.getHeader("X-Gitea-Delivery"))
            || blank(request.getHeader("X-Gitea-Signature"))) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Gitea webhook signature headers are required"
            );
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void auditFailure(HttpServletRequest request, String reason) {
        try {
            String path = SecurityFilterSupport.path(request);
            boolean github = GITHUB_GITOPS_PATH.equals(path);
            boolean gitlab = GITLAB_GITOPS_PATH.equals(path);
            boolean gitea = GITEA_GITOPS_PATH.equals(path);
            String provider = github ? "github" : gitlab ? "gitlab" : gitea ? "gitea" : "alertmanager";
            audit.record(
                "system",
                provider,
                "webhook.auth_failed",
                "webhook",
                github || gitlab || gitea ? provider + "-gitops" : "alertmanager",
                "failed",
                Map.of("reason", reason == null ? "authentication_failed" : reason),
                request
            );
        } catch (RuntimeException ignored) {
            // Authentication failure responses must not depend on audit storage availability.
        }
    }
}
