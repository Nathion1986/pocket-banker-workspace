package com.pocketbanker.actions.workflow;

import static com.pocketbanker.adapters.nativebank.NativeHttpClient.*;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.accounts.BoundTransactionReader;
import com.pocketbanker.adapters.nativebank.*;
import com.pocketbanker.contracts.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FinancialActions {
  private final BankAdapters banks;
  private final NativeHttpClient http;
  private final BankBindingService bindings;
  private final ActionCatalog catalog;
  private final SystemActionPort system;
  private final BoundTransactionReader statements;
  private final com.pocketbanker.profile.ProfileService profile;
  private final com.pocketbanker.data.BackendStore db;

  @Value("${backend.flower-url:http://127.0.0.1:8004}")
  private String flower;

  @Value("${backend.cake-url:http://127.0.0.1:8005}")
  private String cake;

  private static final Map<String, String> VERBS =
      Map.ofEntries(
          Map.entry("freeze_card", "freeze"),
          Map.entry("unfreeze_card", "unfreeze"),
          Map.entry("wealth_purchase", "purchase"),
          Map.entry("wealth_redeem", "redeem"),
          Map.entry("release_budget", "release"));

  public Map<String, Object> execute(
      String user, String key, String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    spec.validate(input, false);
    var result = perform(user, key, action, input);
    validateOutcome(key, spec, input, result);
    return result;
  }

  private Map<String, Object> perform(
      String user, String key, String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    if (action.equals("cancel_flower") || action.equals("cancel_cake")) {
      var cancelled =
          object(
              http.send(
                  spec.provider().equals("flower") ? flower : cake,
                  "/merchant/v1/outcomes/"
                      + quote(input.get("originalOperation").toString())
                      + "/cancel",
                  "POST",
                  Map.of("userId", user)));
      if (!"CANCELLED".equals(cancelled.get("status")))
        throw new BackendException(409, "merchant_cancellation_not_verified");
      Map<String, Object> result = new LinkedHashMap<>(cancelled);
      result.put("originalOrderStatus", "CANCELLED");
      result.put("status", "SUCCEEDED");
      result.put("operationId", key);
      return result;
    }
    if (Set.of("system", "sandbox").contains(spec.provider()))
      return normalize(key, system.execute(user, key, action, input));
    if (action.equals("query_balance")) return normalize(key, balance(user, input));
    if (action.equals("query_transactions")) return normalize(key, transactionResult(user, input));
    if (action.equals("card_query")) {
      List<Map<String, Object>> cards = new ArrayList<>();
      for (var b : active(user, input))
        cards.addAll(banks.require(b.get("bank").toString()).cards(b.get("account_id").toString()));
      return normalize(key, Map.of("status", "SUCCEEDED", "cards", cards));
    }
    if (action.equals("wealth_query")) {
      List<Map<String, Object>> output = new ArrayList<>();
      for (var binding : active(user, input)) {
        String bank = String.valueOf(binding.get("bank")),
            account = String.valueOf(binding.get("account_id"));
        int maxRisk = Integer.parseInt(String.valueOf(input.getOrDefault("maxRisk", 5))),
            settle = Integer.parseInt(String.valueOf(input.getOrDefault("settleDays", 365)));
        bindings.account(user, bank, account);
        var preference = profile.value(user, "risk_level");
        if (preference.isPresent())
          maxRisk = Math.min(maxRisk, Integer.parseInt(preference.get().toString().substring(1)));
        final int allowedRisk = maxRisk;
        var products =
            banks.require(bank).products().stream()
                .filter(
                    p ->
                        Integer.parseInt(p.get("risk").toString()) <= allowedRisk
                            && Integer.parseInt(p.get("settleDays").toString()) <= settle)
                .toList();
        output.add(
            Map.of(
                "bank",
                bank,
                "products",
                products,
                "holdings",
                banks.require(bank).holdings(account)));
      }
      return normalize(key, Map.of("status", "SUCCEEDED", "banks", output));
    }
    String bank = String.valueOf(input.get("bank"));
    String account = bindings.requireAccount(user, bank, input.get("accountId"));
    // Recheck live binding/ownership and balance immediately before calling the native action.
    bindings.requireScope(user, bank, account, "write");
    var live = bindings.account(user, bank, account);
    if (spec.countsDaily() && input.containsKey("amount") && spec.provider().equals("bank")) {
      if (Boolean.TRUE.equals(live.get("frozen")))
        throw new BackendException(409, "account_frozen");
      if (Money.positive(input.get("amount"))
              .compareTo(new BigDecimal(live.get("available").toString()))
          > 0) throw new BackendException(409, "insufficient_available");
    }
    Map<String, Object> params = new LinkedHashMap<>(input);
    params.put("accountId", account);
    Map<String, Object> result;
    if (spec.provider().equals("flower") || spec.provider().equals("cake"))
      result =
          object(
              http.send(
                  spec.provider().equals("flower") ? flower : cake,
                  "/merchant/v1/orders",
                  "POST",
                  Map.of("idempotencyKey", key, "userId", user, "params", params)));
    else
      result =
          banks
              .require(bank)
              .execute(key, user, VERBS.getOrDefault(action, action), account, params);
    readBack(user, bank, account, action, input, result);
    return result;
  }

  public Map<String, Object> outcome(
      String user, String key, String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    if (Set.of("cancel_flower", "cancel_cake").contains(action)) {
      var original =
          object(
              http.send(
                  spec.provider().equals("flower") ? flower : cake,
                  "/merchant/v1/outcomes/"
                      + quote(input.get("originalOperation").toString())
                      + "?owner="
                      + quote(user),
                  "GET",
                  null));
      if (!"CANCELLED".equals(original.get("status"))) return execute(user, key, action, input);
      var result = new LinkedHashMap<>(original);
      result.put("operationId", key);
      result.put("status", "SUCCEEDED");
      result.put("originalOrderStatus", "CANCELLED");
      validateOutcome(key, spec, input, result);
      return result;
    }
    if (spec.provider().equals("system")
        || spec.provider().equals("sandbox")
        || action.startsWith("query_")
        || action.equals("wealth_query")
        || action.equals("card_query")) return execute(user, key, action, input);
    Map<String, Object> result;
    if (spec.provider().equals("flower") || spec.provider().equals("cake"))
      result =
          object(
              http.send(
                  spec.provider().equals("flower") ? flower : cake,
                  "/merchant/v1/outcomes/" + quote(key) + "?owner=" + quote(user),
                  "GET",
                  null));
    else {
      bindings.requireAccount(user, String.valueOf(input.get("bank")), input.get("accountId"));
      result = banks.require(input.get("bank").toString()).outcome(key, user);
    }
    validateOutcome(key, spec, input, result);
    if (spec.provider().equals("bank"))
      readBack(
          user,
          input.get("bank").toString(),
          bindings.requireAccount(user, input.get("bank").toString(), input.get("accountId")),
          action,
          input,
          result);
    return result;
  }

  public boolean supportsCompensation(String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    return spec.reversible()
        && (!spec.provider().equals("bank")
            || banks.require(input.get("bank").toString()).supportsCompensation(action));
  }

  public Map<String, Object> compensationOutcome(
      String user, String key, String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    if (spec.provider().equals("system")) return system.compensate(user, key, action, input);
    if (Set.of("flower", "cake").contains(spec.provider()))
      return object(
          http.send(
              spec.provider().equals("flower") ? flower : cake,
              "/merchant/v1/outcomes/" + quote(key) + "?owner=" + quote(user),
              "GET",
              null));
    return banks.require(input.get("bank").toString()).outcome(key, user);
  }

  public Map<String, Object> compensate(
      String user, String key, String action, Map<String, Object> input) {
    var spec = catalog.require(action);
    if (!supportsCompensation(action, input))
      throw new BackendException(409, "action_not_reversible");
    if (spec.provider().equals("system")) return system.compensate(user, key, action, input);
    if (spec.provider().equals("flower") || spec.provider().equals("cake"))
      return object(
          http.send(
              spec.provider().equals("flower") ? flower : cake,
              "/merchant/v1/outcomes/" + quote(key) + "/cancel",
              "POST",
              Map.of("userId", user)));
    return banks.require(input.get("bank").toString()).compensate(key, user);
  }

  public Map<String, Object> balance(String user, Map<String, Object> input) {
    List<Map<String, Object>> accounts = new ArrayList<>(), errors = new ArrayList<>();
    BigDecimal available = BigDecimal.ZERO;
    for (var binding : active(user, input))
      try {
        var account =
            bindings.account(user, binding.get("bank").toString(), binding.get("account_id"));
        accounts.add(account);
        available = available.add(new BigDecimal(account.get("available").toString()));
      } catch (BackendException e) {
        errors.add(Map.of("bank", binding.get("bank"), "error", e.getMessage()));
      }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "SUCCEEDED");
    out.put("accounts", accounts);
    out.put("complete", errors.isEmpty());
    out.put("errors", errors);
    out.put("knownAvailable", available.toPlainString());
    if (errors.isEmpty()) out.put("totalAvailable", available.toPlainString());
    return out;
  }

  public List<Map<String, Object>> transactions(String user, Map<String, Object> input) {
    return statements.read(user, input);
  }

  private Map<String, Object> transactionResult(String user, Map<String, Object> input) {
    var all = transactions(user, input);
    if (input.containsKey("month") || input.containsKey("year")) {
      int month =
          ((Number)
                  input.getOrDefault(
                      "month",
                      db.now()
                          .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                          .toLocalDate()
                          .getMonthValue()))
              .intValue();
      int year =
          ((Number)
                  input.getOrDefault(
                      "year",
                      db.now()
                          .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                          .toLocalDate()
                          .getYear()))
              .intValue();
      if (month < 1 || month > 12 || year < 2000 || year > 2100)
        throw new BackendException(400, "invalid_bill_period");
      all =
          all.stream()
              .filter(
                  r -> {
                    var t =
                        java.time.OffsetDateTime.parse(r.get("occurredAt").toString())
                            .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"));
                    return t.getMonthValue() == month && t.getYear() == year;
                  })
              .toList();
    }
    int limit = ((Number) input.getOrDefault("limit", 1000000)).intValue();
    var rows = all.stream().limit(limit).toList();
    return Map.of(
        "status",
        "SUCCEEDED",
        "transactions",
        rows,
        "returned",
        rows.size(),
        "complete",
        rows.size() == all.size());
  }

  private void readBack(
      String user,
      String bank,
      String account,
      String action,
      Map<String, Object> input,
      Map<String, Object> result) {
    if (!"SUCCEEDED".equals(result.get("status"))) return;
    if (Set.of("freeze_card", "unfreeze_card", "stop_payment").contains(action)) {
      boolean expected = !action.equals("unfreeze_card");
      if (!Boolean.valueOf(expected).equals(bindings.account(user, bank, account).get("frozen")))
        throw new BackendException(502, "account_protection_not_verified");
    }
    if (Set.of("release", "release_budget", "reserve").contains(action)) {
      String id = Objects.toString(result.get("reservationId"), "");
      var actual = banks.require(bank).reservation(id, user);
      if (action.equals("reserve")) {
        if (!"active".equals(actual.get("status"))
            || Money.positive(input.get("amount"))
                    .compareTo(new BigDecimal(actual.get("amount").toString()))
                != 0) throw new BackendException(502, "reservation_not_verified");
      } else if (!"released".equals(actual.get("status"))
          || new BigDecimal(actual.get("remaining").toString()).signum() != 0)
        throw new BackendException(502, "release_not_verified");
      bindings.account(user, bank, account);
    }
    if (action.equals("cancel_subscription")
        && banks.require(bank).subscriptions(account).stream()
            .noneMatch(
                r ->
                    input.get("subscriptionId").equals(r.get("id"))
                        && "cancelled".equals(r.get("status"))))
      throw new BackendException(502, "subscription_cancellation_not_verified");
    if (action.equals("credit_repay")) {
      var bill =
          banks.require(bank).creditBills(account).stream()
              .filter(row -> input.get("billId").equals(row.get("billId")))
              .findFirst()
              .orElseThrow(() -> new BackendException(502, "repayment_not_verified"));
      BigDecimal before = new BigDecimal(result.get("outstandingBefore").toString());
      BigDecimal after = new BigDecimal(result.get("outstandingAfter").toString());
      if (!input.get("billId").equals(result.get("billId"))
          || !account.equals(result.get("fromAccountId"))
          || before.subtract(after).compareTo(Money.positive(input.get("amount"))) != 0
          || new BigDecimal(bill.get("outstanding").toString()).compareTo(after) > 0)
        throw new BackendException(502, "repayment_not_verified");
    }
    if (Set.of("apply_card", "virtual_card", "card_limits").contains(action)) {
      var actual =
          banks.require(bank).cards(account).stream()
              .filter(c -> result.get("cardId").equals(c.get("cardId")))
              .findFirst()
              .orElseThrow(() -> new BackendException(502, "card_not_verified"));
      if (new BigDecimal(actual.get("limit").toString())
              .compareTo(Money.positive(input.get("limit")))
          != 0) throw new BackendException(502, "card_limit_not_verified");
    }
  }

  private List<Map<String, Object>> active(String user, Map<String, Object> input) {
    List<Map<String, Object>> rows =
        bindings.list(user).stream()
            .filter(
                r ->
                    "active".equals(r.get("state"))
                        && (!input.containsKey("bank") || input.get("bank").equals(r.get("bank"))))
            .toList();
    if (rows.isEmpty()) throw new BackendException(409, "bank_binding_required");
    if (input.containsKey("accountId")) {
      var selected =
          rows.stream().filter(r -> input.get("accountId").equals(r.get("account_id"))).toList();
      if (selected.isEmpty()) throw new BackendException(403, "account_not_bound");
      return selected;
    }
    return rows;
  }

  private Map<String, Object> normalize(String key, Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>(source);
    result.put("operationId", key);
    result.putIfAbsent("status", "SUCCEEDED");
    return result;
  }

  private void validateOutcome(
      String key, ActionSpec spec, Map<String, Object> input, Map<String, Object> result) {
    spec.validateOutput(result);
    if (!key.equals(result.get("operationId"))
        || !Set.of("SUCCEEDED", "PENDING", "CANCELLED").contains(result.get("status")))
      throw new BackendException(502, "bank_result_contract_violation");
    if (spec.involvesMoney()
        && input.containsKey("amount")
        && (!"CNY".equals(result.get("currency"))
            || result.get("amount") == null
            || Money.positive(input.get("amount")).compareTo(Money.positive(result.get("amount")))
                != 0)) throw new BackendException(502, "bank_result_amount_mismatch");
    if (spec.id().equals("transfer")
        && (!input.get("toBank").equals(result.get("toBank"))
            || !input.get("toAccountId").equals(result.get("toAccountId"))))
      throw new BackendException(502, "bank_payee_mismatch");
    if (input.containsKey("productId") && !input.get("productId").equals(result.get("productId")))
      throw new BackendException(502, "bank_product_mismatch");
    if (input.containsKey("cardId") && !input.get("cardId").equals(result.get("cardId")))
      throw new BackendException(502, "bank_card_mismatch");
  }
}
