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
    void opaqueTokenKeyRingSupportsStagedRollingRotationAndProgressiveRehash() {
        String oldPepper = "old-unit-test-pepper-value-with-32-bytes";
        String newPepper = "new-unit-test-pepper-value-with-32-bytes";
        OpaqueTokenHasher oldWriter = opaqueHasher(
            oldPepper,
            "key-old",
            "key-new=" + newPepper,
            "v1",
            false
        );
        OpaqueTokenHasher newWriter = opaqueHasher(
            newPepper,
            "key-new",
            "key-old=" + oldPepper,
            "v2",
            false
        );

        String oldHash = oldWriter.hash("machine-token");
        String newHash = newWriter.hash("machine-token");

        assertThat(oldHash).startsWith("hmac_sha256$v1$");
        assertThat(newHash).startsWith("hmac_sha256$v2$key-new$");
        assertThat(oldWriter.verify("machine-token", newHash))
            .isEqualTo(new OpaqueTokenHasher.Verification(true, false));
        assertThat(newWriter.verify("machine-token", oldHash))
            .isEqualTo(new OpaqueTokenHasher.Verification(true, false));

        OpaqueTokenHasher rehashingWriter = opaqueHasher(
            newPepper,
            "key-new",
            "key-old=" + oldPepper,
            "v2",
            true
        );
        assertThat(rehashingWriter.verify("machine-token", oldHash))
            .isEqualTo(new OpaqueTokenHasher.Verification(true, true));
        assertThat(rehashingWriter.verify("machine-token", newHash))
            .isEqualTo(new OpaqueTokenHasher.Verification(true, false));
    }

    @Test
    void opaqueTokenKeyRingRejectsUnsafeStructure() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            opaqueHasher(
                "current-unit-test-pepper-value-32-bytes",
                "key-current",
                "key-current=previous-unit-test-pepper-value-32-bytes",
                "v2",
                false
            )
        )).hasMessageContaining("must not repeat the current key id");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            opaqueHasher(
                "current-unit-test-pepper-value-32-bytes",
                "invalid key id",
                "",
                "v2",
                false
            )
        )).hasMessageContaining("key ids must match");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            opaqueHasher(
                "current-unit-test-pepper-value-32-bytes",
                "key-current",
                "",
                "v1",
                true
            )
        )).hasMessageContaining("requires RCA_OPAQUE_TOKEN_WRITE_VERSION=v2");
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
        return opaqueHasher(pepper, "legacy", "", "v1", false);
    }

    private OpaqueTokenHasher opaqueHasher(
        String pepper,
        String keyId,
        String previousKeys,
        String writeVersion,
        boolean rehashOnAuthentication
    ) {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setOpaqueTokenPepper(pepper);
        properties.getSecurity().setOpaqueTokenKeyId(keyId);
        properties.getSecurity().setOpaqueTokenPreviousKeys(previousKeys);
        properties.getSecurity().setOpaqueTokenWriteVersion(writeVersion);
        properties.getSecurity().setOpaqueTokenRehashOnAuthentication(
            rehashOnAuthentication
        );
        return new OpaqueTokenHasher(properties);
    }
}
