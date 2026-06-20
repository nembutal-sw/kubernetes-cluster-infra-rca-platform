package io.clusterinfra.rca.webconsole.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SameOriginMutationFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        boolean cookieAuthenticated = PlatformAuthenticationFilter.cookieToken(request.getCookies()) != null;
        boolean bearerAuthenticated =
            PlatformAuthenticationFilter.bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)) != null;
        if (cookieAuthenticated && !bearerAuthenticated && !SAFE_METHODS.contains(request.getMethod())) {
            String source = request.getHeader(HttpHeaders.ORIGIN);
            if (source == null || source.isBlank()) {
                source = request.getHeader(HttpHeaders.REFERER);
            }
            if (!sameOrigin(request, source)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "same-origin request required");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean sameOrigin(HttpServletRequest request, String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(source);
            int sourcePort = uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
            int requestPort = request.getServerPort();
            return request.getScheme().equalsIgnoreCase(uri.getScheme())
                && request.getServerName().equalsIgnoreCase(uri.getHost())
                && requestPort == sourcePort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
