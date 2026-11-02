package com.pocketbanker.data;

import java.sql.Clob;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence only. SQL statements are supplied by internal services, never request strings. */
@Repository
@RequiredArgsConstructor
public class BackendStore {
  private final JdbcTemplate jdbc;
  private final Clock perceptionClock;

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, index) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          var meta = rs.getMetaData();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof Clob clob)
              value = clob.getSubString(1, Math.toIntExact(clob.length()));
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
          }
          return row;
        },
        args);
  }

  public Optional<Map<String, Object>> one(String sql, Object... args) {
    var list = rows(sql, args);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public Instant now() {
    return perceptionClock.instant();
  }

  public void ensureUser(String user) {
    update(
        "INSERT INTO backend_users(user_id,failures,updated_at) VALUES(?,0,?) ON CONFLICT DO NOTHING",
        user,
        now());
  }

  public Map<String, Object> lockUser(String user) {
    ensureUser(user);
    return one("SELECT * FROM backend_users WHERE user_id=? FOR UPDATE", user).orElseThrow();
  }

  public static String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value == null) return null;
    if (value instanceof Clob clob) {
      try {
        return clob.getSubString(1, Math.toIntExact(clob.length()));
      } catch (Exception e) {
        throw new IllegalStateException("invalid_database_text");
      }
    }
    return String.valueOf(value);
  }

  public static Instant time(Map<String, Object> row, String key) {
    Object v = row.get(key);
    if (v == null) return null;
    if (v instanceof OffsetDateTime d) return d.toInstant();
    if (v instanceof java.sql.Timestamp t) return t.toInstant();
    if (v instanceof Instant i) return i;
    throw new IllegalStateException("invalid_database_time");
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }
}
