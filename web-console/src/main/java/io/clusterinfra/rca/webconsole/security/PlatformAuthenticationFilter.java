package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PlatformAuthenticationFilter extends OncePerRequestFilter {
    public static final String SESSION_COOKIE = "RCA_SESSION";
    private final UserSessionRepository sessions;

    public PlatformAuthenticationFilter(UserSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            token = cookieToken(request.getCookies());
        }
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String sessionToken = token;
            sessions.findUserByToken(sessionToken).ifPresent(user -> authenticate(user, sessionToken));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(UserAccount user, String token) {
        List<SimpleGrantedAuthority> authorities = user.role() == null
            ? List.of()
            : List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name().toUpperCase()));
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(user, token, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public static String cookieToken(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
