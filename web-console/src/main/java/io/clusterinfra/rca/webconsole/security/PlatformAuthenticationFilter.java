package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final RcaRepository repository;

    public PlatformAuthenticationFilter(RcaRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            repository.getUserBySessionToken(token).ifPresent(user -> authenticate(user, token));
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
}
