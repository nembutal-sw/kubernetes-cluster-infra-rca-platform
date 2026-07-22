package io.clusterinfra.rca.webconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentIdentity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.service.AgentEnrollmentService;
import io.clusterinfra.rca.webconsole.service.KubernetesTokenReviewService;
import io.clusterinfra.rca.webconsole.service.ManifestTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AccessServiceEnrollmentTests {
    @Mock private ClusterRepository clusters;
    @Mock private AgentRepository agents;
    @Mock private UserSessionRepository sessions;
    @Mock private ManifestTokenService manifestTokens;
    @Mock private AgentEnrollmentService enrollments;
    @Mock private KubernetesTokenReviewService tokenReviews;

    private AccessService access;

    @BeforeEach
    void setUp() {
        access = new AccessService(
            clusters,
            agents,
            sessions,
            new RcaConsoleProperties(),
            manifestTokens,
            enrollments,
            tokenReviews
        );
    }

    @Test
    void delegatesConfiguredKubernetesEnrollmentToTokenReview() {
        AgentEnrollmentConfiguration configuration = configuration(false);
        AgentEnrollmentIdentity identity = identity();
        when(enrollments.configuration("cluster-1")).thenReturn(configuration);
        when(tokenReviews.verify(configuration, "projected-token", "worker-1")).thenReturn(identity);

        assertThat(access.verifyAgentEnrollment(
            "cluster-1",
            "worker-1",
            "kubernetes-token-review",
            "projected-token"
        )).isSameAs(identity);
    }

    @Test
    void strictKubernetesEnrollmentRejectsBootstrapBeforeTokenVerification() {
        when(enrollments.configuration("cluster-1")).thenReturn(configuration(false));

        assertThatThrownBy(() -> access.verifyAgentEnrollment(
            "cluster-1",
            "worker-1",
            "bootstrap-token",
            "bootstrap-token"
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("bootstrap agent enrollment is disabled");
        verify(clusters, never()).verifyBootstrapToken(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    @Test
    void fallbackProfileStillAppliesBootstrapTtlValidation() {
        when(enrollments.configuration("cluster-1")).thenReturn(configuration(true));
        when(clusters.find("cluster-1")).thenReturn(Optional.of(cluster()));
        when(clusters.verifyBootstrapToken(
            "cluster-1",
            "bootstrap-token",
            Duration.ofMinutes(30)
        )).thenReturn(true);

        AgentEnrollmentIdentity identity = access.verifyAgentEnrollment(
            "cluster-1",
            "worker-1",
            "bootstrap-token",
            "bootstrap-token"
        );

        assertThat(identity.method()).isEqualTo("bootstrap_token");
    }

    private AgentEnrollmentConfiguration configuration(boolean fallback) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "ca-sha",
            "https://kubernetes.default.svc",
            "rca-system",
            "cluster-infra-rca-agent",
            fallback,
            now,
            now
        );
    }

    private AgentEnrollmentIdentity identity() {
        return new AgentEnrollmentIdentity(
            "kubernetes_token_review",
            "system:serviceaccount:rca-system:cluster-infra-rca-agent",
            "service-account-uid",
            "rca-system",
            "cluster-infra-rca-agent",
            "rca-agent-1",
            "pod-uid-1"
        );
    }

    private Cluster cluster() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new Cluster(
            "cluster-1",
            "production",
            "prod",
            null,
            ClusterStatus.active,
            null,
            now,
            now
        );
    }
}
