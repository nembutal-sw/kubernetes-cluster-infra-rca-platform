package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuthSessionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserLoginIdChangeRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserLoginRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserPasswordChangeRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.JdbcRcaStore.DuplicateLoginIdException;
import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.security.PlatformAuthenticationFilter;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final AccessService access;
    private final RcaConsoleProperties properties;
    private final AuditService audit;

    public AuthController(
        UserRepository users,
        UserSessionRepository sessions,
        AccessService access,
        RcaConsoleProperties properties,
        AuditService audit
    ) {
        this.users = users;
        this.sessions = sessions;
        this.access = access;
        this.properties = properties;
        this.audit = audit;
    }

    @PostMapping("/login")
    public AuthSessionResponse login(
        @Valid @RequestBody UserLoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        UserAccount user = users.authenticate(request.normalizedUsername(), request.password())
            .orElseGet(() -> {
                audit.record(
                    "user",
                    request.normalizedUsername(),
                    "auth.login",
                    "session",
                    null,
                    "failed",
                    Map.of("reason", "invalid_credentials"),
                    servletRequest
                );
                throw new ResponseStatusException(UNAUTHORIZED, "invalid username or password");
            });
        if (user.status() != UserStatus.active || user.role() == null) {
            audit.user(
                user,
                "auth.login",
                "session",
                null,
                "failed",
                Map.of("reason", "inactive_user"),
                servletRequest
            );
            throw new ResponseStatusException(FORBIDDEN, "user is not active");
        }
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.max(1, properties.getSessionTtlHours())));
        String token = sessions.create(user.userId(), expiresAt);
        servletResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            sessionCookie(token, Duration.between(Instant.now(), expiresAt), servletRequest.isSecure()).toString()
        );
        audit.user(
            user,
            "auth.login",
            "session",
            null,
            "success",
            Map.of("expires_at", expiresAt.toString()),
            servletRequest
        );
        return new AuthSessionResponse(token, "bearer", expiresAt, user);
    }

    @GetMapping("/me")
    public UserAccount me(Authentication authentication) {
        return access.currentUser(authentication);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        Authentication authentication,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        UserAccount user = access.currentUser(authentication);
        String token = PlatformAuthenticationFilter.bearerToken(authorization);
        if (token == null) {
            token = PlatformAuthenticationFilter.cookieToken(servletRequest.getCookies());
        }
        boolean revoked = token != null && sessions.revoke(token);
        servletResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            sessionCookie("", Duration.ZERO, servletRequest.isSecure()).toString()
        );
        audit.user(
            user,
            "auth.logout",
            "session",
            null,
            revoked ? "success" : "not_found",
            Map.of(),
            servletRequest
        );
        return Map.of("revoked", revoked);
    }

    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(
        @Valid @RequestBody UserPasswordChangeRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        if (!users.changePassword(user.userId(), request.currentPassword(), request.newPassword())) {
            audit.user(user, "auth.password_change", "user", user.userId(), "failed", Map.of(), servletRequest);
            throw new ResponseStatusException(UNAUTHORIZED, "current password is invalid");
        }
        audit.user(user, "auth.password_change", "user", user.userId(), "success", Map.of(), servletRequest);
        return Map.of("changed", true);
    }

    @PostMapping("/change-login-id")
    public UserAccount changeLoginId(
        @Valid @RequestBody UserLoginIdChangeRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        try {
            UserAccount changed = users.changeLoginId(
                user.userId(),
                request.currentPassword(),
                request.normalizedUsername()
            ).orElseThrow(() -> {
                audit.user(
                    user,
                    "auth.login_id_change",
                    "user",
                    user.userId(),
                    "failed",
                    Map.of("reason", "invalid_password"),
                    servletRequest
                );
                return new ResponseStatusException(UNAUTHORIZED, "current password is invalid");
            });
            audit.user(
                changed,
                "auth.login_id_change",
                "user",
                changed.userId(),
                "success",
                Map.of("previous_login_id", user.email(), "new_login_id", changed.email()),
                servletRequest
            );
            return changed;
        } catch (DuplicateLoginIdException exception) {
            audit.user(
                user,
                "auth.login_id_change",
                "user",
                user.userId(),
                "failed",
                Map.of("reason", "duplicate_login_id"),
                servletRequest
            );
            throw new ResponseStatusException(CONFLICT, "login id already exists");
        }
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(PlatformAuthenticationFilter.SESSION_COOKIE, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build();
    }
}
