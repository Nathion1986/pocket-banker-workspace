package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Replace the V1 kind CHECK by name discovered from metadata; do not rewrite applied V1. */
public class V3__perception_extensions extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection db = context.getConnection();
    List<String> constraints = new ArrayList<>();
    try (var statement =
            db.prepareStatement(
                "SELECT t.CONSTRAINT_NAME,c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON t.CONSTRAINT_CATALOG=c.CONSTRAINT_CATALOG AND t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_NAME='PERCEPTION_SOURCES' AND t.CONSTRAINT_TYPE='CHECK'");
        var rows = statement.executeQuery()) {
      while (rows.next())
        if (rows.getString(2).toUpperCase(Locale.ROOT).contains("KIND"))
          constraints.add(rows.getString(1));
    }
    try (var statement = db.createStatement()) {
      for (String name : constraints)
        statement.execute(
            "ALTER TABLE perception_sources DROP CONSTRAINT \""
                + name.replace("\"", "\"\"")
                + "\"");
      statement.execute(
          "ALTER TABLE perception_sources ADD CONSTRAINT ck_perception_kind CHECK(kind IN ('SMS','EMAIL','LOCATION','CALENDAR','HEALTH','FLOW','DIALOGUE'))");
      statement.execute("ALTER TABLE events ADD COLUMN city VARCHAR(100)");
      statement.execute("ALTER TABLE perception_log ADD COLUMN canonical_event_id VARCHAR(36)");
      statement.execute(
          "CREATE TABLE backend_signal_aliases(user_id VARCHAR(100) NOT NULL,source_id VARCHAR(36) NOT NULL,external_hash VARCHAR(64) NOT NULL,revision BIGINT NOT NULL,event_id VARCHAR(36),PRIMARY KEY(user_id,source_id,external_hash))");
    }
  }
}
