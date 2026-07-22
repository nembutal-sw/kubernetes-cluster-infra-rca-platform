package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfileUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import java.time.Instant;
import java.util.Optional;
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
    private ClusterRepository clusters;

    private AgentEnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new AgentEnrollmentService(enrollments, clusters, new RcaConsoleProperties());
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
        verify(clusters).revokeBootstrapToken("cluster-1");

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
                "https://kubernetes.default.svc",
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
            "https://kubernetes.default.svc",
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

    private AgentEnrollmentProfileUpdateRequest request(boolean fallback, String ca) {
        return new AgentEnrollmentProfileUpdateRequest(
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            ca,
            "https://kubernetes.default.svc",
            "rca-system",
            "cluster-infra-rca-agent",
            fallback
        );
    }

    private void saveReturnsInput() {
        when(enrollments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
