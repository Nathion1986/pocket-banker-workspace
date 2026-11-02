package com.pocketbanker.channels.web;

import com.pocketbanker.agent.BackendFacade;
import com.pocketbanker.contracts.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * JSON-RPC MCP tools over authenticated HTTP; calls create verified workflows, never raw bank
 * calls.
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpController {
  private final BackendFacade facade;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  @PostMapping
  public ResponseEntity<?> rpc(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> request) {
    facade.user(token);
    Object id = request.get("id");
    String method = String.valueOf(request.get("method"));
    if (!"2.0".equals(request.get("jsonrpc")))
      return ResponseEntity.badRequest().body(error(id, -32600, "invalid_jsonrpc"));
    if (!request.containsKey("id") && method.startsWith("notifications/"))
      return ResponseEntity.accepted().build();
    try {
      Object result;
      switch (method) {
        case "initialize" -> {
          var params = (Map<String, Object>) request.get("params");
          if (params == null || !(params.get("protocolVersion") instanceof String))
            throw new BackendException(400, "protocol_version_required");
          String requested = params.get("protocolVersion").toString();
          String negotiated =
              Set.of("2025-03-26", "2025-06-18").contains(requested) ? requested : "2025-03-26";
          result =
              Map.of(
                  "protocolVersion",
                  negotiated,
                  "capabilities",
                  Map.of("tools", Map.of("listChanged", false)),
                  "serverInfo",
                  Map.of("name", "pocket-banker", "version", "2.0.0"));
        }
        case "ping" -> result = Map.of();
        case "tools/list" -> result =
            Map.of(
                "tools",
                facade.actions(token).stream()
                    .filter(s -> !s.id().equals("release_budget"))
                    .map(
                        s ->
                            Map.of(
                                "name",
                                s.id(),
                                "description",
                                s.provider()
                                    + " action; risk="
                                    + s.risk()
                                    + ", creates a task requiring declared authorization",
                                "inputSchema",
                                s.inputSchema(),
                                "annotations",
                                Map.of(
                                    "readOnlyHint",
                                    Set.of(
                                            "query_balance",
                                            "query_transactions",
                                            "wealth_query",
                                            "card_query",
                                            "bill_analysis",
                                            "anomaly_detect",
                                            "subscription_detect")
                                        .contains(s.id()),
                                    "idempotentHint",
                                    true)))
                    .toList());
        case "tools/call" -> {
          var params = (Map<String, Object>) request.get("params");
          if (key == null
              && params != null
              && params.get("_meta") instanceof Map<?, ?> metadata
              && metadata.get("pocketbanker/idempotencyKey") instanceof String businessKey)
            key = businessKey;
          if (params == null || key == null || key.isBlank() || key.length() > 140)
            throw new BackendException(400, "idempotency_key_required");
          String name = String.valueOf(params.get("name"));
          if (name.equals("release_budget"))
            throw new BackendException(400, "internal_action_not_callable");
          var args = (Map<String, Object>) params.get("arguments");
          var task =
              facade.create(
                  token,
                  new WorkflowPlan(
                      "mcp:" + key,
                      List.of(new WorkflowPlan.Node("action", name, args, List.of())),
                      new WorkflowPlan.Trigger("immediate", null),
                      null,
                      false));
          result =
              Map.of(
                  "content",
                  List.of(Map.of("type", "text", "text", mapper.writeValueAsString(task))),
                  "isError",
                  false);
        }
        default -> {
          return ResponseEntity.ok(error(id, -32601, "method_not_found"));
        }
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("jsonrpc", "2.0");
      response.put("id", id);
      response.put("result", result);
      return ResponseEntity.ok(response);
    } catch (BackendException e) {
      return ResponseEntity.ok(error(id, -32602, e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.ok(error(id, -32602, "invalid_tool_request"));
    }
  }

  private Map<String, Object> error(Object id, int code, String message) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("jsonrpc", "2.0");
    result.put("id", id);
    result.put("error", Map.of("code", code, "message", message));
    return result;
  }
}
