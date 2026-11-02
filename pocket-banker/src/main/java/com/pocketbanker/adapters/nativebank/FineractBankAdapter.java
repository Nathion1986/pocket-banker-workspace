package com.pocketbanker.adapters.nativebank;

import static com.pocketbanker.adapters.nativebank.NativeHttpClient.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Explicit CNY savings subset. Fineract remains the independent source of account/transfer truth.
 */
public final class FineractBankAdapter implements BankAdapter {
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String base, writeBase, tenant, authorization;
  private final Map<String, Long> clients;
  private final Duration timeout;
  private final long paymentTypeId;

  public FineractBankAdapter(
      ObjectMapper mapper,
      String base,
      String tenant,
      String username,
      String password,
      String clientsJson,
      boolean allowLocalHttp,
      long timeoutMillis,
      long paymentTypeId,
      String writeBase) {
    try {
      URI uri = URI.create(base);
      boolean local =
          allowLocalHttp && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
      if ((!local && !"https".equals(uri.getScheme()))
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !Set.of("", "/").contains(uri.getPath())
          || username.isBlank()
          || password.isBlank()
          || tenant.isBlank()
          || timeoutMillis < 100
          || timeoutMillis > 30000
          || paymentTypeId <= 0) throw new IllegalArgumentException();
      this.mapper = mapper;
      this.base = base.replaceAll("/+$", "");
      this.writeBase = writeBase.isBlank() ? this.base : writeBase.replaceAll("/+$", "");
      URI writeUri = URI.create(this.writeBase);
      if (!Objects.equals(uri.getScheme(), writeUri.getScheme())
          || !Objects.equals(uri.getHost(), writeUri.getHost())
          || writeUri.getUserInfo() != null
          || writeUri.getQuery() != null
          || writeUri.getFragment() != null
          || !Set.of("", "/").contains(writeUri.getPath())) throw new IllegalArgumentException();
      this.tenant = tenant;
      this.paymentTypeId = paymentTypeId;
      this.authorization =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
      clients =
          Map.copyOf(mapper.readValue(clientsJson, new TypeReference<Map<String, Long>>() {}));
      if (clients.isEmpty()
          || clients.values().stream().anyMatch(id -> id <= 0)
          || new HashSet<>(clients.values()).size() != clients.size())
        throw new IllegalArgumentException();
      timeout = Duration.ofMillis(timeoutMillis);
      client = HttpClient.newBuilder().connectTimeout(timeout).build();
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid_fineract_configuration");
    }
  }

  @Override
  public String id() {
    return "F";
  }

  @Override
  public boolean supports(String action) {
    return Set.of("query_balance", "query_transactions", "transfer").contains(action);
  }

  @Override
  public void requireSupported(String action, Map<String, Object> params) {
    BankAdapter.super.requireSupported(action, params);
    if (action.equals("transfer") && !"F".equals(params.get("toBank")))
      throw new BackendException(400, "fineract_cross_bank_not_supported");
  }

  private Object request(String path, String method, Object body, String key) {
    try {
      var builder =
          HttpRequest.newBuilder(
                  URI.create(
                      (method.equals("GET") ? base : writeBase) + "/fineract-provider" + path))
              .timeout(timeout)
              .header("Authorization", authorization)
              .header("Fineract-Platform-TenantId", tenant)
              .header("Content-Type", "application/json");
      if (key != null) builder.header("Idempotency-Key", key);
      if (method.equals("GET")) builder.GET();
      else builder.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
      var pending = client.sendAsync(builder.build(), info -> new LimitedBody());
      HttpResponse<byte[]> response;
      try {
        response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        pending.cancel(true);
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        if (e.getCause() instanceof BackendException failure) throw failure;
        throw e;
      }
      byte[] bytes = response.body();
      int status = response.statusCode();
      if (status == 404) throw new BackendException(404, "bank_resource_not_found");
      if (status == 401 || status == 403)
        throw new BackendException(503, "fineract_service_auth_failed");
      if (status == 429 || status >= 500)
        throw new BackendException(
            503, method.equals("GET") ? "bank_unavailable" : "bank_result_unknown");
      if (status >= 400) throw new BackendException(409, "fineract_business_rejected");
      if (status < 200 || status >= 300) throw new BackendException(502, "invalid_bank_status");
      return mapper.readValue(bytes, Object.class);
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      throw new BackendException(
          503, method.equals("GET") ? "bank_unavailable" : "bank_result_unknown");
    }
  }

