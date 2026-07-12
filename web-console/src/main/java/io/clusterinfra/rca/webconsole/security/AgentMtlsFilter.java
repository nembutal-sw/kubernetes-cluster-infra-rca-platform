package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AgentMtlsFilter extends OncePerRequestFilter {
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;

    public AgentMtlsFilter(RcaConsoleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getSecurity().isAgentMtlsRequired()
            || !SecurityFilterSupport.path(request).startsWith("/api/agents/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] certificates)
            || certificates.length == 0
            || certificates[0] == null) {
            SecurityFilterSupport.writeError(
                objectMapper,
                request,
                response,
                HttpStatus.UNAUTHORIZED.value(),
                "agent client certificate is required"
            );
            return;
        }
        request.setAttribute(
            "rca.agent_certificate_subject",
            certificates[0].getSubjectX500Principal().getName()
        );
        filterChain.doFilter(request, response);
    }
}
