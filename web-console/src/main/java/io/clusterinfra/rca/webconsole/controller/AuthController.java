package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuthSessionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserLoginRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserPasswordChangeRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.security.PlatformAuthenticationFilter;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final RcaRepository repository;
    private final AccessService access;
    private final RcaConsoleProperties properties;
    private final AuditService audit;

    public AuthController(
        RcaRepository repository,
        AccessService access,
        RcaConsoleProperties properties,
        AuditService audit
    ) {
        this.repository = repository;
        this.access = access;
        this.properties = properties;
        this.audit = audit;
    }

    @PostMapping("/login")
    public AuthSessionResponse login(@Valid @RequestBody UserLoginRequest request) {
        UserAccount user = repository.authenticateUser(request.normalizedUsername(), request.password())
            .orElseGet(() -> {
                audit.record(
                    "user",
                    request.normalizedUsername(),
                    "auth.login",
                    "session",
                    null,
                    "failed",
                    Map.of("reason", "invalid_credentials")
                );
                throw new ResponseStatusException(UNAUTHORIZED, "invalid username or password");
            });
        if (user.status() != UserStatus.active || user.role() == null) {
            audit.user(user, "auth.login", "session", null, "failed", Map.of("reason", "inactive_user"));
            throw new ResponseStatusException(FORBIDDEN, "user is not active");
        }
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.max(1, properties.getSessionTtlHours())));
        String token = repository.createUserSession(user.userId(), expiresAt);
        audit.user(user, "auth.login", "session", null, "success", Map.of("expires_at", expiresAt.toString()));
        return new AuthSessionResponse(token, "bearer", expiresAt, user);
    }

    @GetMapping("/me")
    public UserAccount me(Authentication authentication) {
        return access.currentUser(authentication);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        Authentication authentication
    ) {
        UserAccount user = access.currentUser(authentication);
        String token = PlatformAuthenticationFilter.bearerToken(authorization);
        boolean revoked = token != null && repository.revokeUserSession(token);
        audit.user(user, "auth.logout", "session", null, revoked ? "success" : "not_found", Map.of());
        return Map.of("revoked", revoked);
    }

    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(
        @Valid @RequestBody UserPasswordChangeRequest request,
        Authentication authentication
    ) {
        UserAccount user = access.currentUser(authentication);
        if (!repository.changeUserPassword(user.userId(), request.currentPassword(), request.newPassword())) {
            audit.user(user, "auth.password_change", "user", user.userId(), "failed", Map.of());
            throw new ResponseStatusException(UNAUTHORIZED, "current password is invalid");
        }
        audit.user(user, "auth.password_change", "user", user.userId(), "success", Map.of());
        return Map.of("changed", true);
    }
}
