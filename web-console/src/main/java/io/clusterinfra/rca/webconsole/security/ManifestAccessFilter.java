package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ManifestAccessFilter extends OncePerRequestFilter {
    private static final Pattern MANIFEST_PATH =
        Pattern.compile("^/api/clusters/([^/]+)/agent-manifest$");

    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ManifestAccessFilter(AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MANIFEST_PATH.matcher(SecurityFilterSupport.path(request)).matches();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Matcher matcher = MANIFEST_PATH.matcher(SecurityFilterSupport.path(request));
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }
        String clusterId = matcher.group(1);
        try {
            access.verifyManifestAccess(
                clusterId,
                request.getHeader(HttpHeaders.AUTHORIZATION),
                request.getParameter("agent_token"),
                SecurityContextHolder.getContext().getAuthentication()
            );
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            auditFailure(clusterId, exception.getReason());
            SecurityFilterSupport.writeError(
                objectMapper,
                response,
                exception.getStatusCode().value(),
                exception.getReason() == null ? "manifest access denied" : exception.getReason()
            );
        }
    }

    private void auditFailure(String clusterId, String reason) {
        try {
            audit.system(
                "manifest",
                "manifest.auth_failed",
                "cluster",
                clusterId,
                "failed",
                Map.of("reason", reason == null ? "authentication_failed" : reason)
            );
        } catch (RuntimeException ignored) {
            // Authentication failure responses must not depend on audit storage availability.
        }
    }
}
