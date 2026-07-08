package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraft;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogOverrideDraftRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> DIFF_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogOverrideDraftRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CatalogOverrideDraft create(
        String overrideJson,
        Map<String, Object> previewSummary,
        List<Map<String, Object>> diff,
        boolean diffTruncated,
        String validationMessage,
        String reason,
        String requestedBy
    ) {
        Instant now = databaseInstant();
        CatalogOverrideDraft draft = new CatalogOverrideDraft(
            id(),
            CatalogOverrideStatus.draft,
            overrideJson,
            previewSummary == null ? Map.of() : previewSummary,
            diff == null ? List.of() : diff,
            diffTruncated,
            blankToNull(validationMessage),
            blankToNull(reason),
            blankToNull(requestedBy),
            null,
            null,
            now,
            now,
            null
        );
        jdbc.update(
            """
                INSERT INTO catalog_override_drafts
                    (draft_id, status, override_json, preview_summary_json, diff_json, diff_truncated,
                     validation_message, reason, requested_by, reviewed_by, decision_note,
                     created_at, updated_at, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            draft.draftId(),
            draft.status().name(),
            draft.overrideJson(),
            write(draft.previewSummary()),
            write(draft.diff()),
            draft.diffTruncated() ? 1 : 0,
            draft.validationMessage(),
            draft.reason(),
            draft.requestedBy(),
            null,
            null,
            timestamp(draft.createdAt()),
            timestamp(draft.updatedAt()),
            null
        );
        return draft;
    }

    public Optional<CatalogOverrideDraft> find(String draftId) {
        return optionalQuery(
            "SELECT * FROM catalog_override_drafts WHERE draft_id = ?",
            this::mapDraft,
            draftId
        );
    }

    public List<CatalogOverrideDraft> list(String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (status == null || status.isBlank()) {
            return jdbc.query(
                "SELECT * FROM catalog_override_drafts ORDER BY created_at DESC LIMIT ?",
                this::mapDraft,
                safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM catalog_override_drafts WHERE status = ? ORDER BY created_at DESC LIMIT ?",
            this::mapDraft,
            status,
            safeLimit
        );
    }

    @Transactional
    public Optional<CatalogOverrideDraft> decide(
        String draftId,
        CatalogOverrideStatus status,
        String reviewedBy,
        String decisionNote
    ) {
        Instant now = databaseInstant();
        int updated = jdbc.update(
            """
                UPDATE catalog_override_drafts
                   SET status = ?, reviewed_by = ?, decision_note = ?, updated_at = ?, reviewed_at = ?
                 WHERE draft_id = ? AND status = ?
                """,
            status.name(),
            blankToNull(reviewedBy),
            blankToNull(decisionNote),
            timestamp(now),
            timestamp(now),
            draftId,
            CatalogOverrideStatus.draft.name()
        );
        return updated == 0 ? Optional.empty() : find(draftId);
    }

    public Optional<CatalogOverrideDraft> discard(String draftId, String reviewedBy, String decisionNote) {
        return decide(draftId, CatalogOverrideStatus.discarded, reviewedBy, decisionNote);
    }

    private CatalogOverrideDraft mapDraft(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CatalogOverrideDraft(
            resultSet.getString("draft_id"),
            CatalogOverrideStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("override_json"),
            read(resultSet.getString("preview_summary_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("diff_json"), DIFF_LIST, List.of()),
            resultSet.getInt("diff_truncated") != 0,
            resultSet.getString("validation_message"),
            resultSet.getString("reason"),
            resultSet.getString("requested_by"),
            resultSet.getString("reviewed_by"),
            resultSet.getString("decision_note"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "reviewed_at")
        );
    }

    private <T> Optional<T> optionalQuery(
        String sql,
        org.springframework.jdbc.core.RowMapper<T> rowMapper,
        Object... parameters
    ) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, rowMapper, parameters));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize catalog override draft JSON", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored catalog override draft JSON is invalid", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant databaseInstant() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return "catalog-override-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
