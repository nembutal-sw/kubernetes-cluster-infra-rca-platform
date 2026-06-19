package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.security.TokenService;
import org.junit.jupiter.api.Test;

class TokenServiceTests {
    private static final String PYTHON_COMPATIBLE_VECTOR =
        "pbkdf2_sha256$210000$AAECAwQFBgcICQoLDA0ODw$48lTXWG2pKRFYa2VDSIa1k9iNJ_kpewyX2PSJx1eg5Q";

    private final TokenService tokens = new TokenService();

    @Test
    void verifiesExistingPythonPbkdf2HashFormat() {
        assertThat(tokens.verifyPassword("admin", PYTHON_COMPATIBLE_VECTOR)).isTrue();
        assertThat(tokens.verifyPassword("wrong", PYTHON_COMPATIBLE_VECTOR)).isFalse();
    }

    @Test
    void generatedHashRoundTrips() {
        String hash = tokens.hashPassword("a-secure-password");

        assertThat(hash).startsWith("pbkdf2_sha256$210000$");
        assertThat(tokens.verifyPassword("a-secure-password", hash)).isTrue();
    }
}
