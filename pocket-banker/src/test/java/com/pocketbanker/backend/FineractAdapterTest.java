package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.adapters.nativebank.FineractBankAdapter;
import com.pocketbanker.contracts.ActionAvailability;
import com.pocketbanker.contracts.ActionCatalog;
import com.pocketbanker.contracts.BackendException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;

class FineractAdapterTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private ExecutorService executor;
  private String base;
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private String idempotencyHeader;
  private Function<String, Object> reply;
  private long bodyDelay;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    reply =
        path -> {
          if (path.endsWith("/clients/1"))
            return Map.of("id", 1, "externalId", "alpha", "active", true, "officeId", 1);
          if (path.contains("/savingsaccounts/1")) return savings();
          return Map.of("totalFilteredRecords", 0, "pageItems", List.of());
        };
    server.createContext(
        "/",
        exchange -> {
          methods.add(exchange.getRequestMethod());
          if ("POST".equals(exchange.getRequestMethod()))
            idempotencyHeader = exchange.getRequestHeaders().getFirst("Idempotency-Key");
          try {
            byte[] bytes =
                mapper.writeValueAsBytes(reply.apply(exchange.getRequestURI().toString()));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().flush();
            if (bodyDelay > 0) Thread.sleep(bodyDelay);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  private FineractBankAdapter adapter(long timeout) {
    return new FineractBankAdapter(
        mapper, base, "default", "fixture", "fixture-only", "{\"alpha\":1}", true, timeout, 1, "");
  }

  private Map<String, Object> savings() {
    return Map.of(
        "id",
        1,
        "clientId",
        1,
        "currency",
        Map.of("code", "CNY", "decimalPlaces", 2),
        "status",
        Map.of("active", true),
        "subStatus",
        Map.of("block", false),
        "summary",
        Map.of("accountBalance", 5000, "availableBalance", 5000),
        "allowOverdraft",
        false,
        "withdrawalFeeForTransfers",
        false);
  }

  private String hash(String s) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, Object> transfer() throws Exception {
    return Map.of(
        "id",
        1,
        "reversed",
        false,
        "currency",
        Map.of("code", "CNY"),
        "fromClient",
        Map.of("id", 1),
        "fromAccount",
        Map.of("id", 1),
        "toAccount",
        Map.of("id", 2),
        "transferAmount",
        20,
        "transferDate",
        List.of(2026, 10, 4),
        "transferDescription",
        "pb:" + hash("alpha:key") + ":" + hash("F-1:F-2:20.00"));
  }

  @Test
  void upstreamSavingsSatisfiesFullUnifiedContract() {
    assertThat(adapter(2000).account("F-1"))
        .containsEntry("creditLimit", "0.00")
        .containsEntry("balance", "5000.00")
        .containsEntry("owner", "alpha");
  }

  @Test
  void mismatchedExternalIdentityIsRejected() {
    var original = reply;
    reply =
        path ->
            path.endsWith("/clients/1")
                ? Map.of("id", 1, "externalId", "someone-else", "active", true)
                : original.apply(path);
    assertThatThrownBy(() -> adapter(2000).account("F-1"))
        .hasMessage("fineract_client_mapping_mismatch");
  }

  @Test
  void unknownUserAndUnsupportedActionCannotWrite() {
    var bank = adapter(2000);
    assertThatThrownBy(() -> bank.accounts("other")).hasMessage("fineract_client_not_mapped");
    assertThatThrownBy(() -> bank.requireSupported("reserve", Map.of()))
        .hasMessage("bank_action_not_supported");
    assertThatThrownBy(() -> bank.requireSupported("transfer", Map.of("toBank", "A")))
        .hasMessage("fineract_cross_bank_not_supported");
    assertThat(methods).isEmpty();
  }

  @Test
  void unknownOutcomeOnlyReadsAndDoesNotResubmit() {
    assertThatThrownBy(() -> adapter(2000).outcome("missing", "alpha"))
        .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThat(methods).containsExactly("GET");
  }

  @Test
  void stableReferenceFindsOutcomeAndRejectsDuplicates() throws Exception {
    var tx = transfer();
    reply = path -> Map.of("totalFilteredRecords", 1, "pageItems", List.of(tx));
    assertThat(adapter(2000).outcome("key", "alpha"))
        .containsEntry("amount", "20.00")
        .containsEntry("status", "SUCCEEDED");
    reply = path -> Map.of("totalFilteredRecords", 2, "pageItems", List.of(tx, tx));
    assertThatThrownBy(() -> adapter(2000).outcome("key", "alpha"))
        .hasMessage("duplicate_bank_operations");
    assertThat(methods).containsOnly("GET");
  }

  @Test
  void incompleteScanIsNotMistakenForAbsentTransfer() {
    reply = path -> Map.of("totalFilteredRecords", 101, "pageItems", List.of());
    assertThatThrownBy(() -> adapter(2000).outcome("key", "alpha"))
        .hasMessage("fineract_reconciliation_scan_incomplete");
    assertThat(methods).containsExactly("GET");
  }

  @Test
  void bodyStallIsBoundedByWholeRequestDeadline() {
    bodyDelay = 1500;
    reply = path -> Map.of("status", "UP");
    long start = System.nanoTime();
    assertThat(adapter(100).healthy()).isFalse();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
  }

  @Test
  void invalidConfigurationAndDuplicatedClientMappingFailClosed() {
    assertThatThrownBy(
            () ->
                new FineractBankAdapter(
                    mapper,
                    base,
                    "default",
                    "u",
                    "p",
                    "{\"alpha\":1,\"beta\":1}",
                    true,
                    1000,
                    1,
                    ""))
        .hasMessage("invalid_fineract_configuration");
    assertThatThrownBy(
            () ->
                new FineractBankAdapter(
                    mapper,
                    "http://example.com",
                    "default",
                    "u",
                    "p",
                    "{\"alpha\":1}",
                    true,
                    1000,
                    1,
                    ""))
        .hasMessage("invalid_fineract_configuration");
  }

  @Test
  void actionContractAdmitsFourthBankBeforeCapabilityValidation() {
    new ActionCatalog()
        .require("transfer")
        .validate(
            Map.of(
                "bank",
                "F",
                "amount",
                "20.00",
                "toBank",
                "F",
                "toAccountId",
                "F-2",
                "toName",
                "recipient"),
            false);
  }

  @Test
  void upstreamIdempotencyHeaderFitsItsFiftyCharacterColumn() throws Exception {
    var tx = new HashMap<>(transfer());
    tx.put("toAccount", Map.of("id", 1));
    tx.put("transferDescription", "pb:" + hash("alpha:key") + ":" + hash("F-1:F-1:20.00"));
    var original = reply;
    reply =
        path -> {
          if (!path.contains("/accounttransfers")) return original.apply(path);
          return methods.contains("POST")
              ? Map.of("totalFilteredRecords", 1, "pageItems", List.of(tx))
              : Map.of("totalFilteredRecords", 0, "pageItems", List.of());
        };
    adapter(2000)
        .execute(
            "key",
            "alpha",
            "transfer",
            "F-1",
            Map.of("amount", "20.00", "toBank", "F", "toAccountId", "F-1"));
    assertThat(idempotencyHeader).hasSizeLessThanOrEqualTo(50);
    assertThat(idempotencyHeader)
        .isEqualTo(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(HexFormat.of().parseHex(hash("alpha:key"))));
  }

  @Test
  void oversizedResponsesAreRejectedWhileStreaming() {
    reply = path -> Map.of("content", "x".repeat(1_000_001));
    assertThatThrownBy(() -> adapter(2000).outcome("key", "alpha"))
        .hasMessage("bank_response_too_large");
  }

  @Test
  void transferDateMayUseIsoEncodingUnlikeSavingsStatements() throws Exception {
    var tx = new HashMap<>(transfer());
    tx.put("transferDate", "2026-10-04");
    reply = path -> Map.of("totalFilteredRecords", 1, "pageItems", List.of(tx));
    assertThat(adapter(2000).outcome("key", "alpha"))
        .containsEntry("settleAt", "2026-10-04T00:00:00Z");
  }

  @Test
  void matchedReferenceCannotHideAlteredSourceAccountOrMissingStatus() throws Exception {
    var tx = new HashMap<>(transfer());
    tx.put("fromAccount", Map.of("id", 3));
    reply = path -> Map.of("totalFilteredRecords", 1, "pageItems", List.of(tx));
    assertThatThrownBy(() -> adapter(2000).outcome("key", "alpha"))
        .hasMessage("bank_reference_parameters_mismatch");
    tx.put("fromAccount", Map.of("id", 1));
    tx.remove("reversed");
    assertThatThrownBy(() -> adapter(2000).outcome("key", "alpha"))
        .hasMessage("invalid_bank_transfer");
  }

  @Test
  void mockToFineractIsRejectedBeforeAnyProviderCall() {
    var availability = new ActionAvailability(new ActionCatalog(), null, null, null);
    assertThatThrownBy(
            () ->
                availability.require(
                    "alpha",
                    new ActionCatalog().require("transfer"),
                    Map.of("bank", "A", "toBank", "F")))
        .hasMessage("fineract_cross_bank_not_supported");
  }
}
