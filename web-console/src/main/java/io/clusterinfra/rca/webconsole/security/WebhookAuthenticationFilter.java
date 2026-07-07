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

    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public WebhookAuthenticationFilter(AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public static Set<String> protectedPaths() {
        return Set.of(ALERTMANAGER_PATH);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ALERTMANAGER_PATH.equals(SecurityFilterSupport.path(request));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            access.verifyWebhookToken(
                request.getHeader(HttpHeaders.AUTHORIZATION),
                request.getHeader("X-Webhook-Token")
            );
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            auditFailure(request, exception.getReason());
            SecurityFilterSupport.writeError(
                objectMapper,
                response,
                exception.getStatusCode().value(),
                exception.getReason() == null ? "webhook authentication failed" : exception.getReason()
            );
        }
    }

    private void auditFailure(HttpServletRequest request, String reason) {
        try {
            audit.record(
                "system",
                "alertmanager",
                "webhook.auth_failed",
                "webhook",
                "alertmanager",
                "failed",
                Map.of("reason", reason == null ? "authentication_failed" : reason),
                request
            );
        } catch (RuntimeException ignored) {
            // Authentication failure responses must not depend on audit storage availability.
        }
    }
}
