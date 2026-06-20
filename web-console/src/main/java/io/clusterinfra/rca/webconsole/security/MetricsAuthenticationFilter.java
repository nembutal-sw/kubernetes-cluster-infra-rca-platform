package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class MetricsAuthenticationFilter extends OncePerRequestFilter {
    public static final String METRICS_TOKEN_HEADER = "X-Metrics-Token";

    private final RcaConsoleProperties properties;

    public MetricsAuthenticationFilter(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"/actuator/prometheus".equals(path) && !path.startsWith("/actuator/metrics");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String expected = properties.getObservability().getMetricsToken();
            String supplied = request.getHeader(METRICS_TOKEN_HEADER);
            if (supplied == null || supplied.isBlank()) {
                supplied = PlatformAuthenticationFilter.bearerToken(
                    request.getHeader(HttpHeaders.AUTHORIZATION)
                );
            }
            if (!expected.isBlank() && constantTimeEquals(expected, supplied)) {
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        "metrics-scraper",
                        supplied,
                        List.of(new SimpleGrantedAuthority("ROLE_METRICS"))
                    );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
