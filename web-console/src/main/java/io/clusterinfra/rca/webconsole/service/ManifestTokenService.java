package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.ManifestTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManifestTokenService {
    private final ManifestTokenRepository tokens;
    private final RcaConsoleProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public ManifestTokenService(
        ManifestTokenRepository tokens,
        RcaConsoleProperties properties
    ) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public IssuedManifestToken issue(String clusterId, String createdBy) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant createdAt = Instant.now();
        tokens.deleteExpired(createdAt);
        Instant expiresAt = createdAt.plusSeconds(
            Math.max(30, Math.min(900, properties.getSecurity().getManifestTokenTtlSeconds()))
        );
        tokens.create(
            "manifest-token-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            clusterId,
            hash(token),
            createdBy == null || createdBy.isBlank() ? "unknown" : createdBy,
            createdAt,
            expiresAt
        );
        return new IssuedManifestToken(token, expiresAt);
    }

    public boolean consume(String clusterId, String token) {
        return token != null
            && !token.isBlank()
            && tokens.consume(clusterId, hash(token), Instant.now());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedManifestToken(String token, Instant expiresAt) {
    }
}
