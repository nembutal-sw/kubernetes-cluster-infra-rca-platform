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
                    "api_key: " + "sk" + "-1234567890abcdef"
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

    @Test
    void redactsCommonCloudSourceControlAndKubeconfigSecrets() {
        String githubToken = "gh" + "p_abcdefghijklmnopqrstuvwxyz123456";
        String slackToken = "xox" + "b-1234567890-abcdefghijklmnop";
        String awsAccessKey = "AK" + "IAABCDEFGHIJKLMNOP";
        String redacted = SensitiveDataRedactor.redactText(
            "github=" + githubToken + " "
                + "slack=" + slackToken + " "
                + "aws=" + awsAccessKey + " "
                + "db=postgresql://rca:super-secret@db.internal/rca"
        );

        assertThat(redacted)
            .doesNotContain("abcdefghijklmnopqrstuvwxyz123456")
            .doesNotContain("abcdefghijklmnop")
            .doesNotContain(awsAccessKey)
            .doesNotContain("super-secret")
            .contains("[redacted]");

        Map<String, Object> kubeconfig = SensitiveDataRedactor.redactMap(Map.of(
            "client-certificate-data", "base64-certificate",
            "client-key-data", "base64-private-key",
            "certificate-authority-data", "base64-ca"
        ));
        assertThat(kubeconfig.values()).containsOnly("[redacted]");
    }

    @Test
    void redactsGoogleApiKeyFormatsWithoutAFieldLabel() {
        String legacyKey = "AI" + "za" + "A".repeat(35);
        String currentKey = "AQ." + "B".repeat(40);

        String redacted = SensitiveDataRedactor.redactText(
            "legacy=" + legacyKey + " current=" + currentKey
        );

        assertThat(redacted)
            .contains("[redacted]")
            .doesNotContain(legacyKey)
            .doesNotContain(currentKey);
    }
}
