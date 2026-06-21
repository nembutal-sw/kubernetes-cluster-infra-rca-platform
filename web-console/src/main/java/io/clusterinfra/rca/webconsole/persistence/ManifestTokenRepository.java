package io.clusterinfra.rca.webconsole.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ManifestTokenRepository {
    private final JdbcTemplate jdbc;

    public ManifestTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(
        String tokenId,
        String clusterId,
        String tokenHash,
        String createdBy,
        Instant createdAt,
        Instant expiresAt
    ) {
        jdbc.update(
            """
            INSERT INTO manifest_download_tokens
                (token_id, cluster_id, token_hash, created_by, created_at, expires_at, consumed_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL)
            """,
            tokenId,
            clusterId,
            tokenHash,
            createdBy,
            timestamp(createdAt),
            timestamp(expiresAt)
        );
    }

    public boolean consume(String clusterId, String tokenHash, Instant consumedAt) {
        return jdbc.update(
            """
            UPDATE manifest_download_tokens
            SET consumed_at = ?
            WHERE cluster_id = ?
              AND token_hash = ?
              AND consumed_at IS NULL
              AND expires_at > ?
            """,
            timestamp(consumedAt),
            clusterId,
            tokenHash,
            timestamp(consumedAt)
        ) == 1;
    }

    public int deleteExpired(Instant now) {
        return jdbc.update(
            "DELETE FROM manifest_download_tokens WHERE expires_at <= ?",
            timestamp(now)
        );
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
