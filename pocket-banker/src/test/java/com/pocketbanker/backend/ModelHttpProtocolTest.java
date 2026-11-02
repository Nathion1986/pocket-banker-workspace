package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.orchestration.ModelCoordinator;
import com.pocketbanker.security.BackendAudit;
import com.pocketbanker.security.BackendCircuit;
import com.pocketbanker.shadow.ScopedShadowGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelHttpProtocolTest {
  final ObjectMapper mapper = new ObjectMapper();
  HttpServer server;
  ExecutorService workers;
  String base;
  volatile JsonNode seen;
  int delayMs;
  String finishReason = "stop";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            seen = mapper.readTree(exchange.getRequestBody());
            if (delayMs > 0) Thread.sleep(delayMs);
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("message", Map.of("content", "{\"decision\":\"confirm\"}"));
            if (!"missing".equals(finishReason)) choice.put("finish_reason", finishReason);
            byte[] reply = mapper.writeValueAsBytes(Map.of("choices", List.of(choice)));
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
          } catch (InterruptedException interrupted) {
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
    workers.shutdownNow();
  }

  ModelCoordinator coordinator(Map<String, String> extra, long timeout) throws Exception {
    assertThat(System.getenv("MODEL_PROTOCOL_TEST_KEY"))
        .isEqualTo("synthetic-local-model-test-key");
    var shadow = mock(ScopedShadowGateway.class);
    when(shadow.mask(anyString(), anyString(), anyMap()))
        .thenAnswer(
            call ->
                new ScopedShadowGateway.Prompt(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    Map<String, String> config =
        new LinkedHashMap<>(
            Map.of(
                "model",
                "synthetic-model",
                "role",
                "primary",
                "baseUrl",
                base,
                "apiKeyEnv",
                "MODEL_PROTOCOL_TEST_KEY"));
    config.putAll(extra);
    return new ModelCoordinator(
        shadow,
        mock(BackendStore.class),
        mock(BackendAudit.class),
        mapper,
        mock(BackendCircuit.class),
        "local-protocol",
        mapper.writeValueAsString(Map.of("fixture-primary", config)),
        timeout);
  }

  @Test
  void configuredCoordinatorBudgetMustNotBeShortenedToThreeSeconds() throws Exception {
    delayMs = 3400;
    var model = coordinator(Map.of(), 6500);
    assertThat(
            model
                .review(
                    "synthetic-user",
                    "synthetic-session",
                    Map.of("kind", "account_query"),
                    "green",
                    true)
                .decision())
        .isEqualTo("confirm");
  }

  @Test
  void explicitThinkingAndJsonOptionsReachRealHttpRequest() throws Exception {
    var model =
        coordinator(
            Map.of("thinking", "disabled", "responseFormat", "json_object", "maxTokens", "256"),
            6500);
    assertThat(
            model
                .review(
                    "synthetic-user",
                    "synthetic-session",
                    Map.of("kind", "account_query"),
                    "green",
                    true)
                .decision())
        .isEqualTo("confirm");
    assertThat(seen.path("thinking").path("type").asText()).isEqualTo("disabled");
    assertThat(seen.path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(seen.path("max_tokens").asInt()).isEqualTo(256);
  }

  @ParameterizedTest
  @ValueSource(strings = {"length", "tool_calls", "missing"})
  void incompleteRealModelResponseCannotApproveEvenWithValidJson(String reason) throws Exception {
    finishReason = reason;
    var model = coordinator(Map.of("strictFinishReason", "true"), 6500);
    assertThat(
            model
                .review(
                    "synthetic-user",
                    "synthetic-session",
                    Map.of("kind", "account_query"),
                    "green",
                    true)
                .decision())
        .isEqualTo("abstain");
  }
}
