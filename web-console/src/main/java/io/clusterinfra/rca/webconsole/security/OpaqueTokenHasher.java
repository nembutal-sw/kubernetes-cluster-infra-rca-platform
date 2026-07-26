package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class OpaqueTokenHasher {
    private static final String V1_PREFIX = "hmac_sha256$v1$";
    private static final String V2_PREFIX = "hmac_sha256$v2$";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final OpaqueTokenKeyRing.WriteVersion writeVersion;
    private final boolean rehashOnAuthentication;

    public OpaqueTokenHasher(RcaConsoleProperties properties) {
        OpaqueTokenKeyRing keyRing = OpaqueTokenKeyRing.from(properties.getSecurity());
        this.currentKeyId = keyRing.currentKeyId();
        this.keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keyRing.keys().entrySet()) {
            this.keys.put(
                entry.getKey(),
                entry.getValue().getBytes(StandardCharsets.UTF_8)
            );
        }
        this.writeVersion = keyRing.writeVersion();
        this.rehashOnAuthentication = keyRing.rehashOnAuthentication();
    }

    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("opaque token must not be blank");
        }
        String digest = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac(token, keys.get(currentKeyId)));
        if (writeVersion == OpaqueTokenKeyRing.WriteVersion.V1) {
            return V1_PREFIX + digest;
        }
        return V2_PREFIX + currentKeyId + "$" + digest;
    }

    public boolean matches(String token, String encodedHash) {
        return verify(token, encodedHash).matched();
    }

    public Verification verify(String token, String encodedHash) {
        if (token == null || encodedHash == null) {
            return Verification.notMatched();
        }
        if (encodedHash.startsWith(V2_PREFIX)) {
            return verifyV2(token, encodedHash);
        }
        if (encodedHash.startsWith(V1_PREFIX)) {
            return verifyV1(token, encodedHash);
        }
        return Verification.notMatched();
    }

    private Verification verifyV2(String token, String encodedHash) {
        try {
            String remainder = encodedHash.substring(V2_PREFIX.length());
            int separator = remainder.indexOf('$');
            if (separator < 1 || separator == remainder.length() - 1
                || remainder.indexOf('$', separator + 1) >= 0) {
                return Verification.notMatched();
            }
            String keyId = remainder.substring(0, separator);
            byte[] key = keys.get(keyId);
            if (key == null) {
                return Verification.notMatched();
            }
            byte[] expected = decodeDigest(remainder.substring(separator + 1));
            boolean matched = expected.length == 32
                && MessageDigest.isEqual(mac(token, key), expected);
            return matched
                ? matched(keyId, OpaqueTokenKeyRing.WriteVersion.V2)
                : Verification.notMatched();
        } catch (RuntimeException exception) {
            return Verification.notMatched();
        }
    }

    private Verification verifyV1(String token, String encodedHash) {
        try {
            byte[] expected = decodeDigest(encodedHash.substring(V1_PREFIX.length()));
            if (expected.length != 32) {
                return Verification.notMatched();
            }
            String matchedKeyId = null;
            for (Map.Entry<String, byte[]> entry : keys.entrySet()) {
                boolean candidateMatched = MessageDigest.isEqual(
                    mac(token, entry.getValue()),
                    expected
                );
                if (candidateMatched && matchedKeyId == null) {
                    matchedKeyId = entry.getKey();
                }
            }
            return matchedKeyId == null
                ? Verification.notMatched()
                : matched(matchedKeyId, OpaqueTokenKeyRing.WriteVersion.V1);
        } catch (RuntimeException exception) {
            return Verification.notMatched();
        }
    }

    private Verification matched(
        String matchedKeyId,
        OpaqueTokenKeyRing.WriteVersion storedVersion
    ) {
        boolean canonical = currentKeyId.equals(matchedKeyId)
            && writeVersion == storedVersion;
        return new Verification(true, rehashOnAuthentication && !canonical);
    }

    private byte[] decodeDigest(String encodedDigest) {
        return Base64.getUrlDecoder().decode(encodedDigest);
    }

    private byte[] mac(String token, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    public record Verification(boolean matched, boolean rehashRequired) {
        private static Verification notMatched() {
            return new Verification(false, false);
        }
    }
}
