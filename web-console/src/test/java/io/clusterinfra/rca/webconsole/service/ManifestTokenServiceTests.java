package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.ManifestTokenRepository;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManifestTokenServiceTests {
    @Test
    void issueStoresOnlyHashedTokenAndAppliesMinimumTtl() {
        ManifestTokenRepository repository = mock(ManifestTokenRepository.class);
        ManifestTokenService service = service(repository, 5);

        ManifestTokenService.IssuedManifestToken issued = service.issue("cluster-1", "admin");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> createdAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteExpired(createdAt.capture());
        verify(repository).create(
            anyString(),
            eq("cluster-1"),
            hash.capture(),
            eq("admin"),
            createdAt.capture(),
            expiresAt.capture()
        );
        assertThat(issued.token()).isNotBlank();
        assertThat(hash.getValue()).hasSize(64).isNotEqualTo(issued.token());
        assertThat(Duration.between(createdAt.getValue(), expiresAt.getValue())).isEqualTo(Duration.ofSeconds(30));
        assertThat(issued.expiresAt()).isEqualTo(expiresAt.getValue());
    }

    @Test
    void issueAppliesMaximumTtlAndNormalizesBlankCreator() {
        ManifestTokenRepository repository = mock(ManifestTokenRepository.class);
        ManifestTokenService service = service(repository, 5_000);

        service.issue("cluster-1", " ");

        ArgumentCaptor<Instant> createdAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).create(
            anyString(),
            eq("cluster-1"),
            anyString(),
            eq("unknown"),
            createdAt.capture(),
            expiresAt.capture()
        );
        assertThat(Duration.between(createdAt.getValue(), expiresAt.getValue())).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void consumeDelegatesHashedTokenAndRejectsBlankToken() {
        ManifestTokenRepository repository = mock(ManifestTokenRepository.class);
        ManifestTokenService service = service(repository, 300);
        when(repository.consume(eq("cluster-1"), anyString(), any(Instant.class))).thenReturn(true);

        assertThat(service.consume("cluster-1", "manifest-secret")).isTrue();

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).consume(eq("cluster-1"), hash.capture(), any(Instant.class));
        assertThat(hash.getValue()).hasSize(64).isNotEqualTo("manifest-secret");

        ManifestTokenRepository unusedRepository = mock(ManifestTokenRepository.class);
        ManifestTokenService unusedService = service(unusedRepository, 300);
        assertThat(unusedService.consume("cluster-1", " ")).isFalse();
        verifyNoInteractions(unusedRepository);
    }

    private RcaConsoleProperties properties(int ttlSeconds) {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setManifestTokenTtlSeconds(ttlSeconds);
        return properties;
    }

    private ManifestTokenService service(ManifestTokenRepository repository, int ttlSeconds) {
        return new ManifestTokenService(
            repository,
            properties(ttlSeconds),
            new TokenGenerator(),
            new Sha256Digest()
        );
    }
}
