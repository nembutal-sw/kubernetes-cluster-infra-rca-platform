package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfileUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentEnrollmentServiceTests {
    private static final String TEST_CA = """
        -----BEGIN CERTIFICATE-----
        MIIC4DCCAcigAwIBAgIIS6OkPM80gCUwDQYJKoZIhvcNAQEMBQAwFjEUMBIGA1UE
        AxMLUkNBIFRlc3QgQ0EwHhcNMjYwNzIyMDc1MzE1WhcNMzYwNzE5MDc1MzE1WjAW
        MRQwEgYDVQQDEwtSQ0EgVGVzdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
        AQoCggEBAK0MWDDIw/Stx6OefyU1GKGQgca/lipd2R3AS/dNgmQqmLx/TlzpPb0T
        F2WcsvMcwH1s254CKiHGmjSyXVW5WWemi/NkroGNQnN9JmlFJxNnm3tLAnICVlSk
        09DBHETRLKDdhnBlR6K76aMCDrETWbsvyNX3DjK07b9iw7Cft3tAT8zEw0zil4Sr
        myTS8jlNQ1JLnsBxUEXgLeLe2z/iokrV7lIXhoK8pNeR5MuBjFJcjXO/2POdOqUV
        w0j4rynu1rYcGFjHFMBa78cVrLWeAS3ShAGdhLpZyZAijHSixcnLdg6GbOQp0KeO
        2VxDF6tT13xXgf8UqZFGhON3z5xSlkkCAwEAAaMyMDAwHQYDVR0OBBYEFMwYkYLR
        ziavHhF5c0DUkVASseYXMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEMBQAD
        ggEBADd6w3i3fdZOYrkAvFcn3uBhaEaLyHx8GYq0GqlbIKJSKi5wVQVebQQV3Mv7
        yVXlIYtJWM3jsB8g3x1f9wNrC8OLvcOn36p6mBHfzciJ2Ar9Kf1eJJsveCMR+d19
        6r045630FNy/tVe08Fdg+/IRYvvQz2m2yvTN/KTDuMONrh2LCda+LkcjaS3FhrGU
        GM4lhkFw8wZLv9jfwkE+83s1HxatY8E/cyUAopI+6W5EywRqKtiNwhd2VPHdVa7D
        c8a19EUio1/Xy4iSUbGgl0g7dXCtAL+KkoywpkutgMX0uZGJwc9VCEoI2l9ZuleU
        60YUiKiT06pLpNKiTxvgCnp9Y+A=
        -----END CERTIFICATE-----
        """;

    @Mock
    private AgentEnrollmentRepository enrollments;

    @Mock
    private AgentRepository agents;

    @Mock
    private ClusterRepository clusters;

    private AgentEnrollmentService service;

    @BeforeEach
    void setUp() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        service = new AgentEnrollmentService(
            enrollments,
            agents,
            clusters,
            properties,
            new AgentSecurityPolicy(properties)
        );
        when(clusters.find("cluster-1")).thenReturn(Optional.of(new Cluster(
            "cluster-1",
            "production",
            "prod",
            null,
            ClusterStatus.active,
            null,
            Instant.now(),
            Instant.now()
        )));
    }

    @Test
    void strictTokenReviewProfileRevokesBootstrapAndExposesOnlyFingerprint() {
        saveReturnsInput();
        var profile = service.update("cluster-1", request(false, TEST_CA));

        assertThat(profile.mode()).isEqualTo(AgentEnrollmentMode.kubernetes_token_review);
        assertThat(profile.caSha256()).hasSize(64);
        assertThat(profile.bootstrapFallbackAllowed()).isFalse();
        assertThat(profile.workloadIdentityReady()).isFalse();
        verify(clusters).revokeBootstrapToken("cluster-1");
        verify(agents).revokeNodeTokensForEnrollmentChange("cluster-1");

        ArgumentCaptor<AgentEnrollmentConfiguration> saved = ArgumentCaptor.forClass(
            AgentEnrollmentConfiguration.class
        );
        verify(enrollments).save(saved.capture());
        assertThat(saved.getValue().caBundlePem()).contains("BEGIN CERTIFICATE");
        assertThat(profile.toString()).doesNotContain("BEGIN CERTIFICATE");
    }

    @Test
    void fallbackProfileDoesNotRevokeBootstrapToken() {
        saveReturnsInput();
        service.update("cluster-1", request(true, TEST_CA));

        verify(clusters, never()).revokeBootstrapToken("cluster-1");
    }

    @Test
    void existingCaCanBeRetainedWithoutReturningItToTheClient() {
        saveReturnsInput();
        Instant now = Instant.now();
        when(enrollments.findConfiguration("cluster-1")).thenReturn(Optional.of(
            new AgentEnrollmentConfiguration(
                "cluster-1",
                AgentEnrollmentMode.kubernetes_token_review,
                "https://kubernetes.example:6443",
                TEST_CA,
                "old-sha",
                "cluster-infra-rca-agent-enrollment",
                "rca-system",
                "cluster-infra-rca-agent",
                true,
                now,
                now
            )
        ));

        var profile = service.update("cluster-1", request(true, ""));

        assertThat(profile.caSha256()).hasSize(64);
        verify(enrollments).save(any());
    }

    @Test
    void rejectsApiServerUrlWithPathBeforeSaving() {
        AgentEnrollmentProfileUpdateRequest invalid = new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443/proxy",
            TEST_CA,
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            false
        );

        assertThatThrownBy(() -> service.update("cluster-1", invalid))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("HTTPS origin");
        verify(enrollments, never()).save(any());
    }

    @Test
    void returningToBootstrapRequiresExplicitRotationWhenTheTokenIsRevoked() {
        when(clusters.bootstrapTokenRequiresRotation("cluster-1", java.time.Duration.ofMinutes(30)))
            .thenReturn(true);
        AgentEnrollmentProfileUpdateRequest bootstrap = new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.bootstrap_token,
            null,
            null,
            null,
            null,
            null,
            null
        );

        var profile = service.update("cluster-1", bootstrap);

        verify(enrollments).delete("cluster-1");
        assertThat(profile.mode()).isEqualTo(AgentEnrollmentMode.bootstrap_token);
        assertThat(profile.bootstrapTokenRotationRequired()).isTrue();
    }

    @Test
    void rejectsKubernetesApiAudienceBeforeSaving() {
        AgentEnrollmentProfileUpdateRequest invalid = new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            TEST_CA,
            "https://kubernetes.default.svc",
            "rca-system",
            "cluster-infra-rca-agent",
            false
        );

        assertThatThrownBy(() -> service.update("cluster-1", invalid))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("dedicated to Agent enrollment");
        verify(enrollments, never()).save(any());
    }

    @Test
    void completeWorkloadIdentityContractBecomesReady() {
        saveReturnsInput();
        AgentEnrollmentProfileUpdateRequest complete = completeRequest(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        var profile = service.update("cluster-1", complete);

        assertThat(profile.workloadIdentityReady()).isTrue();
        assertThat(profile.profileVersion()).isEqualTo(1);
        assertThat(profile.requiredPodLabels())
            .containsEntry("cluster-infra-rca.io/cluster-id", "cluster-1");
    }

    @Test
    void onlySecurityContractChangesIncrementTheVersionAndRevokeNodeTokens() {
        AtomicReference<AgentEnrollmentConfiguration> stored = new AtomicReference<>();
        when(enrollments.findConfiguration("cluster-1"))
            .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(enrollments.save(any())).thenAnswer(invocation -> {
            AgentEnrollmentConfiguration configuration = invocation.getArgument(0);
            stored.set(configuration);
            return configuration;
        });

        var initial = service.update(
            "cluster-1",
            completeRequest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );
        var unchanged = service.update(
            "cluster-1",
            completeRequest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );
        var changed = service.update(
            "cluster-1",
            completeRequest("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        );

        assertThat(initial.profileVersion()).isEqualTo(1);
        assertThat(unchanged.profileVersion()).isEqualTo(1);
        assertThat(changed.profileVersion()).isEqualTo(2);
        verify(agents, times(2)).revokeNodeTokensForEnrollmentChange("cluster-1");
    }

    @Test
    void legacyGraceIsClusterScopedAndLimitedToThirtyDays() {
        saveReturnsInput();
        Instant graceUntil = Instant.now().plus(java.time.Duration.ofDays(7));
        AgentEnrollmentProfileUpdateRequest allowed = new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            TEST_CA,
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            java.util.Map.of(
                "app.kubernetes.io/name", "cluster-infra-rca-agent",
                "cluster-infra-rca.io/cluster-id", "cluster-1"
            ),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            graceUntil,
            true
        );

        assertThat(service.update("cluster-1", allowed).legacyUnboundTokenGraceUntil())
            .isEqualTo(graceUntil);

        AgentEnrollmentProfileUpdateRequest excessive = new AgentEnrollmentProfileUpdateRequest(
            allowed.mode(),
            allowed.apiServerUrl(),
            allowed.caBundlePem(),
            allowed.audience(),
            allowed.namespace(),
            allowed.serviceAccount(),
            allowed.reviewerTokenPath(),
            allowed.expectedServiceAccountUid(),
            allowed.expectedDaemonSetName(),
            allowed.expectedDaemonSetUid(),
            allowed.requiredPodLabels(),
            allowed.allowedImageDigest(),
            Instant.now().plus(java.time.Duration.ofDays(31)),
            allowed.bootstrapFallbackAllowed()
        );

        assertThatThrownBy(() -> service.update("cluster-1", excessive))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("no more than 30 days");
    }

    private AgentEnrollmentProfileUpdateRequest request(boolean fallback, String ca) {
        return new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            ca,
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            fallback
        );
    }

    private AgentEnrollmentProfileUpdateRequest completeRequest(String imageDigest) {
        return new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            TEST_CA,
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            java.util.Map.of(
                "app.kubernetes.io/name", "cluster-infra-rca-agent",
                "cluster-infra-rca.io/cluster-id", "cluster-1"
            ),
            imageDigest,
            true
        );
    }

    private void saveReturnsInput() {
        when(enrollments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
