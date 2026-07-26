package io.clusterinfra.rca.webconsole.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class AgentEnrollmentSecurityStartupValidatorTests {
    @Test
    void productionRejectsStoredProfilesUsingKubernetesApiAudience() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        AgentEnrollmentRepository repository = mock(AgentEnrollmentRepository.class);
        when(repository.findAllConfigurations()).thenReturn(List.of(
            configuration("cluster-unsafe", "https://kubernetes.default.svc")
        ));
        RcaConsoleProperties properties = new RcaConsoleProperties();
        AgentEnrollmentSecurityStartupValidator validator =
            new AgentEnrollmentSecurityStartupValidator(
                environment,
                repository,
                new AgentSecurityPolicy(properties)
            );

        assertThatThrownBy(() -> validator.run(mock(ApplicationArguments.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cluster-unsafe");
    }

    @Test
    void productionAcceptsStoredProfilesUsingDedicatedAudience() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AgentEnrollmentRepository repository = mock(AgentEnrollmentRepository.class);
        when(repository.findAllConfigurations()).thenReturn(List.of(
            configuration(
                "cluster-safe",
                AgentSecurityPolicy.DEFAULT_ENROLLMENT_AUDIENCE
            )
        ));
        RcaConsoleProperties properties = new RcaConsoleProperties();
        AgentEnrollmentSecurityStartupValidator validator =
            new AgentEnrollmentSecurityStartupValidator(
                environment,
                repository,
                new AgentSecurityPolicy(properties)
            );

        assertThatCode(() -> validator.run(mock(ApplicationArguments.class)))
            .doesNotThrowAnyException();
    }

    private AgentEnrollmentConfiguration configuration(
        String clusterId,
        String audience
    ) {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new AgentEnrollmentConfiguration(
            clusterId,
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "test-ca-sha",
            audience,
            "rca-system",
            "cluster-infra-rca-agent",
            1,
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            Map.of("cluster-infra-rca.io/cluster-id", clusterId),
            "sha256:" + "a".repeat(64),
            false,
            now,
            now
        );
    }
}
