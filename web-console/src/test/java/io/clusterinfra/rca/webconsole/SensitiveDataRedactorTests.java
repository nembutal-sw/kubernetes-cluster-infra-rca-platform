package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTests {
    @Test
    void redactsSensitiveKeysAndEmbeddedCredentialsRecursively() {
        Map<String, Object> redacted = SensitiveDataRedactor.redactMap(Map.of(
            "authorization", "Bearer top-secret-token",
            "nested", Map.of(
                "database_password", "super-secret",
                "messages", List.of(
                    "token=abc123 request failed",
                    "api_key: sk-1234567890abcdef"
                )
            ),
            "safe", "disk usage is 95%"
        ));

        assertThat(redacted.toString())
            .contains("[redacted]")
            .contains("disk usage is 95%")
            .doesNotContain("top-secret-token")
            .doesNotContain("super-secret")
            .doesNotContain("abc123")
            .doesNotContain("1234567890abcdef");
    }
}
