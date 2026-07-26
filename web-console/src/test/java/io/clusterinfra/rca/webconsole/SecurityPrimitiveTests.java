package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.security.OpaqueTokenHasher;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import org.junit.jupiter.api.Test;

class SecurityPrimitiveTests {
    private static final String PYTHON_COMPATIBLE_VECTOR =
        "pbkdf2_sha256$210000$AAECAwQFBgcICQoLDA0ODw$48lTXWG2pKRFYa2VDSIa1k9iNJ_kpewyX2PSJx1eg5Q";

    private final PasswordHasher passwords = new PasswordHasher();

    @Test
    void verifiesExistingPythonPbkdf2HashFormat() {
        assertThat(passwords.matches("admin", PYTHON_COMPATIBLE_VECTOR)).isTrue();
        assertThat(passwords.matches("wrong", PYTHON_COMPATIBLE_VECTOR)).isFalse();
    }

    @Test
    void generatedPasswordHashRoundTrips() {
        String hash = passwords.hash("a-secure-password");

        assertThat(hash).startsWith("pbkdf2_sha256$210000$");
        assertThat(passwords.matches("a-secure-password", hash)).isTrue();
    }

    @Test
    void opaqueTokenHashUsesPepperedHmacAndConstantFormat() {
        OpaqueTokenHasher first = opaqueHasher("first-unit-test-pepper-value-000001");
        OpaqueTokenHasher second = opaqueHasher("second-unit-test-pepper-value-00002");

        String hash = first.hash("machine-token");

        assertThat(hash).startsWith("hmac_sha256$v1$");
        assertThat(first.matches("machine-token", hash)).isTrue();
        assertThat(first.matches("wrong-token", hash)).isFalse();
        assertThat(second.matches("machine-token", hash)).isFalse();
        assertThat(first.matches("machine-token", "pbkdf2_sha256$210000$invalid")).isFalse();
    }

    @Test
    void blankOpaqueTokenPepperFallsBackToDevelopmentDefault() {
        OpaqueTokenHasher hasher = opaqueHasher(" ");

        String hash = hasher.hash("machine-token");

        assertThat(hasher.matches("machine-token", hash)).isTrue();
    }

    @Test
    void generatedTokensHaveFullEntropyAndPlainDigestRemainsStable() {
        TokenGenerator generator = new TokenGenerator();
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(new Sha256Digest().digest("stable"))
            .isEqualTo("f379ccb92b9116442dc65bdc35648a85d3786b34779db7f704a901fa07b00cb6");
    }

    private OpaqueTokenHasher opaqueHasher(String pepper) {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setOpaqueTokenPepper(pepper);
        return new OpaqueTokenHasher(properties);
    }
}
