package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;

    public UserRepository(JdbcTemplate jdbc, PasswordHasher passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public Optional<UserAccount> authenticate(String username, String password) {
        Optional<UserRow> row = findRowByEmail(username);
        if (row.isEmpty() || !passwords.matches(password, row.get().passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(row.get().account());
    }

    @Transactional
    public UserAccount ensureDefaultAdmin(String username, String password) {
        String normalized = username.trim().toLowerCase();
        Optional<UserRow> existing = findRowByEmail(normalized);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            UserAccount user = existing.get().account();
            jdbc.update(
                """
                    UPDATE user_accounts SET requested_role = ?, role = ?, status = ?,
                        approved_by = COALESCE(approved_by, ?), approved_at = COALESCE(approved_at, ?)
                    WHERE user_id = ?
                    """,
                UserRole.admin.name(),
                UserRole.admin.name(),
                UserStatus.active.name(),
                "system",
                timestamp(now),
                user.userId()
            );
            return find(user.userId()).orElseThrow();
        }

        Optional<UserRow> primaryAdmin = findRowById("user-admin");
        if (primaryAdmin.isPresent()) {
            UserAccount user = primaryAdmin.get().account();
            jdbc.update(
                """
                    UPDATE user_accounts SET requested_role = ?, role = ?, status = ?,
                        approved_by = COALESCE(approved_by, ?), approved_at = COALESCE(approved_at, ?)
                    WHERE user_id = ?
                    """,
                UserRole.admin.name(),
                UserRole.admin.name(),
                UserStatus.active.name(),
                "system",
                timestamp(now),
                user.userId()
            );
            return find(user.userId()).orElseThrow();
        }

        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "user-admin",
            normalized,
            "Administrator",
            passwords.hash(password),
            UserRole.admin.name(),
            UserRole.admin.name(),
            UserStatus.active.name(),
            null,
            null,
            "system",
            timestamp(now),
            timestamp(now)
        );
        return find("user-admin").orElseThrow();
    }

    public Optional<UserAccount> find(String userId) {
        return findRowById(userId).map(UserRow::account);
    }

    @Transactional
    public boolean changePassword(String userId, String currentPassword, String newPassword) {
        try {
            String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM user_accounts WHERE user_id = ?",
                String.class,
                userId
            );
            if (passwordHash == null || !passwords.matches(currentPassword, passwordHash)) {
                return false;
            }
            jdbc.update(
                "UPDATE user_accounts SET password_hash = ? WHERE user_id = ?",
                passwords.hash(newPassword),
                userId
            );
            return true;
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    @Transactional
    public Optional<UserAccount> changeLoginId(String userId, String currentPassword, String newUsername) {
        String normalized = newUsername.trim().toLowerCase();
        try {
            UserRow current = jdbc.queryForObject(
                "SELECT * FROM user_accounts WHERE user_id = ?",
                (resultSet, rowNumber) -> mapUserRow(resultSet),
                userId
            );
            if (current == null || !passwords.matches(currentPassword, current.passwordHash())) {
                return Optional.empty();
            }
            Optional<UserRow> existing = findRowByEmail(normalized);
            if (existing.isPresent() && !existing.get().account().userId().equals(userId)) {
                throw new DuplicateLoginIdException(normalized);
            }
            if (!current.account().email().equals(normalized)) {
                jdbc.update(
                    "UPDATE user_accounts SET email = ? WHERE user_id = ?",
                    normalized,
                    userId
                );
            }
            return find(userId);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private Optional<UserRow> findRowByEmail(String email) {
        return optionalQuery(
            "SELECT * FROM user_accounts WHERE email = ?",
            (resultSet, rowNumber) -> mapUserRow(resultSet),
            email.trim().toLowerCase()
        );
    }

    private Optional<UserRow> findRowById(String userId) {
        return optionalQuery(
            "SELECT * FROM user_accounts WHERE user_id = ?",
            (resultSet, rowNumber) -> mapUserRow(resultSet),
            userId
        );
    }

    private UserRow mapUserRow(ResultSet resultSet) throws SQLException {
        UserAccount account = new UserAccount(
            resultSet.getString("user_id"),
            resultSet.getString("email"),
            resultSet.getString("full_name"),
            UserRole.valueOf(resultSet.getString("requested_role")),
            enumOrNull(UserRole.class, resultSet.getString("role")),
            UserStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("reason"),
            resultSet.getString("approval_note"),
            resultSet.getString("approved_by"),
            instant(resultSet, "created_at"),
            instant(resultSet, "approved_at")
        );
        return new UserRow(account, resultSet.getString("password_hash"));
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

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record UserRow(UserAccount account, String passwordHash) {
    }
}
