package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.CursorPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

public final class CursorPageSupport {
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final int MAX_QUERY_LENGTH = 200;

    private CursorPageSupport() {
    }

    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cursor = value.trim();
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("cursor is too long");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\n", -1);
            if (parts.length != 2 || parts[1].isBlank() || parts[1].length() > 128) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid");
        }
    }

    public static String cleanQuery(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String query = value.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query must be 200 characters or fewer");
        }
        return query;
    }

    public static String likePattern(String query) {
        String escaped = query.toLowerCase(java.util.Locale.ROOT)
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
        return "%" + escaped + "%";
    }

    public static int safeLimit(Integer value) {
        return value == null ? 50 : Math.max(1, Math.min(value, 200));
    }

    public static <T> CursorPage<T> page(
        List<T> rows,
        int limit,
        long total,
        Function<T, Instant> timestamp,
        Function<T, String> id
    ) {
        boolean hasMore = rows.size() > limit;
        List<T> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String nextCursor = hasMore && !items.isEmpty()
            ? encode(timestamp.apply(items.getLast()), id.apply(items.getLast()))
            : null;
        return new CursorPage<>(items, nextCursor, hasMore, total, limit);
    }

    private static String encode(Instant timestamp, String id) {
        if (timestamp == null || id == null || id.isBlank()) {
            throw new IllegalStateException("page row is missing cursor fields");
        }
        String value = timestamp + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public record Cursor(Instant timestamp, String id) {
    }
}
