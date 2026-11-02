package com.pocketbanker.security;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BackendPermissionService {
  private final BackendStore db;
  private final VaultCodec vault;
  private final BackendAudit audit;
  private final com.pocketbanker.accounts.BankBindingService bindings;
  private static final BigDecimal YELLOW_LIMIT = new BigDecimal("1000.00");

  public String risk(
      String user,
      ActionSpec spec,
      Map<String, Object> input,
      boolean degraded,
      String excludeOperation) {
    String risk = spec.risk();
    if (spec.involvesMoney() && spec.countsDaily()) {
      BigDecimal amount =
          input.containsKey("amount") && !input.get("amount").toString().startsWith("$")
              ? Money.positive(input.get("amount"))
              : BigDecimal.ZERO;
      BigDecimal total = daily(user, excludeOperation).add(cost(user, spec, input, amount));
      if (total.compareTo(YELLOW_LIMIT) > 0) risk = "RED";
      if (spec.id().equals("transfer")
          && amount.compareTo(new BigDecimal("300")) >= 0
          && !familiar(user, input)) risk = "RED";
      int smallAttempts = 0;
      BigDecimal attempted = amount;
      for (var node :
          db.rows(
              "SELECT n.input_cipher,n.operation_key FROM backend_nodes n JOIN backend_tasks t ON n.task_id=t.id WHERE t.user_id=? AND n.action_id='transfer' AND t.created_at>? AND n.operation_key<>?",
              user,
              db.now().minusSeconds(60),
              excludeOperation == null ? "" : excludeOperation)) {
        Object raw = vault.map(user, text(node, "input_cipher")).get("amount");
        if (raw == null || raw.toString().startsWith("$")) continue;
        BigDecimal prior = Money.positive(raw);
        if (prior.compareTo(new BigDecimal("300")) <= 0) {
          smallAttempts++;
          attempted = attempted.add(prior);
        }
      }
      if (smallAttempts >= 3 && attempted.compareTo(new BigDecimal("750")) >= 0) risk = "RED";
    }
    if (spec.involvesMoney()) {
      var rate =
          db.one(
                  "SELECT COUNT(*) AS count FROM backend_tasks WHERE user_id=? AND created_at>?",
                  user,
                  db.now().minusSeconds(60))
              .orElseThrow();
      if (number(rate, "count") >= 10) risk = "RED";
    }
    if (degraded) risk = risk.equals("GREEN") ? "YELLOW" : "RED";
    return risk;
  }

  public BigDecimal daily(String user, String exclude) {
    String day = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
    var row =
        db.one(
                "SELECT COALESCE(SUM(amount),0) AS total FROM quota_reservations WHERE user_id=? AND (state='reserved' OR (business_date=? AND state='spent')) AND operation_key<>?",
                user,
                day,
                exclude == null ? "" : exclude)
            .orElseThrow();
    return new BigDecimal(row.get("total").toString());
  }

  public boolean familiar(String user, Map<String, Object> input) {
    if (!input.containsKey("toAccountId")) return false;
    String account = input.get("toAccountId").toString(),
        bank = String.valueOf(input.get("toBank"));
    var stats =
        db.one(
            "SELECT count FROM backend_payee_stats WHERE user_id=? AND bank=? AND account_hash=?",
            user,
            bank,
            PerceptionAccess.hash(account));
    if (stats.isPresent() && number(stats.get(), "count") >= 3) return true;
    return db
        .rows(
            "SELECT account_cipher FROM backend_persons WHERE user_id=? AND bank=? AND confirmed=TRUE AND account_cipher IS NOT NULL",
            user,
            bank)
        .stream()
        .anyMatch(p -> account.equals(vault.open(user, text(p, "account_cipher"), String.class)));
  }

  @org.springframework.transaction.annotation.Transactional
  public void requireUnlocked(String user) {
    var state = db.lockUser(user);
    Instant until = time(state, "locked_until");
    if (until != null && !until.isAfter(db.now())) {
      db.update("UPDATE backend_users SET failures=0,locked_until=NULL WHERE user_id=?", user);
      audit.write(user, null, null, "circuit_recovered", Map.of("reason", "cooldown_elapsed"));
    }
    if (until != null && until.isAfter(db.now()))
      throw new BackendException(423, "user_temporarily_locked");
  }

  @org.springframework.transaction.annotation.Transactional
  public void reserve(String user, String operation, ActionSpec spec, Map<String, Object> input) {
    if (!spec.countsDaily()) return;
    db.lockUser(user);
    var previous =
        db.one(
            "SELECT state FROM quota_reservations WHERE operation_key=? AND user_id=?",
            operation,
            user);
    if (previous.isPresent() && !"released".equals(text(previous.get(), "state"))) return;
    String day = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
    if (previous.isPresent()) {
      db.update(
          "UPDATE quota_reservations SET state='reserved',business_date=?,amount=?,created_at=? WHERE operation_key=? AND user_id=?",
          day,
          cost(user, spec, input, Money.positive(input.get("amount"))),
          db.now(),
          operation,
          user);
      return;
    }
    db.update(
        "INSERT INTO quota_reservations VALUES(?,?,?,?,?,?)",
        operation,
        user,
        day,
        cost(user, spec, input, Money.positive(input.get("amount"))),
        "reserved",
        db.now());
  }

  @org.springframework.transaction.annotation.Transactional
  public void settle(
      String user,
      String operation,
      ActionSpec spec,
      Map<String, Object> input,
      boolean succeeded) {
    db.lockUser(user);
    int changed =
        db.update(
            "UPDATE quota_reservations SET state=? WHERE operation_key=? AND user_id=? AND state='reserved'",
            succeeded ? "spent" : "released",
            operation,
            user);
    if (succeeded && changed > 0 && spec.id().equals("transfer")) {
      String bank = String.valueOf(input.get("toBank")),
          hash = PerceptionAccess.hash(input.get("toAccountId").toString());
      var prior =
          db.one(
              "SELECT * FROM backend_payee_stats WHERE user_id=? AND bank=? AND account_hash=?",
              user,
              bank,
              hash);
      if (prior.isPresent())
        db.update(
            "UPDATE backend_payee_stats SET count=count+1,amount=amount+?,last_at=? WHERE user_id=? AND bank=? AND account_hash=?",
            Money.positive(input.get("amount")),
            db.now(),
            user,
            bank,
            hash);
      else
        db.update(
            "INSERT INTO backend_payee_stats VALUES(?,?,?,?,?,?)",
            user,
            bank,
            hash,
            1,
            Money.positive(input.get("amount")),
            db.now());
    }
  }

  private BigDecimal cost(
      String user, ActionSpec spec, Map<String, Object> input, BigDecimal amount) {
    if (spec.id().equals("transfer") && input.containsKey("bank")) {
      var account = bindings.account(user, input.get("bank").toString(), input.get("accountId"));
      BigDecimal rate =
          new BigDecimal(String.valueOf(account.getOrDefault("transferFeeRate", "0")));
      return amount.add(amount.multiply(rate).setScale(2, java.math.RoundingMode.CEILING));
    }
    return amount;
  }

  public static int rank(String risk) {
    return switch (risk) {
      case "GREEN" -> 0;
      case "YELLOW" -> 1;
      case "RED" -> 2;
      default -> throw new BackendException(400, "invalid_risk");
    };
  }

  public static String max(String a, String b) {
    return rank(a) >= rank(b) ? a : b;
  }
}
