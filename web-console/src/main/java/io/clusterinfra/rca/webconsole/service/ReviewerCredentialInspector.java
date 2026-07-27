package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class ReviewerCredentialInspector {
    private static final int MAX_TOKEN_BYTES = 32 * 1024;

    private final ObjectMapper objectMapper;

    public ReviewerCredentialInspector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Inspection inspect(String tokenPath) {
        try {
            String token = readToken(tokenPath);
            return new Inspection(true, expiration(token), null);
        } catch (NoSuchFileException exception) {
            return new Inspection(false, null, "missing");
        } catch (Exception exception) {
            return new Inspection(false, null, "invalid");
        }
    }

    String readToken(String tokenPath) throws IOException {
        Path path = Path.of(tokenPath);
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_TOKEN_BYTES + 1);
        }
        if (bytes.length > MAX_TOKEN_BYTES) {
            throw new IOException("Kubernetes reviewer token exceeds the size limit");
        }
        String token = new String(bytes, StandardCharsets.UTF_8).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("Kubernetes reviewer token is empty or invalid");
        }
        return token;
    }

    private Instant expiration(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3 || segments[1].isBlank()) {
            return null;
        }
        try {
            JsonNode claims = objectMapper.readTree(
                Base64.getUrlDecoder().decode(pad(segments[1]))
            );
            JsonNode expiration = claims.path("exp");
            if (!expiration.canConvertToLong() || expiration.asLong() <= 0) {
                return null;
            }
            return Instant.ofEpochSecond(expiration.asLong());
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }

    public record Inspection(
        boolean readable,
        Instant expiresAt,
        String issue
    ) {
        public boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }
}
