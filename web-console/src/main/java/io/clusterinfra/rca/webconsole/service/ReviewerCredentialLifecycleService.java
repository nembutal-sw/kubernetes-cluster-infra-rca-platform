package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialRetireRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialRotationRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewerCredentialLifecycleService {
    private final AgentEnrollmentRepository enrollments;
    private final ClusterRepository clusters;
    private final ReviewerCredentialInspector inspector;
    private final RcaConsoleProperties properties;

    public ReviewerCredentialLifecycleService(
        AgentEnrollmentRepository enrollments,
        ClusterRepository clusters,
        ReviewerCredentialInspector inspector,
        RcaConsoleProperties properties
    ) {
        this.enrollments = enrollments;
        this.clusters = clusters;
        this.inspector = inspector;
        this.properties = properties;
    }

    public AgentEnrollmentProfile decorate(AgentEnrollmentProfile profile) {
        if (!profile.configured() || profile.mode() != AgentEnrollmentMode.kubernetes_token_review) {
            return withStatus(profile, notConfigured());
        }
        AgentEnrollmentConfiguration configuration = enrollments
            .findConfiguration(profile.clusterId())
            .orElse(null);
        return withStatus(profile, status(configuration));
    }

    public ReviewerCredentialStatus status(AgentEnrollmentConfiguration configuration) {
        if (configuration == null
            || configuration.mode() != AgentEnrollmentMode.kubernetes_token_review
            || configuration.reviewerTokenPath() == null) {
            return notConfigured();
        }
        Instant now = Instant.now();
        ReviewerCredentialInspector.Inspection current = inspector.inspect(
            configuration.reviewerTokenPath()
        );
        boolean previousAvailable = previousAvailable(configuration, now);
        ReviewerCredentialState state;
        if (!current.readable()) {
            state = "missing".equals(current.issue())
                ? ReviewerCredentialState.missing
                : ReviewerCredentialState.invalid;
        } else if (current.expired(now)) {
            state = ReviewerCredentialState.expired;
        } else if (previousAvailable) {
            state = ReviewerCredentialState.rotating;
        } else if (current.expiresAt() == null) {
            state = ReviewerCredentialState.unknown_expiry;
        } else if (!current.expiresAt().isAfter(now.plus(expiringWindow()))) {
            state = ReviewerCredentialState.expiring;
        } else {
            state = ReviewerCredentialState.ready;
        }
        return new ReviewerCredentialStatus(
            state,
            configuration.reviewerCredentialVersion(),
            current.readable(),
            current.expiresAt(),
            previousAvailable,
            configuration.reviewerPreviousValidUntil(),
            configuration.reviewerCredentialRotatedAt()
        );
    }

    public List<ReviewerCredentialStatus> statuses() {
        return enrollments.findAllConfigurations().stream()
            .filter(configuration ->
                configuration.mode() == AgentEnrollmentMode.kubernetes_token_review
            )
            .map(this::status)
            .toList();
    }

    @Transactional
    public AgentEnrollmentProfile rotate(
        String clusterId,
        ReviewerCredentialRotationRequest request
    ) {
        requireCluster(clusterId);
        enrollments.lockCluster(clusterId);
        AgentEnrollmentConfiguration current = requireConfiguration(clusterId);
        requireVersion(current, request.expectedVersion());
        String nextPath = ReviewerCredentialPaths.validate(
            request.nextTokenPath(),
            "next_token_path"
        );
        if (nextPath.equals(current.reviewerTokenPath())) {
            throw invalid("next_token_path must differ from the current reviewer token path");
        }
        Instant now = Instant.now();
        Instant previousValidUntil = request.previousValidUntil();
        if (!previousValidUntil.isAfter(now)
            || previousValidUntil.isAfter(now.plus(maximumGrace()))) {
            throw invalid(
                "previous_valid_until must be in the future and within the configured grace limit"
            );
        }
        ReviewerCredentialInspector.Inspection next = inspector.inspect(nextPath);
        if (!next.readable()) {
            throw invalid("next reviewer credential is missing or invalid");
        }
        if (next.expired(now)
            || (next.expiresAt() != null
                && !next.expiresAt().isAfter(now.plus(expiringWindow())))) {
            throw invalid("next reviewer credential is expired or too close to expiry");
        }
        AgentEnrollmentConfiguration saved = enrollments.save(
            withReviewerCredential(
                current,
                nextPath,
                current.reviewerTokenPath(),
                previousValidUntil,
                current.reviewerCredentialVersion() + 1,
                now
            )
        );
        return decorate(saved.view());
    }

    @Transactional
    public AgentEnrollmentProfile retirePrevious(
        String clusterId,
        ReviewerCredentialRetireRequest request
    ) {
        requireCluster(clusterId);
        enrollments.lockCluster(clusterId);
        AgentEnrollmentConfiguration current = requireConfiguration(clusterId);
        requireVersion(current, request.expectedVersion());
        if (current.reviewerPreviousTokenPath() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "no previous reviewer credential is staged"
            );
        }
        AgentEnrollmentConfiguration saved = enrollments.save(
            withReviewerCredential(
                current,
                current.reviewerTokenPath(),
                null,
                null,
                current.reviewerCredentialVersion() + 1,
                current.reviewerCredentialRotatedAt()
            )
        );
        return decorate(saved.view());
    }

    List<String> activeTokenPaths(AgentEnrollmentConfiguration configuration) {
        Instant now = Instant.now();
        if (previousAvailable(configuration, now)) {
            return List.of(
                configuration.reviewerTokenPath(),
                configuration.reviewerPreviousTokenPath()
            );
        }
        return List.of(configuration.reviewerTokenPath());
    }

    private boolean previousAvailable(
        AgentEnrollmentConfiguration configuration,
        Instant now
    ) {
        if (configuration.reviewerPreviousTokenPath() == null
            || configuration.reviewerPreviousValidUntil() == null
            || !configuration.reviewerPreviousValidUntil().isAfter(now)) {
            return false;
        }
        ReviewerCredentialInspector.Inspection previous = inspector.inspect(
            configuration.reviewerPreviousTokenPath()
        );
        return previous.readable() && !previous.expired(now);
    }

    private AgentEnrollmentConfiguration requireConfiguration(String clusterId) {
        AgentEnrollmentConfiguration configuration = enrollments
            .findConfiguration(clusterId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Kubernetes TokenReview enrollment is not configured"
            ));
        if (configuration.mode() != AgentEnrollmentMode.kubernetes_token_review) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Kubernetes TokenReview enrollment is not configured"
            );
        }
        return configuration;
    }

    private void requireCluster(String clusterId) {
        if (clusters.find(clusterId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cluster not found");
        }
    }

    private void requireVersion(
        AgentEnrollmentConfiguration configuration,
        long expectedVersion
    ) {
        if (configuration.reviewerCredentialVersion() != expectedVersion) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "reviewer credential version changed; refresh and retry"
            );
        }
    }

    private AgentEnrollmentConfiguration withReviewerCredential(
        AgentEnrollmentConfiguration value,
        String currentPath,
        String previousPath,
        Instant previousValidUntil,
        long credentialVersion,
        Instant rotatedAt
    ) {
        return new AgentEnrollmentConfiguration(
            value.clusterId(),
            value.mode(),
            value.apiServerUrl(),
            value.caBundlePem(),
            value.caSha256(),
            value.audience(),
            value.namespace(),
            value.serviceAccount(),
            value.profileVersion(),
            currentPath,
            credentialVersion,
            previousPath,
            previousValidUntil,
            rotatedAt,
            value.expectedServiceAccountUid(),
            value.expectedDaemonSetName(),
            value.expectedDaemonSetUid(),
            value.requiredPodLabels(),
            value.allowedImageDigest(),
            value.legacyUnboundTokenGraceUntil(),
            value.bootstrapFallbackAllowed(),
            value.createdAt(),
            Instant.now()
        );
    }

    private AgentEnrollmentProfile withStatus(
        AgentEnrollmentProfile profile,
        ReviewerCredentialStatus status
    ) {
        return new AgentEnrollmentProfile(
            profile.clusterId(),
            profile.mode(),
            profile.configured(),
            profile.apiServerUrl(),
            profile.caSha256(),
            profile.audience(),
            profile.namespace(),
            profile.serviceAccount(),
            profile.profileVersion(),
            profile.reviewerTokenPath(),
            profile.reviewerCredentialVersion(),
            profile.reviewerPreviousTokenPath(),
            profile.reviewerPreviousValidUntil(),
            profile.reviewerCredentialRotatedAt(),
            status,
            profile.expectedServiceAccountUid(),
            profile.expectedDaemonSetName(),
            profile.expectedDaemonSetUid(),
            profile.requiredPodLabels(),
            profile.allowedImageDigest(),
            profile.workloadIdentityReady(),
            profile.bootstrapFallbackAllowed(),
            profile.bootstrapTokenRotationRequired(),
            profile.legacyUnboundTokenGraceUntil(),
            profile.legacyUnboundAgents(),
            profile.updatedAt()
        );
    }

    private ReviewerCredentialStatus notConfigured() {
        return new ReviewerCredentialStatus(
            ReviewerCredentialState.not_configured,
            0,
            false,
            null,
            false,
            null,
            null
        );
    }

    private Duration expiringWindow() {
        return Duration.ofSeconds(Math.max(
            60,
            properties.getAgent().getReviewerCredentialExpiringSeconds()
        ));
    }

    private Duration maximumGrace() {
        return Duration.ofSeconds(Math.max(
            60,
            properties.getAgent().getReviewerCredentialMaximumGraceSeconds()
        ));
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
