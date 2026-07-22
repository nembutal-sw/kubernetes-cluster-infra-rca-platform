package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentIdentity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.service.AgentEnrollmentService;
import io.clusterinfra.rca.webconsole.service.KubernetesTokenReviewService;
import io.clusterinfra.rca.webconsole.service.ManifestTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AccessService {
    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final UserSessionRepository sessions;
    private final RcaConsoleProperties properties;
    private final ManifestTokenService manifestTokens;
    private final AgentEnrollmentService enrollments;
    private final KubernetesTokenReviewService tokenReviews;

    public AccessService(
        ClusterRepository clusters,
        AgentRepository agents,
        UserSessionRepository sessions,
        RcaConsoleProperties properties,
        ManifestTokenService manifestTokens,
        AgentEnrollmentService enrollments,
        KubernetesTokenReviewService tokenReviews
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.sessions = sessions;
        this.properties = properties;
        this.manifestTokens = manifestTokens;
        this.enrollments = enrollments;
        this.tokenReviews = tokenReviews;
    }

    public UserAccount currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount user)) {
            throw new ResponseStatusException(UNAUTHORIZED, "login required");
        }
        return user;
    }

    public void verifyNodeIdentity(String clusterId, String nodeName, String nodeToken) {
        if (!agents.verifyNodeToken(clusterId, nodeName, nodeToken)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid node token");
        }
    }

    public void verifyBootstrapToken(String clusterId, String agentToken) {
        clusters.find(clusterId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cluster not found"));
        Duration tokenTtl = Duration.ofSeconds(
            properties.getSecurity().getAgentBootstrapTokenTtlSeconds()
        );
        if (!clusters.verifyBootstrapToken(clusterId, agentToken, tokenTtl)) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid agent token");
        }
    }

    public AgentEnrollmentIdentity verifyAgentEnrollment(
        String clusterId,
        String nodeName,
        String enrollmentMethod,
        String credential
    ) {
        var configuration = enrollments.configuration(clusterId);
        if ("kubernetes-token-review".equals(enrollmentMethod)) {
            if (configuration == null
                || configuration.mode() != AgentEnrollmentMode.kubernetes_token_review) {
                throw new ResponseStatusException(UNAUTHORIZED, "Kubernetes agent enrollment is not configured");
            }
            return tokenReviews.verify(configuration, credential, nodeName);
        }
        if (configuration != null
            && configuration.mode() == AgentEnrollmentMode.kubernetes_token_review
            && !configuration.bootstrapFallbackAllowed()) {
            throw new ResponseStatusException(UNAUTHORIZED, "bootstrap agent enrollment is disabled");
        }
        verifyBootstrapToken(clusterId, credential);
        return new AgentEnrollmentIdentity(
            "bootstrap_token",
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public void verifyManifestAccess(
        String clusterId,
        String authorization,
        String manifestToken,
        Authentication authentication
    ) {
        if (authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof UserAccount) {
            return;
        }
        if (manifestToken != null && !manifestToken.isBlank()
            && manifestTokens.consume(clusterId, manifestToken)) {
            return;
        }
        String bearer = PlatformAuthenticationFilter.bearerToken(authorization);
        if (bearer != null && sessions.findUserByToken(bearer).isPresent()) {
            return;
        }
        throw new ResponseStatusException(UNAUTHORIZED, "user or agent authentication required");
    }

    public void verifyWebhookToken(String authorization, String webhookHeader) {
        String expected = properties.getWebhookToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "webhook token is not configured");
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
