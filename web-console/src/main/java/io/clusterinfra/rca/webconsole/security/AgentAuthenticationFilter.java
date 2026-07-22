package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AgentAuthenticationFilter extends OncePerRequestFilter {
    private static final String REGISTER_PATH = "/api/agents/register";
    private static final Set<String> AGENT_PATHS = Set.of(
        REGISTER_PATH,
        "/api/agents/heartbeat",
        "/api/agents/evidence-requests",
        "/api/agents/evidence-responses",
        "/api/agents/realtime-events",
        "/api/agents/token/rotate",
        "/api/agents/action-executions",
        "/api/agents/action-results"
    );

    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public AgentAuthenticationFilter(AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public static Set<String> protectedPaths() {
        return AGENT_PATHS;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AGENT_PATHS.contains(SecurityFilterSupport.path(request));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        String path = SecurityFilterSupport.path(request);
        String clusterId = null;
        String nodeName = null;
        try {
            JsonNode body = parseBody(wrapped);
            clusterId = requiredText(body, "cluster_id");
            String bearerToken = PlatformAuthenticationFilter.bearerToken(
                wrapped.getHeader("Authorization")
            );
            if (REGISTER_PATH.equals(path)) {
                access.verifyBootstrapToken(
                    clusterId,
                    credential(bearerToken, optionalText(body, "agent_token"))
                );
            } else {
                nodeName = requiredText(body, "node_name");
                access.verifyNodeIdentity(
                    clusterId,
                    nodeName,
                    credential(bearerToken, optionalText(body, "node_token"))
                );
            }
            request.setAttribute("rca.authenticated_cluster_id", clusterId);
            request.setAttribute("rca.authenticated_node_name", nodeName);
            filterChain.doFilter(wrapped, response);
        } catch (ResponseStatusException exception) {
            auditFailure(wrapped, path, clusterId, nodeName, exception.getReason());
            SecurityFilterSupport.writeError(
                objectMapper,
                wrapped,
                response,
                exception.getStatusCode().value(),
                exception.getReason() == null ? "agent authentication failed" : exception.getReason()
            );
        }
    }

    private JsonNode parseBody(CachedBodyHttpServletRequest request) {
        if (request.body().length == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent credentials required");
        }
        try {
            JsonNode body = objectMapper.readTree(request.body());
            if (body == null || !body.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object body required");
            }
            return body;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed JSON request");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body could not be read");
        }
    }

    private String requiredText(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent credentials required");
        }
        return value;
    }

    private String optionalText(JsonNode body, String field) {
        return body.path(field).asText("").trim();
    }

    private String credential(String bearerToken, String legacyBodyToken) {
        boolean hasBearer = bearerToken != null && !bearerToken.isBlank();
        boolean hasLegacy = legacyBodyToken != null && !legacyBodyToken.isBlank();
        if (hasBearer && hasLegacy && !bearerToken.equals(legacyBodyToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "conflicting agent credentials");
        }
        if (hasBearer) {
            return bearerToken;
        }
        if (hasLegacy) {
            return legacyBodyToken;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent credentials required");
    }

    private void auditFailure(
        HttpServletRequest request,
        String path,
        String clusterId,
        String nodeName,
        String reason
    ) {
        try {
            audit.record(
                "agent",
                nodeName,
                "agent.auth_failed",
                "cluster",
                clusterId,
                "failed",
                Map.of(
                    "path", path,
                    "reason", reason == null ? "authentication_failed" : reason
                ),
                request
            );
        } catch (RuntimeException ignored) {
            // Authentication failure responses must not depend on audit storage availability.
        }
    }
}
