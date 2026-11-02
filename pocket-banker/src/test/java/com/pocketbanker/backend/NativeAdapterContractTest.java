package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.pocketbanker.adapters.nativebank.*;
import com.pocketbanker.contracts.BackendException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Captures the real Java transport; its independent peer publishes the expected routes/body. */
class NativeAdapterContractTest {
  record Request(String method, String path, String query, JsonNode body) {}

  @ParameterizedTest
  @ValueSource(strings = {"A", "B", "C"})
  void actualAdapterRequestsMatchTheIndependentlyPublishedNativeContract(String bank)
      throws Exception {
    var mapper = new ObjectMapper();
    var peer = new NativeFixture();
    var spec = mapper.valueToTree(peer.get(bank, "/openapi.json"));
    var capture = new AtomicReference<Request>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("X-Mock-Service-Token"))
              .isEqualTo("synthetic-contract-key");
          byte[] input = exchange.getRequestBody().readAllBytes();
          capture.set(
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath(),
                  exchange.getRequestURI().getRawQuery(),
                  input.length == 0 ? mapper.createObjectNode() : mapper.readTree(input)));
          byte[] reply =
              (bank.equals("A")
                      ? "{\"data\":{}}"
                      : bank.equals("C") ? "{\"data\":{\"attributes\":{}}}" : "{}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new HttpBankAdapter(
              bank,
              "http://127.0.0.1:" + server.getAddress().getPort(),
              new NativeHttpClient(mapper, "synthetic-contract-key", 2000));
      List<Runnable> calls =
          new ArrayList<>(
              List.of(
                  () -> adapter.accounts("owner with space"),
                  () -> adapter.account("owned-account"),
                  () -> adapter.transactions("owned-account", 250, 250),
                  () -> adapter.products(),
                  () -> adapter.holdings("owned-account"),
                  () -> adapter.subscriptions("owned-account"),
                  () -> adapter.cards("owned-account"),
                  () -> adapter.creditBills("owned-account"),
                  () -> adapter.reservation("reservation", "owner with space"),
                  () -> adapter.outcome("operation", "owner with space"),
                  () -> adapter.compensate("operation", "owner with space"),
                  () -> adapter.healthy()));
      spec.path("paths")
          .fieldNames()
          .forEachRemaining(
              path -> {
                if (path.contains("/actions/"))
                  calls.add(
                      () ->
                          adapter.execute(
                              "operation",
                              "owner",
                              path.substring(path.lastIndexOf('/') + 1),
                              "owned-account",
                              Map.of("amount", "100.00")));
              });
      assertThat(calls.size()).isGreaterThan(20);
      for (var call : calls) {
        capture.set(null);
        try {
          call.run();
        } catch (BackendException expectedInvalidSyntheticResponse) {
          assertThat(expectedInvalidSyntheticResponse.status()).isEqualTo(502);
        }
        var request = capture.get();
        assertThat(request).isNotNull();
        String route = null;
        var paths = spec.path("paths").fieldNames();
        while (paths.hasNext()) {
          String candidate = paths.next();
          if (request.path().matches(candidate.replaceAll("\\{[^}]+\\}", "[^/]+"))) {
            route = candidate;
            break;
          }
        }
        assertThat(route).as(request.method() + " " + request.path()).isNotNull();
        var operation = spec.path("paths").path(route).path(request.method().toLowerCase());
        assertThat(operation.isMissingNode()).isFalse();
        if (route.contains("/actions/"))
          validateRequired(
              operation.path("requestBody").path("content").path("application/json").path("schema"),
              request.body());
        if (route.endsWith("/transactions"))
          assertThat(request.query()).contains("offset=250", "limit=250");
        if (route.endsWith("/reservations/{id}") || route.endsWith("/outcomes/{key}"))
          assertThat(request.query()).contains("owner=owner%20with%20space");
      }
    } finally {
      server.stop(0);
      peer.close();
    }
  }

  private void validateRequired(JsonNode schema, JsonNode body) {
    for (var required : schema.path("required"))
      assertThat(body.has(required.asText())).as(required.asText()).isTrue();
    schema
        .path("properties")
        .fields()
        .forEachRemaining(
            field -> {
              if (body.has(field.getKey()))
                validateRequired(field.getValue(), body.path(field.getKey()));
            });
  }
}
