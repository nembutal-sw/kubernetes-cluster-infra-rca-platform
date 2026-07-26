package io.clusterinfra.rca.webconsole.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordHasher {
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int MAX_VERIFICATION_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String password) {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] digest = derive(password, salt, ITERATIONS);
        return String.join("$", HASH_PREFIX, Integer.toString(ITERATIONS), encode(salt), encode(digest));
    }

    public boolean matches(String password, String encodedHash) {
        if (password == null || encodedHash == null) {
            return false;
        }
        try {
            String[] parts = encodedHash.split("\\$", 4);
            if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > MAX_VERIFICATION_ITERATIONS) {
                return false;
            }
            byte[] salt = decode(parts[2]);
            byte[] expected = decode(parts[3]);
            if (salt.length < 8 || expected.length != KEY_BITS / 8) {
                return false;
            }
            return MessageDigest.isEqual(derive(password, salt, iterations), expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean supports(String encodedHash) {
        return encodedHash != null && encodedHash.startsWith(HASH_PREFIX + "$");
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("PBKDF2 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        secureRandom.nextBytes(value);
        return value;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
