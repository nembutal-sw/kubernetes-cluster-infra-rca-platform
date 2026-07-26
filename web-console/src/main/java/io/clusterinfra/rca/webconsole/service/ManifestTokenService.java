package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.ManifestTokenRepository;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManifestTokenService {
    private final ManifestTokenRepository tokens;
    private final RcaConsoleProperties properties;
    private final TokenGenerator tokenGenerator;
    private final Sha256Digest digests;

    public ManifestTokenService(
        ManifestTokenRepository tokens,
        RcaConsoleProperties properties,
        TokenGenerator tokenGenerator,
        Sha256Digest digests
    ) {
        this.tokens = tokens;
        this.properties = properties;
        this.tokenGenerator = tokenGenerator;
        this.digests = digests;
    }

    public IssuedManifestToken issue(String clusterId, String createdBy) {
        String token = tokenGenerator.generate();
        Instant createdAt = Instant.now();
        tokens.deleteExpired(createdAt);
        Instant expiresAt = createdAt.plusSeconds(
            Math.max(30, Math.min(900, properties.getSecurity().getManifestTokenTtlSeconds()))
        );
        tokens.create(
            "manifest-token-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            clusterId,
            digests.digest(token),
            createdBy == null || createdBy.isBlank() ? "unknown" : createdBy,
            createdAt,
            expiresAt
        );
        return new IssuedManifestToken(token, expiresAt);
    }

    public boolean consume(String clusterId, String token) {
        return token != null
            && !token.isBlank()
            && tokens.consume(clusterId, digests.digest(token), Instant.now());
    }

    public record IssuedManifestToken(String token, Instant expiresAt) {
    }
}
