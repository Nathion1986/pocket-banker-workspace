package com.pocketbanker.adapters.nativebank;

import static com.pocketbanker.adapters.nativebank.NativeHttpClient.*;

import com.pocketbanker.contracts.*;
import java.util.*;

public class HttpBankAdapter implements BankAdapter {
  private final String id, base, prefix;
  private final NativeHttpClient http;

  public HttpBankAdapter(String id, String base, NativeHttpClient http) {
    this.id = id;
    this.base = base;
    this.http = http;
    prefix = id.equals("A") ? "/api/v2" : id.equals("B") ? "/v2" : "/v1";
  }

  @Override
  public String id() {
    return id;
  }

  private Object read(String path) {
    return unwrap(http.send(base, prefix + path, "GET", null));
  }

  private Object unwrap(Object value) {
    return id.equals("A")
        ? object(value).get("data")
        : id.equals("C") ? object(object(value).get("data")).get("attributes") : value;
  }

  @Override
  public List<Map<String, Object>> accounts(String user) {
    var rows = list(read("/customers/" + quote(user) + "/accounts"));
    rows.forEach(this::validateAccount);
    if (rows.stream().anyMatch(r -> !user.equals(r.get("owner"))))
      throw new BackendException(502, "bank_account_owner_mismatch");
    return rows;
  }

  @Override
  public Map<String, Object> account(String account) {
    var result = object(read("/accounts/" + quote(account)));
    validateAccount(result);
    if (!account.equals(result.get("id")))
      throw new BackendException(502, "bank_account_id_mismatch");
    return result;
  }

  @Override
  public List<Map<String, Object>> transactions(String account, int offset, int limit) {
    var rows =
        list(
            read(
                "/accounts/"
                    + quote(account)
                    + "/transactions?offset="
                    + offset
                    + "&limit="
                    + limit));
    rows.forEach(
        r -> {
          validate(r, ContractSchemas.transaction());
          if (!account.equals(r.get("accountId")))
            throw new BackendException(502, "bank_transaction_account_mismatch");
        });
    return rows;
  }

  @Override
  public List<Map<String, Object>> products() {
    var rows = list(read("/products"));
    rows.forEach(r -> validate(r, ContractSchemas.product()));
    return rows;
  }

  @Override
  public List<Map<String, Object>> holdings(String account) {
    var rows = list(read("/accounts/" + quote(account) + "/holdings"));
    rows.forEach(
        r -> {
          validate(r, ContractSchemas.holding());
          if (!account.equals(r.get("accountId")))
            throw new BackendException(502, "bank_holding_account_mismatch");
        });
    return rows;
  }

  @Override
  public List<Map<String, Object>> subscriptions(String account) {
    var rows = list(read("/accounts/" + quote(account) + "/subscriptions"));
    rows.forEach(r -> validate(r, ContractSchemas.subscription()));
    return rows;
  }

  private void validate(Map<String, Object> value, Map<String, Object> schema) {
    SchemaValidator.validate(value, schema, 502, "invalid_bank_schema");
  }

  private void validateAccount(Map<String, Object> value) {
    validate(value, ContractSchemas.account());
    if (!id.equals(value.get("bank"))) throw new BackendException(502, "bank_id_mismatch");
    var balance = new java.math.BigDecimal(value.get("balance").toString());
    var reserved = new java.math.BigDecimal(value.get("reserved").toString());
    var available = new java.math.BigDecimal(value.get("available").toString());
    if (reserved.signum() < 0
        || available.signum() < 0
        || balance.subtract(reserved).compareTo(available) != 0)
      throw new BackendException(502, "bank_balance_inconsistent");
  }

  @Override
  public Map<String, Object> reservation(String reservation, String owner) {
    var r = object(read("/reservations/" + quote(reservation) + "?owner=" + quote(owner)));
    validate(r, ContractSchemas.reservation());
    if (!reservation.equals(r.get("id")))
      throw new BackendException(502, "bank_reservation_mismatch");
    return r;
  }

  @Override
  public List<Map<String, Object>> cards(String account) {
    var rows = list(read("/accounts/" + quote(account) + "/cards"));
    rows.forEach(
        r -> {
          validate(r, ContractSchemas.card());
          if (!account.equals(r.get("accountId")))
            throw new BackendException(502, "bank_card_account_mismatch");
        });
    return rows;
  }

  @Override
  public Map<String, Object> execute(
      String key, String owner, String verb, String account, Map<String, Object> args) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("idempotencyKey", key);
    payload.put("userId", owner);
    payload.put("accountId", account);
    payload.put("params", args);
    Object body =
        id.equals("B")
            ? Map.of(
                "requestKey", key, "customerId", owner, "fromAccount", account, "arguments", args)
            : id.equals("C")
                ? Map.of("data", Map.of("type", verb, "id", key, "attributes", payload))
                : payload;
    return object(unwrap(http.send(base, prefix + "/actions/" + quote(verb), "POST", body)));
  }

  @Override
  public List<Map<String, Object>> creditBills(String account) {
    var rows = list(read("/accounts/" + quote(account) + "/credit-bills"));
    for (var row : rows) {
      validate(row, ContractSchemas.creditBill());
      if (!account.equals(row.get("accountId")))
        throw new BackendException(502, "bank_credit_bill_account_mismatch");
      var amount = new java.math.BigDecimal(row.get("amount").toString());
      var paid = new java.math.BigDecimal(row.get("paid").toString());
      var outstanding = new java.math.BigDecimal(row.get("outstanding").toString());
      if (amount.signum() < 0
          || paid.signum() < 0
          || outstanding.signum() < 0
          || amount.subtract(paid).compareTo(outstanding) != 0
          || "paid".equals(row.get("status")) != (outstanding.signum() == 0))
        throw new BackendException(502, "bank_credit_bill_balance_mismatch");
    }
    return rows;
  }

  @Override
  public Map<String, Object> outcome(String key, String owner) {
    return object(read("/outcomes/" + quote(key) + "?owner=" + quote(owner)));
  }

  @Override
  public Map<String, Object> compensate(String key, String owner) {
    return object(
        unwrap(
            http.send(
                base,
                prefix + "/outcomes/" + quote(key) + "/cancel",
                "POST",
                Map.of("userId", owner))));
  }

  @Override
  public boolean healthy() {
    try {
      return "up".equals(object(read("/health")).get("status"));
    } catch (Exception e) {
      return false;
    }
  }
}
