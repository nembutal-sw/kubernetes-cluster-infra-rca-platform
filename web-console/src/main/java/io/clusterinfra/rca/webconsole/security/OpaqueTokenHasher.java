package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class OpaqueTokenHasher {
    private static final String HASH_PREFIX = "hmac_sha256$v1$";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    public OpaqueTokenHasher(RcaConsoleProperties properties) {
        String configuredPepper = properties.getSecurity().getOpaqueTokenPepper();
        if (configuredPepper == null || configuredPepper.isBlank()) {
            throw new IllegalStateException("RCA_OPAQUE_TOKEN_PEPPER must not be blank");
        }
        this.pepper = configuredPepper.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("opaque token must not be blank");
        }
        return HASH_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(token));
    }

    public boolean matches(String token, String encodedHash) {
        if (token == null || encodedHash == null || !supports(encodedHash)) {
            return false;
        }
        try {
            byte[] expected = Base64.getUrlDecoder().decode(encodedHash.substring(HASH_PREFIX.length()));
            return expected.length == 32 && MessageDigest.isEqual(mac(token), expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean supports(String encodedHash) {
        return encodedHash != null && encodedHash.startsWith(HASH_PREFIX);
    }

    private byte[] mac(String token) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }
}
