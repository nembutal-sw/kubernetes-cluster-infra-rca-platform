package db.migration;

import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V14__widen_audit_outcome extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection()
            .getMetaData()
            .getDatabaseProductName()
            .toLowerCase(Locale.ROOT);
        String sql;
        if (product.contains("postgresql")) {
            sql = "ALTER TABLE audit_events ALTER COLUMN outcome TYPE VARCHAR(128)";
        } else if (product.contains("mariadb") || product.contains("mysql")) {
            sql = "ALTER TABLE audit_events MODIFY outcome VARCHAR(128) NOT NULL";
        } else if (product.contains("h2")) {
            sql = "ALTER TABLE audit_events ALTER COLUMN outcome VARCHAR(128) NOT NULL";
        } else {
            throw new IllegalStateException("Unsupported database for audit outcome migration: " + product);
        }

        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }
}
