package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialRetireRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialRotationRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialState;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReviewerCredentialLifecycleServiceTests {
    private static final String CURRENT =
        "/var/run/secrets/cluster-infra-rca-reviewers/current/token";
    private static final String NEXT =
        "/var/run/secrets/cluster-infra-rca-reviewers/next/token";

    @Mock
    private AgentEnrollmentRepository enrollments;

    @Mock
    private ClusterRepository clusters;

    @Mock
    private ReviewerCredentialInspector inspector;

    private ReviewerCredentialLifecycleService service;
    private AtomicReference<AgentEnrollmentConfiguration> stored;

    @BeforeEach
    void setUp() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getAgent().setReviewerCredentialExpiringSeconds(300);
        properties.getAgent().setReviewerCredentialMaximumGraceSeconds(3600);
        service = new ReviewerCredentialLifecycleService(
            enrollments,
            clusters,
            inspector,
            properties
        );
        stored = new AtomicReference<>(configuration(
            CURRENT,
            4,
            null,
            null,
            null
        ));
        org.mockito.Mockito.lenient().when(enrollments.findConfiguration("cluster-1"))
            .thenAnswer(invocation -> Optional.of(stored.get()));
        org.mockito.Mockito.lenient().when(clusters.find("cluster-1")).thenReturn(Optional.of(
            new io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster(
                "cluster-1",
                "production",
                "prod",
                null,
                io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus.active,
                null,
                Instant.now(),
                Instant.now()
            )
        ));
        org.mockito.Mockito.lenient().when(enrollments.save(any())).thenAnswer(invocation -> {
            AgentEnrollmentConfiguration value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
    }

    @Test
    void rotationValidatesNextCredentialAndStagesPreviousWithinGrace() {
        Instant graceUntil = Instant.now().plusSeconds(900);
        when(inspector.inspect(NEXT)).thenReturn(new ReviewerCredentialInspector.Inspection(
            true,
            Instant.now().plusSeconds(7200),
            null
        ));
        when(inspector.inspect(CURRENT)).thenReturn(new ReviewerCredentialInspector.Inspection(
            true,
            Instant.now().plusSeconds(3600),
            null
        ));

        var profile = service.rotate(
            "cluster-1",
            new ReviewerCredentialRotationRequest(NEXT, 4L, graceUntil)
        );

        assertThat(profile.reviewerTokenPath()).isEqualTo(NEXT);
        assertThat(profile.reviewerPreviousTokenPath()).isEqualTo(CURRENT);
        assertThat(profile.reviewerCredentialVersion()).isEqualTo(5);
        assertThat(profile.profileVersion()).isEqualTo(7);
        assertThat(profile.reviewerCredentialStatus().state())
            .isEqualTo(ReviewerCredentialState.rotating);
        assertThat(service.activeTokenPaths(stored.get())).containsExactly(NEXT, CURRENT);
    }

    @Test
    void staleVersionAndExcessiveGraceAreRejectedWithoutSaving() {
        assertThatThrownBy(() -> service.rotate(
            "cluster-1",
            new ReviewerCredentialRotationRequest(
                NEXT,
                3L,
                Instant.now().plusSeconds(900)
            )
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("version changed");

        assertThatThrownBy(() -> service.rotate(
            "cluster-1",
            new ReviewerCredentialRotationRequest(
                NEXT,
                4L,
                Instant.now().plusSeconds(7200)
            )
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("grace limit");
        verify(enrollments, never()).save(any());
    }

    @Test
    void rotationRejectsUnreadableOrExpiringNextCredential() {
        when(inspector.inspect(NEXT))
            .thenReturn(new ReviewerCredentialInspector.Inspection(false, null, "missing"))
            .thenReturn(new ReviewerCredentialInspector.Inspection(
                true,
                Instant.now().plusSeconds(30),
                null
            ));

        assertThatThrownBy(() -> service.rotate(
            "cluster-1",
            new ReviewerCredentialRotationRequest(
                NEXT,
                4L,
                Instant.now().plusSeconds(900)
            )
        )).hasMessageContaining("missing or invalid");
        assertThatThrownBy(() -> service.rotate(
            "cluster-1",
            new ReviewerCredentialRotationRequest(
                NEXT,
                4L,
                Instant.now().plusSeconds(900)
            )
        )).hasMessageContaining("expired or too close");
        verify(enrollments, never()).save(any());
    }

    @Test
    void retirePreviousClearsFallbackAndIncrementsCredentialVersion() {
        Instant now = Instant.now();
        stored.set(configuration(NEXT, 5, CURRENT, now.plusSeconds(900), now));
        when(inspector.inspect(NEXT)).thenReturn(new ReviewerCredentialInspector.Inspection(
            true,
            now.plusSeconds(7200),
            null
        ));

        var profile = service.retirePrevious(
            "cluster-1",
            new ReviewerCredentialRetireRequest(5L)
        );

        assertThat(profile.reviewerPreviousTokenPath()).isNull();
        assertThat(profile.reviewerPreviousValidUntil()).isNull();
        assertThat(profile.reviewerCredentialVersion()).isEqualTo(6);
        assertThat(service.activeTokenPaths(stored.get())).containsExactly(NEXT);
    }

    @Test
    void missingClusterReturnsNotFoundBeforeTryingToLock() {
        when(clusters.find("cluster-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(
            "cluster-missing",
            new ReviewerCredentialRotationRequest(
                NEXT,
                4L,
                Instant.now().plusSeconds(900)
            )
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("cluster not found");
        verify(enrollments, never()).lockCluster("cluster-missing");
    }

    private AgentEnrollmentConfiguration configuration(
        String currentPath,
        long credentialVersion,
        String previousPath,
        Instant previousValidUntil,
        Instant rotatedAt
    ) {
        Instant now = Instant.now();
        return new AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "test-sha",
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            7,
            currentPath,
            credentialVersion,
            previousPath,
            previousValidUntil,
            rotatedAt,
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            Map.of("cluster-infra-rca.io/cluster-id", "cluster-1"),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            null,
            false,
            now,
            now
        );
    }
}
