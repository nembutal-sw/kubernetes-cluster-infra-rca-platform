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

    public AuthController(RcaRepository repository, AccessService access, RcaConsoleProperties properties) {
        this.repository = repository;
        this.access = access;
        this.properties = properties;
    }

    @PostMapping("/login")
    public AuthSessionResponse login(@Valid @RequestBody UserLoginRequest request) {
        UserAccount user = repository.authenticateUser(request.normalizedUsername(), request.password())
            .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid username or password"));
        if (user.status() != UserStatus.active || user.role() == null) {
            throw new ResponseStatusException(FORBIDDEN, "user is not active");
        }
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.max(1, properties.getSessionTtlHours())));
        String token = repository.createUserSession(user.userId(), expiresAt);
        return new AuthSessionResponse(token, "bearer", expiresAt, user);
    }

    @GetMapping("/me")
    public UserAccount me(Authentication authentication) {
        return access.currentUser(authentication);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = PlatformAuthenticationFilter.bearerToken(authorization);
        return Map.of("revoked", token != null && repository.revokeUserSession(token));
    }

    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(
        @Valid @RequestBody UserPasswordChangeRequest request,
        Authentication authentication
    ) {
        UserAccount user = access.currentUser(authentication);
        if (!repository.changeUserPassword(user.userId(), request.currentPassword(), request.newPassword())) {
            throw new ResponseStatusException(UNAUTHORIZED, "current password is invalid");
        }
        return Map.of("changed", true);
    }
}
