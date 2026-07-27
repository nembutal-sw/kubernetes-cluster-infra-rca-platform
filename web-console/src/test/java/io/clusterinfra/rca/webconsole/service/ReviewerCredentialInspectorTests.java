package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewerCredentialInspectorTests {
    @TempDir
    Path temporaryDirectory;

    private final ReviewerCredentialInspector inspector =
        new ReviewerCredentialInspector(new ObjectMapper());

    @Test
    void readsTokenAndReportsUnsignedJwtExpirationForLifecycleStatus() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3600).truncatedTo(
            java.time.temporal.ChronoUnit.SECONDS
        );
        String token = segment("{\"alg\":\"none\"}")
            + "."
            + segment("{\"exp\":" + expiresAt.getEpochSecond() + "}")
            + ".signature";
        Path tokenFile = temporaryDirectory.resolve("token");
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8);

        ReviewerCredentialInspector.Inspection inspection =
            inspector.inspect(tokenFile.toString());

        assertThat(inspection.readable()).isTrue();
        assertThat(inspection.expiresAt()).isEqualTo(expiresAt);
        assertThat(inspector.readToken(tokenFile.toString())).isEqualTo(token);
    }

    @Test
    void rejectsMissingWhitespaceAndOversizedCredentials() throws Exception {
        Path whitespace = temporaryDirectory.resolve("whitespace-token");
        Files.writeString(whitespace, "token with spaces", StandardCharsets.UTF_8);
        Path oversized = temporaryDirectory.resolve("oversized-token");
        Files.writeString(
            oversized,
            "x".repeat(32 * 1024 + 1),
            StandardCharsets.UTF_8
        );

        assertThat(inspector.inspect(temporaryDirectory.resolve("missing").toString()).issue())
            .isEqualTo("missing");
        assertThat(inspector.inspect(whitespace.toString()).issue()).isEqualTo("invalid");
        assertThatThrownBy(() -> inspector.readToken(oversized.toString()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("size limit");
    }

    private String segment(String value) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
