package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.agent.*;
import com.pocketbanker.contracts.*;
import com.pocketbanker.orchestration.PlanningProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class PlanningProtocolTest {
  ObjectMapper mapper = new ObjectMapper();
  HttpServer server;
  ExecutorService threads;
  String base;
  Map<String, Object> seen;
  String mode = "valid";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    threads = Executors.newCachedThreadPool();
    server.setExecutor(threads);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            var body = mapper.readTree(exchange.getRequestBody());
            seen =
                mapper.readValue(body.path("messages").path(1).path("content").asText(), Map.class);
            if (mode.equals("timeout")) {
              Thread.sleep(1000);
            }
            var original = (List<Map<String, Object>>) seen.get("requested");
            Map<String, Object> node = new LinkedHashMap<>(original.get(0));
            node.put("params", new LinkedHashMap<>((Map<?, ?>) node.get("params")));
            if (mode.equals("invent"))
              ((Map<String, Object>) node.get("params")).put("amount", "999.00");
            if (mode.equals("unknown")) node.put("actionId", "send_money_without_permission");
            if (mode.equals("extra")) node.put("approved", true);
            String content = mapper.writeValueAsString(Map.of("nodes", List.of(node)));
            if (mode.equals("malformed")) content = "not JSON";
            byte[] reply =
                mapper.writeValueAsBytes(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
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
  void close() {
    server.stop(0);
    threads.shutdownNow();
  }

  WorkflowPlan candidate() {
    return new WorkflowPlan(
        "planner-fixture",
        List.of(
            new WorkflowPlan.Node(
                "action",
                "transfer",
                Map.of(
                    "bank",
                    "F",
                    "amount",
                    "123.45",
                    "toBank",
                    "F",
                    "toAccountId",
                    "F-2",
                    "toName",
                    "原始姓名"),
                List.of())),
        new WorkflowPlan.Trigger("at", "2026-10-05T02:00:00Z"),
        null,
        false);
  }

  StructuredPlanningService service(int timeout) {
    return new StructuredPlanningService(
        new ActionCatalog(),
        mapper,
        base,
        "fixture-planner-key",
        "fixture-model",
        "local-protocol",
        timeout, "");
  }

  @Test
  void realHttpProtocolBindsPrivateValuesLocallyAndPreservesTrigger() {
    assertThat(service(2000).refine(candidate())).isEqualTo(candidate());
    String wire = seen.toString();
    assertThat(wire).doesNotContain("123.45", "原始姓名", "F-2", "2026-10-05", "fixture-planner-key");
    assertThat(wire).contains("inputSchema", "outputSchema", "@action:amount");
  }

  @Test
  void hallucinatedMoneyActionOrApprovalCannotEnterPlan() {
    for (String fault : List.of("invent", "unknown", "extra")) {
      mode = fault;
      assertThatThrownBy(() -> service(2000).refine(candidate()))
          .isInstanceOf(BackendException.class);
    }
  }

  @Test
  void malformedResponseAndTimeoutAreBoundedAndNeverProduceFallbackMoneyPlan() {
    mode = "malformed";
    assertThatThrownBy(() -> service(100).refine(candidate())).isInstanceOf(BackendException.class);
    mode = "timeout";
    long start = System.nanoTime();
    assertThatThrownBy(() -> service(100).refine(candidate())).hasMessage("planner_unavailable");
    assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1000);
  }

  @Test
  void duplicatesOrCycleAreRejectedDespiteValidNodeSchemas() {
    var draft =
        new WorkflowPlan(
            "graph",
            List.of(
                new WorkflowPlan.Node("a", "query_balance", Map.of("bank", "A"), List.of()),
                new WorkflowPlan.Node(
                    "b", "query_transactions", Map.of("bank", "A"), List.of("a"))),
            new WorkflowPlan.Trigger("immediate", null),
            null,
            false);
    PlanningProvider bad =
        (shape, budget) ->
            Map.of(
                "nodes",
                List.of(
                    Map.of(
                        "id",
                        "a",
                        "actionId",
                        "query_balance",
                        "params",
                        Map.of("bank", "@a:bank"),
                        "dependsOn",
                        List.of("b")),
                    Map.of(
                        "id",
                        "b",
                        "actionId",
                        "query_transactions",
                        "params",
                        Map.of("bank", "@b:bank"),
                        "dependsOn",
                        List.of("a"))));
    assertThatThrownBy(
            () -> new StructuredPlanningService(new ActionCatalog(), bad, 1000).refine(draft))
        .hasMessage("planner_invalid_plan");
  }
}
