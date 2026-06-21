package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ManifestTokenRepositoryTests {
    @Test
    void bindsInstantValuesAsJdbcTimestamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManifestTokenRepository repository = new ManifestTokenRepository(jdbc);
        Instant createdAt = Instant.parse("2026-06-22T00:00:00Z");
        Instant expiresAt = createdAt.plusSeconds(300);

        repository.create(
            "token-1",
            "cluster-1",
            "hash",
            "admin",
            createdAt,
            expiresAt
        );

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(any(String.class), parameters.capture());
        assertThat(parameters.getValue()[4]).isEqualTo(Timestamp.from(createdAt));
        assertThat(parameters.getValue()[5]).isEqualTo(Timestamp.from(expiresAt));
    }

    @Test
    void consumesAndDeletesWithJdbcTimestamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManifestTokenRepository repository = new ManifestTokenRepository(jdbc);
        Instant now = Instant.parse("2026-06-22T00:05:00Z");
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThat(repository.consume("cluster-1", "hash", now)).isTrue();
        assertThat(repository.deleteExpired(now)).isEqualTo(1);

        verify(jdbc).update(
            any(String.class),
            eq(Timestamp.from(now)),
            eq("cluster-1"),
            eq("hash"),
            eq(Timestamp.from(now))
        );
        verify(jdbc).update(any(String.class), eq(Timestamp.from(now)));
    }
}
