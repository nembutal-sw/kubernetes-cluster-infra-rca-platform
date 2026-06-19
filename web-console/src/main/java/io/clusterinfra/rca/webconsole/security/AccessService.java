package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AccessService {
    private final RcaRepository repository;
    private final RcaConsoleProperties properties;

    public AccessService(RcaRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public UserAccount currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount user)) {
            throw new ResponseStatusException(UNAUTHORIZED, "login required");
        }
        return user;
    }

    public void verifyAgentIdentity(String clusterId, String nodeName, String agentToken, String nodeToken) {
        verifyBootstrapToken(clusterId, agentToken);
        if (!repository.verifyAgentNodeToken(clusterId, nodeName, nodeToken)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid node token");
        }
    }

    public void verifyBootstrapToken(String clusterId, String agentToken) {
        Cluster cluster = repository.getCluster(clusterId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cluster not found"));
        if (!constantTimeEquals(cluster.bootstrapToken(), agentToken)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid agent token");
        }
    }

    public void verifyManifestAccess(
        String clusterId,
        String authorization,
        String agentToken,
        Authentication authentication
    ) {
        if (authentication != null && authentication.isAuthenticated()) {
            return;
        }
        if (agentToken != null && !agentToken.isBlank()) {
            verifyBootstrapToken(clusterId, agentToken);
            return;
        }
        String bearer = PlatformAuthenticationFilter.bearerToken(authorization);
        if (bearer != null && repository.getUserBySessionToken(bearer).isPresent()) {
            return;
        }
        throw new ResponseStatusException(UNAUTHORIZED, "user or agent authentication required");
    }

    public void verifyWebhookToken(String authorization, String webhookHeader) {
        String expected = properties.getWebhookToken();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String bearer = PlatformAuthenticationFilter.bearerToken(authorization);
        String supplied = webhookHeader == null || webhookHeader.isBlank() ? bearer : webhookHeader.trim();
        if (!constantTimeEquals(expected, supplied)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid webhook token");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
