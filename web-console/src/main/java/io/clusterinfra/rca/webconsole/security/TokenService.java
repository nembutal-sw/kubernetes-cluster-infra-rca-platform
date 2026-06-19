package io.clusterinfra.rca.webconsole.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hashPassword(String password) {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] digest = derive(password, salt, ITERATIONS);
        return String.join("$", HASH_PREFIX, Integer.toString(ITERATIONS), encode(salt), encode(digest));
    }

    public boolean verifyPassword(String password, String encodedHash) {
        try {
            String[] parts = encodedHash.split("\\$", 4);
            if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = decode(parts[2]);
            byte[] expected = decode(parts[3]);
            return MessageDigest.isEqual(derive(password, salt, iterations), expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String generateToken() {
        return encode(randomBytes(TOKEN_BYTES));
    }

    public String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
