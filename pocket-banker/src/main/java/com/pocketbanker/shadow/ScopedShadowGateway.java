package com.pocketbanker.shadow;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.VaultCodec;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScopedShadowGateway {
  public record Prompt(String user, String session, Map<String, Object> features) {
    public Prompt {
      features = freeze(features);
    }
  }

  private static Map<String, Object> freeze(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((k, v) -> copy.put(k, immutable(v)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutable(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((k, v) -> result.put(k.toString(), immutable(v)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list)
      return list.stream().map(ScopedShadowGateway::immutable).toList();
    return value;
  }

  private static final Set<String> FIELDS =
      Set.of(
          "nodes",
          "params",
          "actionId",
          "intent",
          "bank",
          "toBank",
          "risk",
          "kind",
          "type",
          "currency",
          "status",
          "mode",
          "schema",
          "decision",
          "consensus",
          "nodeId",
          "amount",
          "balance",
          "available",
          "dueDate",
          "dueAt",
          "date",
          "toName",
          "name",
          "accountId",
          "toAccountId",
          "memo",
          "productId",
          "purpose",
          "reservationId",
          "dailyLimit",
          "limit",
          "fields",
          "datasetId",
          "code",
          "injectionDetected",
          "instructionOverride",
          "permissionBypass",
          "exfiltration",
          "template",
          "placeholders",
          "reviewBasis",
          "relations",
          "income",
          "expense",
          "totalAvailable",
          "totalBalance",
          "fee",
          "createdAt",
          "occurredAt",
          "birthday");
  private static final Set<String> ENUMS =
      Set.of(
          "A",
          "B",
          "C",
          "CNY",
          "USD",
          "HKD",
          "EUR",
          "GBP",
          "F",
          "transaction_debit",
          "transaction_credit",
          "tuition_due",
          "payment_due",
          "risk_transaction",
          "life_event",
          "flight_trip",
          "calendar_trip",
          "calendar_payment",
          "calendar_birthday",
          "coverage_hint",
          "home_city_change",
          "monthly_income",
          "monthly_expense",
          "prediction",
          "salary_gather",
          "split_payment",
          "channel_optimization",
          "repayment",
          "birthday_funding",
          "GREEN",
          "YELLOW",
          "RED",
          "plan",
          "candidate",
          "structured",
          "unsafe",
          "safe",
          "immediate",
          "at",
          "recurring",
          "event",
          "confirm",
          "reject",
          "abstain",
          "SUCCEEDED",
          "PENDING",
          "CANCELLED",
          "mock",
          "string",
          "number",
          "object",
          "array",
          "boolean",
          "integer",
          "transactions",
          "signal",
          "calendar",
          "calendar_travel",
          "tuition",
          "birthday",
          "finance",
          "life",
          "city",
          "travel",
          "coverage",
          "instruction",
          "schema_only");
  private final BackendStore db;
  private final com.pocketbanker.contracts.ActionCatalog catalog;
  private final VaultCodec vault;
  private final SecureRandom random = new SecureRandom();

  @Transactional
  public Prompt mask(String user, String session, Map<String, Object> input) {
    if (session == null || !session.matches("[A-Za-z0-9_-]{1,100}"))
      throw new BackendException(400, "invalid_shadow_session");
    db.lockUser(user);
    var found =
        db.one("SELECT * FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session);
    if (found.isPresent() && !time(found.get(), "expires_at").isAfter(db.now())) {
      erase(user, session);
      found = Optional.empty();
    }
    if (found.isEmpty()) {
      db.update(
          "INSERT INTO backend_shadow_sessions VALUES(?,?,?,?,?)",
          session,
          user,
          2 + random.nextInt(97),
          7 * (random.nextBoolean() ? 1 + random.nextInt(52) : -1 - random.nextInt(52)),
          db.now().plusSeconds(1800));
      found =
          db.one("SELECT * FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : input.entrySet())
      result.put(
          key(user, session, entry.getKey()),
          transform(
              user,
              session,
              entry.getKey(),
              entry.getValue(),
              (int) number(found.get(), "factor"),
              (int) number(found.get(), "date_shift")));
    return new Prompt(user, session, result);
  }

  private Object transform(
      String user, String session, String field, Object value, int factor, int shift) {
    if (value == null) return "null";
    if (field.equals("schema")
        && (analysisSchema("transactions").equals(value)
            || analysisSchema("confirmed_events").equals(value))) return immutable(value);
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach(
          (k, v) ->
              out.put(
                  key(user, session, k.toString()),
                  transform(user, session, k.toString(), v, factor, shift)));
      return out;
    }
    if (value instanceof List<?> list)
      return list.stream().map(v -> transform(user, session, field, v, factor, shift)).toList();
    String text = value.toString();
    if (field.toLowerCase(Locale.ROOT).contains("amount")
        || field.equals("balance")
        || field.equals("available")) {
      try {
        String transformed =
            new BigDecimal(text).multiply(BigDecimal.valueOf(factor)).toPlainString();
        remember(
            user,
            session,
            new BigDecimal(transformed).stripTrailingZeros().toPlainString(),
            text,
            "amount_value");
        return transformed;
      } catch (Exception e) {
        return token(user, session, text, "number");
      }
    }
    if (field.toLowerCase(Locale.ROOT).contains("date")
        || Set.of("dueAt", "createdAt", "occurredAt", "birthday").contains(field)) {
      try {
        String transformed =
            text.contains("T")
                ? Instant.parse(text).plusSeconds(shift * 86400L).toString()
                : LocalDate.parse(text).plusDays(shift).toString();
        remember(user, session, transformed, text, "date_value");
        return transformed;
      } catch (Exception e) {
        return token(user, session, text, "date");
      }
    }
    if (Set.of("intent", "actionId").contains(field)
        && catalog.list().stream().anyMatch(a -> a.id().equals(text))) return text;
    if (Set.of(
                "bank",
                "toBank",
                "risk",
                "kind",
                "type",
                "currency",
                "status",
                "mode",
                "schema",
                "decision",
                "consensus")
            .contains(field)
        && ENUMS.contains(text)) return text;
    if (value instanceof Boolean) return value;
    // Every free-form string (including text/name/account) is replaced, never passed through.
    return token(user, session, text, "identity");
  }

  private String token(String user, String session, String value, String kind) {
    for (var row :
        db.rows(
            "SELECT * FROM backend_shadow_maps WHERE user_id=? AND session_id=?", user, session))
      if (kind.equals(text(row, "kind"))
          && value.equals(vault.open(user, text(row, "value_cipher"), String.class)))
        return text(row, "token");
    String token = "S_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    db.update(
        "INSERT INTO backend_shadow_maps VALUES(?,?,?,?,?)",
        user,
        session,
        token,
        vault.seal(user, value),
        kind);
    return token;
  }

  private String key(String user, String session, String key) {
    return FIELDS.contains(key) ? key : token(user, session, key, "field");
  }

  public Map<String, Object> analysisSchema(String dataset) {
    Map<String, Object> fields =
        switch (dataset) {
          case "transactions" -> Map.of(
              "id",
              "string",
              "accountId",
              "string",
              "amount",
              "decimal-string",
              "currency",
              "string",
              "merchant",
              "string",
              "category",
              "string",
              "city",
              "string",
              "occurredAt",
              "timestamp",
              "bank",
              "string");
          case "confirmed_events" -> Map.of(
              "event_type",
              "string",
              "kind",
              "string",
              "amount",
              "decimal-string",
              "due_date",
              "date",
              "status",
              "string");
          default -> throw new BackendException(400, "dataset_not_allowed");
        };
    return Map.of("fields", fields, "readOnly", true);
  }

  private void remember(String user, String session, String masked, String original, String kind) {
    String id =
        "V_"
            + com.pocketbanker.security.PerceptionAccess.hash(kind + ":" + masked).substring(0, 32);
    if (db.one(
            "SELECT token FROM backend_shadow_maps WHERE user_id=? AND session_id=? AND token=?",
            user,
            session,
            id)
        .isEmpty())
      db.update(
          "INSERT INTO backend_shadow_maps VALUES(?,?,?,?,?)",
          user,
          session,
          id,
          vault.seal(user, original),
          kind);
  }

  private String known(String user, String session, String masked, String kind) {
    String id =
        "V_"
            + com.pocketbanker.security.PerceptionAccess.hash(kind + ":" + masked).substring(0, 32);
    var row =
        db.one(
                "SELECT value_cipher FROM backend_shadow_maps WHERE user_id=? AND session_id=? AND token=? AND kind=?",
                user,
                session,
                id,
                kind)
            .orElseThrow(
                () ->
                    new BackendException(
                        502,
                        kind.equals("amount_value")
                            ? "unknown_shadow_amount"
                            : "unknown_shadow_date"));
    return vault.open(user, text(row, "value_cipher"), String.class);
  }

  public String restore(String user, String session, String text) {
    var state =
        db.one("SELECT * FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session)
            .orElseThrow(() -> new BackendException(409, "shadow_session_unavailable"));
    if (!time(state, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "shadow_session_expired");
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("S_[A-Za-z0-9_]+").matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      var row =
          db.one(
                  "SELECT value_cipher FROM backend_shadow_maps WHERE user_id=? AND session_id=? AND token=?",
                  user,
                  session,
                  matcher.group())
              .orElseThrow(() -> new BackendException(502, "unknown_shadow_token"));
      matcher.appendReplacement(
          out,
          java.util.regex.Matcher.quoteReplacement(
              vault.open(user, BackendStore.text(row, "value_cipher"), String.class)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public BigDecimal restoreAmount(String user, String session, Object amount) {
    var row =
        db.one("SELECT * FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session)
            .orElseThrow(() -> new BackendException(409, "shadow_session_unavailable"));
    if (!time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "shadow_session_expired");
    try {
      String canonical = new BigDecimal(amount.toString()).stripTrailingZeros().toPlainString();
      return new BigDecimal(known(user, session, canonical, "amount_value"));
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      throw new BackendException(502, "shadow_amount_not_exact");
    }
  }

  public String restoreDate(String user, String session, String date) {
    var row =
        db.one("SELECT * FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session)
            .orElseThrow(() -> new BackendException(409, "shadow_session_unavailable"));
    if (!time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "shadow_session_expired");
    try {
      if (date.contains("T")) Instant.parse(date);
      else LocalDate.parse(date);
      return known(user, session, date, "date_value");
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      throw new BackendException(502, "invalid_shadow_date");
    }
  }

  public String render(String template, Map<String, Object> trusted) {
    if (template == null
        || template.matches("(?s).*\\d.*")
        || template.matches("(?s).*[零一二三四五六七八九十百千万亿两]+[元块角分].*"))
      throw new BackendException(502, "model_literal_number_forbidden");
    var matcher = java.util.regex.Pattern.compile("\\{\\{([A-Za-z_]+)\\}\\}").matcher(template);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      Object value = trusted.get(matcher.group(1));
      if (value == null) throw new BackendException(502, "unknown_reply_placeholder");
      matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value.toString()));
    }
    matcher.appendTail(result);
    if (result.toString().contains("{{") || result.toString().contains("}}"))
      throw new BackendException(502, "invalid_reply_placeholder");
    return result.toString();
  }

  @Transactional
  public void erase(String user, String session) {
    db.update("DELETE FROM backend_shadow_maps WHERE user_id=? AND session_id=?", user, session);
    db.update("DELETE FROM backend_shadow_sessions WHERE user_id=? AND id=?", user, session);
  }

  @Transactional
  public int expire() {
    int n =
        db.update(
            "DELETE FROM backend_shadow_maps WHERE EXISTS(SELECT 1 FROM backend_shadow_sessions s WHERE s.user_id=backend_shadow_maps.user_id AND s.id=backend_shadow_maps.session_id AND s.expires_at<=?)",
            db.now());
    db.update("DELETE FROM backend_shadow_sessions WHERE expires_at<=?", db.now());
    return n;
  }
}
