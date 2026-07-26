package io.clusterinfra.rca.webconsole.maintenance;

import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AgentEnrollmentMigrationCli {
    static final String APPLY_CONFIRMATION =
        "APPLY_AGENT_ENROLLMENT_AUDIENCE_MIGRATION";
    private static final Pattern CLUSTER_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private AgentEnrollmentMigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out, System.err));
    }

    static int run(Map<String, String> environment, PrintStream output, PrintStream error) {
        try {
            Options options = Options.from(environment);
            try (Connection connection = DriverManager.getConnection(
                options.jdbcUrl(),
                options.username(),
                options.password()
            )) {
                return execute(connection, options, output);
            }
        } catch (Exception exception) {
            error.println("agent_enrollment_migration_status=failed");
            error.println("error=" + safeMessage(exception));
            return 1;
        }
    }

    static int execute(Connection connection, Options options, PrintStream output) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            List<UnsafeProfile> unsafeProfiles = unsafeProfiles(
                connection,
                options,
                options.mode() == Mode.apply
            );
            printInventory(output, options.mode(), unsafeProfiles);
            if (options.mode() == Mode.audit) {
                connection.rollback();
                return unsafeProfiles.isEmpty() ? 0 : 3;
            }
            if (unsafeProfiles.isEmpty()) {
                connection.rollback();
                output.println("migration_result=no_changes");
                return 0;
            }

            Instant now = Instant.now();
            int migratedProfiles = 0;
            int revokedNodeTokens = 0;
            for (UnsafeProfile profile : unsafeProfiles) {
                int updated = updateProfile(connection, profile, options.targetAudience(), now);
                if (updated != 1) {
                    throw new SQLException(
                        "profile changed concurrently for cluster " + profile.clusterId()
                    );
                }
                migratedProfiles += updated;
                revokedNodeTokens += revokeNodeTokens(connection, profile.clusterId(), now);
            }
            connection.commit();
            output.println("migration_result=applied");
            output.println("migrated_profile_count=" + migratedProfiles);
            output.println("revoked_node_token_count=" + revokedNodeTokens);
            output.println("target_audience=" + options.targetAudience());
            return 0;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static List<UnsafeProfile> unsafeProfiles(
        Connection connection,
        Options options,
        boolean lock
    ) throws SQLException {
        String sql = """
            SELECT cluster_id, audience, profile_version
            FROM agent_enrollment_profiles
            ORDER BY cluster_id
            """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.ArrayList<UnsafeProfile> profiles = new java.util.ArrayList<>();
            while (resultSet.next()) {
                String clusterId = resultSet.getString("cluster_id");
                String audience = resultSet.getString("audience");
                if (options.apiAudiences().contains(audience)
                    && options.includes(clusterId)) {
                    profiles.add(new UnsafeProfile(
                        clusterId,
                        audience,
                        resultSet.getLong("profile_version")
                    ));
                }
            }
            return List.copyOf(profiles);
        }
    }

    private static int updateProfile(
        Connection connection,
        UnsafeProfile profile,
        String targetAudience,
        Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE agent_enrollment_profiles
            SET audience = ?, profile_version = profile_version + 1, updated_at = ?
            WHERE cluster_id = ? AND audience = ? AND profile_version = ?
            """)) {
            statement.setString(1, targetAudience);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, profile.clusterId());
            statement.setString(4, profile.audience());
            statement.setLong(5, profile.profileVersion());
            return statement.executeUpdate();
        }
    }

    private static int revokeNodeTokens(
        Connection connection,
        String clusterId,
        Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE node_agents
            SET node_token_revoked_at = ?,
                next_node_token_hash = NULL,
                next_node_token_expires_at = NULL
            WHERE cluster_id = ? AND node_token_revoked_at IS NULL
            """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, clusterId);
            return statement.executeUpdate();
        }
    }

    private static void printInventory(
        PrintStream output,
        Mode mode,
        List<UnsafeProfile> unsafeProfiles
    ) {
        output.println("agent_enrollment_migration_mode=" + mode);
        output.println("unsafe_profile_count=" + unsafeProfiles.size());
        for (UnsafeProfile profile : unsafeProfiles) {
            output.println(
                "unsafe_profile=" + profile.clusterId()
                    + ",audience=" + profile.audience()
                    + ",profile_version=" + profile.profileVersion()
            );
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("[\\r\\n\\t]", " ");
    }

    enum Mode {
        audit,
        apply
    }

    record UnsafeProfile(String clusterId, String audience, long profileVersion) {
    }

    record Options(
        Mode mode,
        String jdbcUrl,
        String username,
        String password,
        Set<String> apiAudiences,
        Set<String> clusters,
        String targetAudience
    ) {
        static Options from(Map<String, String> environment) {
            Mode mode = mode(requiredOrDefault(
                environment,
                "RCA_AGENT_ENROLLMENT_MIGRATION_MODE",
                "audit"
            ));
            String jdbcUrl = requiredAny(
                environment,
                "RCA_JDBC_URL",
                "SPRING_DATASOURCE_URL"
            );
            String username = requiredAny(
                environment,
                "RCA_DB_USERNAME",
                "SPRING_DATASOURCE_USERNAME"
            );
            String password = valueAny(
                environment,
                "RCA_DB_PASSWORD",
                "SPRING_DATASOURCE_PASSWORD"
            );
            Set<String> apiAudiences = csv(
                requiredAny(environment, "RCA_KUBERNETES_API_AUDIENCES"),
                "RCA_KUBERNETES_API_AUDIENCES"
            );
            String targetAudience = requiredOrDefault(
                environment,
                "RCA_AGENT_ENROLLMENT_MIGRATION_TARGET_AUDIENCE",
                AgentSecurityPolicy.DEFAULT_ENROLLMENT_AUDIENCE
            ).trim();
            validateAudience(targetAudience, "target audience");
            if (apiAudiences.contains(targetAudience)) {
                throw new IllegalArgumentException(
                    "migration target audience must not be a Kubernetes API audience"
                );
            }

            Set<String> clusters = Set.of();
            if (mode == Mode.apply) {
                if (!APPLY_CONFIRMATION.equals(
                    environment.get("RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM")
                )) {
                    throw new IllegalArgumentException(
                        "apply mode requires RCA_AGENT_ENROLLMENT_MIGRATION_CONFIRM="
                            + APPLY_CONFIRMATION
                    );
                }
                clusters = csv(
                    requiredAny(environment, "RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS"),
                    "RCA_AGENT_ENROLLMENT_MIGRATION_CLUSTERS"
                );
                if (clusters.size() > 100 || clusters.stream().anyMatch(
                    value -> !CLUSTER_ID.matcher(value).matches()
                )) {
                    throw new IllegalArgumentException(
                        "migration clusters must contain 1-100 valid cluster IDs"
                    );
                }
            }
            return new Options(
                mode,
                jdbcUrl,
                username,
                password,
                apiAudiences,
                clusters,
                targetAudience
            );
        }

        boolean includes(String clusterId) {
            return mode == Mode.audit || clusters.contains(clusterId);
        }

        private static Mode mode(String value) {
            try {
                return Mode.valueOf(value.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "RCA_AGENT_ENROLLMENT_MIGRATION_MODE must be audit or apply"
                );
            }
        }

        private static Set<String> csv(String value, String field) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .forEach(item -> {
                    validateAudience(item, field);
                    values.add(item);
                });
            if (values.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return Set.copyOf(values);
        }

        private static void validateAudience(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 255
                || value.chars().anyMatch(Character::isWhitespace)
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " contains an invalid value");
            }
        }

        private static String requiredAny(Map<String, String> values, String... keys) {
            String value = valueAny(values, keys);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    String.join(" or ", keys) + " is required"
                );
            }
            return value.trim();
        }

        private static String valueAny(Map<String, String> values, String... keys) {
            for (String key : keys) {
                if (values.containsKey(key)) {
                    return values.get(key);
                }
            }
            return "";
        }

        private static String requiredOrDefault(
            Map<String, String> values,
            String key,
            String fallback
        ) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
