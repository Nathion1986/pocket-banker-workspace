package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ChannelMigrationTest {
  @Test
  void cachedLegacyTelegramConversationKeepsItsSessionAcrossUpgrade() throws Exception {
    String url = "jdbc:h2:mem:legacy_channel_context;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    try {
      Flyway.configure()
          .dataSource(url, "sa", "")
          .locations("classpath:db/migration")
          .target("12")
          .load()
          .migrate();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        sql.executeUpdate(
            "INSERT INTO backend_channel_bindings VALUES('legacy-user','telegram','77','active',CURRENT_TIMESTAMP)");
        sql.executeUpdate(
            "INSERT INTO backend_sessions(id,user_id,context_cipher,expires_at,version) VALUES('telegram-77','legacy-user','retained-context',CURRENT_TIMESTAMP,4)");
      }
      Flyway.configure()
          .dataSource(url, "sa", "")
          .locations("classpath:db/migration")
          .load()
          .migrate();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        try (var row =
            sql.executeQuery(
                "SELECT session_id FROM backend_channel_bindings WHERE user_id='legacy-user'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("telegram-77");
        }
        try (var row =
            sql.executeQuery(
                "SELECT context_cipher,version FROM backend_sessions WHERE user_id='legacy-user'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("retained-context");
          assertThat(row.getInt(2)).isEqualTo(4);
        }
      }
    } finally {
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        sql.execute("DROP ALL OBJECTS");
      }
    }
  }

  @Test
  void versionTwelveFileDatabaseUpgradesWithoutLosingBindingsRepliesOrNotifications()
      throws Exception {
    Path temp = Files.createTempDirectory("c-migration-");
    String url = "jdbc:h2:file:" + temp.resolve("main") + ";MODE=PostgreSQL";
    try {
      Flyway.configure()
          .dataSource(url, "sa", "")
          .locations("classpath:db/migration")
          .target("12")
          .load()
          .migrate();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        sql.executeUpdate(
            "INSERT INTO backend_channel_bindings VALUES('legacy-user','telegram','77','active',CURRENT_TIMESTAMP)");
        sql.executeUpdate(
            "INSERT INTO backend_channel_links VALUES('legacy-link','legacy-user','retained-hash',CURRENT_TIMESTAMP,FALSE)");
        sql.executeUpdate(
            "INSERT INTO backend_telegram_updates VALUES(900,'legacy-user','processed',CURRENT_TIMESTAMP)");
        sql.executeUpdate(
            "INSERT INTO backend_notifications(id,user_id,delivery_key,kind,content_cipher,state,next_attempt_at,created_at) VALUES('legacy-notice','legacy-user','old-key','test','retained-opaque-cipher','pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      }
      var flyway =
          Flyway.configure()
              .dataSource(url, "sa", "")
              .locations("classpath:db/migration")
              .target("13")
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
      flyway.validate();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        try (var row =
            sql.executeQuery(
                "SELECT session_id FROM backend_channel_bindings WHERE external_id='77'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("shared");
        }
        try (var row =
            sql.executeQuery(
                "SELECT session_id,consumed FROM backend_channel_links WHERE id='legacy-link'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("shared");
          assertThat(row.getBoolean(2)).isFalse();
        }
        try (var row =
            sql.executeQuery(
                "SELECT state,input_hash,result_cipher FROM backend_telegram_updates WHERE update_id=900")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("processed");
          assertThat(row.getString(2)).isNull();
          assertThat(row.getString(3)).isNull();
        }
        try (var row =
            sql.executeQuery(
                "SELECT content_cipher,state FROM backend_notifications WHERE id='legacy-notice'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("retained-opaque-cipher");
          assertThat(row.getString(2)).isEqualTo("pending");
        }
        try (var row = sql.executeQuery("SELECT COUNT(*) FROM backend_channel_deliveries")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getInt(1)).isZero();
        }
      }
    } finally {
      try (var files = Files.walk(temp)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void revVersionTwelveUpgradesThroughFourteenAndRetainsAllLegacyData() throws Exception {
    Path temp = Files.createTempDirectory("c-migration-");
    String url = "jdbc:h2:file:" + temp.resolve("main") + ";MODE=PostgreSQL";
    try {
      Flyway.configure()
          .dataSource(url, "sa", "")
          .locations("classpath:db/migration")
          .target("12")
          .load()
          .migrate();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        sql.executeUpdate(
            "INSERT INTO backend_channel_bindings VALUES('legacy-user','telegram','77','active',CURRENT_TIMESTAMP)");
        sql.executeUpdate(
            "INSERT INTO backend_channel_links VALUES('legacy-link','legacy-user','retained-hash',CURRENT_TIMESTAMP,FALSE)");
        sql.executeUpdate(
            "INSERT INTO backend_telegram_updates VALUES(900,'legacy-user','processed',CURRENT_TIMESTAMP)");
        sql.executeUpdate(
            "INSERT INTO backend_notifications(id,user_id,delivery_key,kind,content_cipher,state,next_attempt_at,created_at) VALUES('legacy-notice','legacy-user','old-key','test','retained-opaque-cipher','pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      }
      var flyway =
          Flyway.configure()
              .dataSource(url, "sa", "")
              .locations("classpath:db/migration")
              .target("14")
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
      flyway.validate();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      try (var db = DriverManager.getConnection(url, "sa", "");
          var sql = db.createStatement()) {
        try (var row =
            sql.executeQuery(
                "SELECT session_id FROM backend_channel_bindings WHERE external_id='77'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("shared");
        }
        try (var row =
            sql.executeQuery(
                "SELECT session_id,consumed FROM backend_channel_links WHERE id='legacy-link'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("shared");
          assertThat(row.getBoolean(2)).isFalse();
        }
        try (var row =
            sql.executeQuery(
                "SELECT state,input_hash,result_cipher FROM backend_telegram_updates WHERE update_id=900")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("processed");
          assertThat(row.getString(2)).isNull();
          assertThat(row.getString(3)).isNull();
        }
        try (var row =
            sql.executeQuery(
                "SELECT content_cipher,state FROM backend_notifications WHERE id='legacy-notice'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("retained-opaque-cipher");
          assertThat(row.getString(2)).isEqualTo("pending");
        }
        try (var row =
            sql.executeQuery(
                "SELECT generation FROM backend_channel_bindings WHERE external_id='77'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getLong(1)).isEqualTo(1L);
        }
        try (var row = sql.executeQuery("SELECT COUNT(*) FROM backend_channel_deliveries")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getInt(1)).isZero();
        }
      }
    } finally {
      try (var files = Files.walk(temp)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void appMigrationFifteenAddsIsolatedTablesAndLeavesLegacyBindingMode() throws Exception {
    String url = "jdbc:h2:mem:app_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    var old =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .target("14")
            .load();
    old.migrate();
    try (var db = DriverManager.getConnection(url, "sa", "");
        var sql = db.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO backend_channel_bindings(user_id,channel,external_id,state,created_at,session_id,generation) VALUES('legacy','telegram','77','active',CURRENT_TIMESTAMP,'shared',5)");
      var latest =
          Flyway.configure()
              .dataSource(url, "sa", "")
              .locations("classpath:db/migration")
              // This case verifies the V14 -> V15 boundary, not future authentication migrations.
              .target("15")
              .load();
      assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
      latest.validate();
      assertThat(latest.migrate().migrationsExecuted).isZero();
      try (var row =
          sql.executeQuery(
              "SELECT generation,app_mode FROM backend_channel_bindings WHERE user_id='legacy'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isEqualTo(5);
        assertThat(row.getBoolean(2)).isFalse();
      }
      sql.execute("DROP ALL OBJECTS");
    }
  }
}