  /** Bound bytes while streaming and retain a deadline covering headers and body. */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long received;

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      delegate.onSubscribe(value);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) received += buffer.remaining();
      if (received > 1_000_000) {
        subscription.cancel();
        delegate.onError(new BackendException(502, "bank_response_too_large"));
      } else delegate.onNext(buffers);
    }

    public void onError(Throwable failure) {
      delegate.onError(failure);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }

  private Map<String, Object> read(String path) {
    return object(request("/api/v1" + path, "GET", null, null));
  }

  private static long number(Object value) {
    try {
      long id = Long.parseLong(value.toString());
      if (id <= 0) throw new IllegalArgumentException();
      return id;
    } catch (Exception e) {
      throw new BackendException(502, "invalid_fineract_id");
    }
  }

  private static long accountNumber(String id) {
    if (id == null || !id.matches("F-[1-9][0-9]{0,17}"))
      throw new BackendException(400, "invalid_fineract_account");
    return number(id.substring(2));
  }

  private String owner(long clientId) {
    return clients.entrySet().stream()
        .filter(e -> e.getValue() == clientId)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new BackendException(403, "fineract_client_not_mapped"));
  }

  private Map<String, Object> verifiedClient(long clientId) {
    var value = read("/clients/" + clientId);
    if (number(value.get("id")) != clientId
        || !owner(clientId).equals(value.get("externalId"))
        || !Boolean.TRUE.equals(value.get("active")))
      throw new BackendException(403, "fineract_client_mapping_mismatch");
    return value;
  }

  private Map<String, Object> savings(String id) {
    var value = read("/savingsaccounts/" + accountNumber(id));
    if (!id.equals("F-" + number(value.get("id"))))
      throw new BackendException(502, "bank_account_id_mismatch");
    verifiedClient(number(value.get("clientId")));
    var currency = object(value.get("currency"));
    if (!"CNY".equals(currency.get("code"))
        || ((Number) currency.get("decimalPlaces")).intValue() != 2)
      throw new BackendException(409, "fineract_currency_not_supported");
    return value;
  }

  @Override
  public List<Map<String, Object>> accounts(String user) {
    Long clientId = clients.get(user);
    if (clientId == null) throw new BackendException(403, "fineract_client_not_mapped");
    verifiedClient(clientId);
    var rows =
        read("/clients/" + clientId + "/accounts").getOrDefault("savingsAccounts", List.of());
    List<Map<String, Object>> result = new ArrayList<>();
    for (var row : list(rows)) {
      var account = account("F-" + number(row.get("id")));
      if (!user.equals(account.get("owner")))
        throw new BackendException(502, "bank_account_owner_mismatch");
      result.add(account);
    }
    return result;
  }

  private static BigDecimal decimal(Object value) {
    try {
      return new BigDecimal(value.toString()).setScale(2, RoundingMode.UNNECESSARY);
    } catch (Exception e) {
      throw new BackendException(502, "invalid_bank_amount");
    }
  }

  @Override
  public Map<String, Object> account(String id) {
    var raw = savings(id);
    if (Boolean.TRUE.equals(raw.get("allowOverdraft")))
      throw new BackendException(409, "fineract_overdraft_not_supported");
    var summary = object(raw.get("summary"));
    var balance = decimal(summary.get("accountBalance"));
    var available = decimal(summary.get("availableBalance"));
    var reserved = balance.subtract(available);
    if (available.signum() < 0 || reserved.signum() < 0)
      throw new BackendException(502, "bank_balance_inconsistent");
    var sub = object(raw.get("subStatus"));
    boolean blocked =
        !Boolean.TRUE.equals(object(raw.get("status")).get("active"))
            || Boolean.TRUE.equals(sub.get("block"))
            || Boolean.TRUE.equals(sub.get("blockDebit"));
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id);
    value.put("owner", owner(number(raw.get("clientId"))));
    value.put("bank", "F");
    value.put("currency", "CNY");
    value.put("balance", balance.toPlainString());
    value.put("available", available.toPlainString());
    value.put("reserved", reserved.toPlainString());
    value.put("frozen", blocked);
    value.put("creditLimit", "0.00");
    // Local integration limit; not represented as an upstream bank policy.
    value.put("singleLimit", "1000000.00");
    value.put("limitSource", "local_integration_policy");
    value.put("transferFeeRate", "0");
    value.put("settlementDays", 0);
    SchemaValidator.validate(value, ContractSchemas.account(), 502, "invalid_bank_schema");
    return value;
  }

  private static String date(Object raw) {
    try {
      if (raw instanceof String isoDate)
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
      List<?> parts = (List<?>) raw;
      return LocalDate.of(
              ((Number) parts.get(0)).intValue(),
              ((Number) parts.get(1)).intValue(),
              ((Number) parts.get(2)).intValue())
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant()
          .toString();
    } catch (Exception e) {
      throw new BackendException(502, "invalid_fineract_date");
    }
  }

  @Override
  public List<Map<String, Object>> transactions(String id, int offset, int limit) {
    savings(id);
    var raw = read("/savingsaccounts/" + accountNumber(id) + "?associations=transactions");
    var rows = list(raw.getOrDefault("transactions", List.of()));
    List<Map<String, Object>> result = new ArrayList<>();
    for (var row : rows) {
      if (Boolean.TRUE.equals(row.get("reversed"))) continue;
      if (number(row.get("accountId")) != accountNumber(id))
        throw new BackendException(502, "bank_transaction_account_mismatch");
      var amount = decimal(row.get("amount"));
      var type = object(row.get("transactionType"));
      String entry = Objects.toString(row.get("entryType"), "");
      if (entry.equals("DEBIT")) amount = amount.negate();
      else if (!entry.equals("CREDIT"))
        throw new BackendException(502, "unsupported_fineract_entry_type");
      var item =
          Map.<String, Object>of(
              "id",
              "F-T-" + number(row.get("id")),
              "accountId",
              id,
              "amount",
              amount.toPlainString(),
              "currency",
              "CNY",
              "merchant",
              Objects.toString(type.get("value"), "Fineract"),
              "category",
              row.get("transfer") == null ? "其他" : "转账",
              "city",
              "未知",
              "timePrecision",
              "day",
              "occurredAt",
              date(row.get("date")));
      SchemaValidator.validate(item, ContractSchemas.transaction(), 502, "invalid_bank_schema");
      result.add(item);
    }
    result.sort(Comparator.comparing(item -> item.get("id").toString()));
    return result.stream().skip(offset).limit(limit).toList();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String reference(String user, String key) {
    return "pb:" + hash(user + ":" + key);
  }

  private Map<String, Object> find(String user, String key) {
    String reference = reference(user, key);
    List<Map<String, Object>> found = new ArrayList<>();
    // Upstream externalId is not a transfer filter in the pinned implementation.
    // Read bounded pages, compare the stable reference, and fail closed if incomplete.
    for (int offset = 0; offset < 10000; offset += 100) {
      var page =
          read("/accounttransfers?offset=" + offset + "&limit=100&orderBy=att.id&sortOrder=ASC");
      var rows = list(page.get("pageItems"));
      for (var row : rows)
        if (Objects.toString(row.get("transferDescription"), "").startsWith(reference + ":"))
          found.add(row);
      int total = ((Number) page.get("totalFilteredRecords")).intValue();
      if (offset + rows.size() >= total) {
        if (found.size() > 1) throw new BackendException(502, "duplicate_bank_operations");
        if (found.isEmpty()) throw new BackendException(404, "bank_resource_not_found");
        var result = found.get(0);
        if (!user.equals(owner(number(object(result.get("fromClient")).get("id")))))
          throw new BackendException(403, "bank_operation_not_owned");
        return result;
      }
      if (rows.isEmpty()) break;
    }
    throw new BackendException(503, "fineract_reconciliation_scan_incomplete");
  }

  @Override
  public Map<String, Object> execute(
      String key, String user, String verb, String account, Map<String, Object> args) {
    requireSupported(verb, args);
    var from = savings(account);
    if (!user.equals(owner(number(from.get("clientId")))))
      throw new BackendException(403, "account_not_owned");
    String destination = Objects.toString(args.get("toAccountId"), "");
    var to = savings(destination);
    var amount = Money.positive(args.get("amount")).setScale(2);
    if (amount.compareTo(new BigDecimal("1000000.00")) > 0)
      throw new BackendException(409, "fineract_local_transfer_limit");
    if (Boolean.TRUE.equals(from.get("withdrawalFeeForTransfers")))
      throw new BackendException(409, "fineract_transfer_fee_not_supported");
    var description = reference(user, key) + ":" + hash(account + ":" + destination + ":" + amount);
    try {
      var old = find(user, key);
      if (!description.equals(old.get("transferDescription")))
        throw new BackendException(409, "bank_idempotency_conflict");
      return normalize(user, key, old);
    } catch (BackendException e) {
      if (e.status() != 404) throw e;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fromAccountType", "2");
    body.put("toAccountType", "2");
    body.put("fromAccountId", Long.toString(accountNumber(account)));
    body.put("toAccountId", Long.toString(accountNumber(destination)));
    body.put("fromClientId", from.get("clientId").toString());
    body.put("toClientId", to.get("clientId").toString());
    body.put(
        "fromOfficeId", verifiedClient(number(from.get("clientId"))).get("officeId").toString());
    body.put("toOfficeId", verifiedClient(number(to.get("clientId"))).get("officeId").toString());
    body.put("transferDate", LocalDate.now(ZoneOffset.UTC).toString());
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("locale", "en");
    body.put("transferAmount", amount.toPlainString());
    body.put("transferDescription", description);
    body.put("paymentTypeId", paymentTypeId);
    // Upstream idempotency_key is varchar(50); Base64url retains all 256 bits in 43 chars.
    String bankKey =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(HexFormat.of().parseHex(hash(user + ":" + key)));
    request("/api/v1/accounttransfers", "POST", body, bankKey);
    return outcome(key, user);
  }

  private Map<String, Object> normalize(String user, String key, Map<String, Object> transfer) {
    if (!"CNY".equals(object(transfer.get("currency")).get("code"))
        || !(transfer.get("reversed") instanceof Boolean)
        || !user.equals(owner(number(object(transfer.get("fromClient")).get("id")))))
      throw new BackendException(502, "invalid_bank_transfer");
    String fromAccount = "F-" + number(object(transfer.get("fromAccount")).get("id"));
    String toAccount = "F-" + number(object(transfer.get("toAccount")).get("id"));
    String amount = decimal(transfer.get("transferAmount")).toPlainString();
    String expected =
        reference(user, key) + ":" + hash(fromAccount + ":" + toAccount + ":" + amount);
    if (!expected.equals(transfer.get("transferDescription")))
      throw new BackendException(502, "bank_reference_parameters_mismatch");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operationId", key);
    result.put("upstreamTransferId", number(transfer.get("id")));
    result.put("status", Boolean.TRUE.equals(transfer.get("reversed")) ? "CANCELLED" : "SUCCEEDED");
    result.put("kind", "transfer");
    result.put("amount", amount);
    result.put("currency", "CNY");
    result.put("fromAccountId", fromAccount);
    result.put("toAccountId", toAccount);
    result.put("toBank", "F");
    result.put("fee", "0.00");
    result.put("settleAt", date(transfer.get("transferDate")));
    return result;
  }

  @Override
  public Map<String, Object> outcome(String key, String owner) {
    return normalize(owner, key, find(owner, key));
  }

  @Override
  public boolean supportsCompensation(String action) {
    return false;
  }

  private BackendException unsupported() {
    return new BackendException(400, "bank_action_not_supported");
  }

  @Override
  public List<Map<String, Object>> products() {
    throw unsupported();
  }

  @Override
  public List<Map<String, Object>> holdings(String id) {
    throw unsupported();
  }

  @Override
  public Map<String, Object> reservation(String id, String owner) {
    throw unsupported();
  }

  @Override
  public List<Map<String, Object>> cards(String id) {
    throw unsupported();
  }

  @Override
  public List<Map<String, Object>> subscriptions(String id) {
    throw unsupported();
  }

  @Override
  public Map<String, Object> compensate(String key, String owner) {
    throw unsupported();
  }

  @Override
  public boolean healthy() {
    try {
      return "UP".equals(object(request("/actuator/health", "GET", null, null)).get("status"));
    } catch (BackendException e) {
      return false;
    }
  }
}
