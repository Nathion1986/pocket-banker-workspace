package com.pocketbanker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.*;
import com.pocketbanker.orchestration.HttpPlanningProvider;
import com.pocketbanker.orchestration.PlanningProvider;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** The model selects a valid graph; private values and user authority remain bound locally. */
@Service
public class StructuredPlanningService {
  private final ActionCatalog catalog;
  private final PlanningProvider provider;
  private final String understandModel;
  private final long timeout;

  @org.springframework.beans.factory.annotation.Autowired
  public StructuredPlanningService(
      ActionCatalog catalog,
      ObjectMapper mapper,
      @Value("${backend.planner.url:}") String url,
      @Value("${backend.planner.api-key:}") String key,
      @Value("${backend.planner.model:}") String model,
      @Value("${backend.model-mode:mock}") String mode,
      @Value("${backend.planner.timeout-ms:2000}") long timeout,
      @Value("${backend.understand-model:}") String understandModel) {
    if (timeout < 100 || timeout > 30000)
      throw new IllegalArgumentException("invalid_planner_timeout");
    this.catalog = catalog;
    this.timeout = timeout;
    this.understandModel = understandModel;
    provider =
        url.isBlank()
            ? null
            : new HttpPlanningProvider(mapper, url, key, model, mode.equals("local-protocol"));
  }

  public StructuredPlanningService(ActionCatalog catalog, PlanningProvider provider, long timeout) {
    this.catalog = catalog;
    this.provider = provider;
    this.timeout = timeout;
    this.understandModel = "";
  }

  public boolean enabled() {
    return provider != null;
  }

  public String health() {
    return provider == null ? "disabled" : provider.health();
  }

  /** 规则未命中时的开放理解：GLM 返回 intent/slots/ask；失败返回 null 走原兜底。 */
  public Map<String, Object> understand(Map<String, Object> features) {
    if (provider == null) return null;
    try {
      return provider.understand(features, Duration.ofMillis(timeout), understandModel);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  public WorkflowPlan refine(WorkflowPlan candidate) {
    if (provider == null) return candidate;
    Map<String, WorkflowPlan.Node> source = new LinkedHashMap<>();
    Map<String, Object> values = new HashMap<>();
    List<Map<String, Object>> requested = new ArrayList<>();
    for (var node : candidate.nodes()) {
      source.put(node.id(), node);
      Map<String, String> refs = new LinkedHashMap<>();
      node.params()
          .forEach(
              (name, value) -> {
                String ref = "@" + node.id() + ":" + name;
                refs.put(name, ref);
                values.put(ref, value);
              });
      requested.add(
          Map.of(
              "id",
              node.id(),
              "actionId",
              node.actionId(),
              "params",
              refs,
              "dependsOn",
              node.dependsOn()));
    }
    var definitions =
        source.values().stream()
            .map(n -> catalog.require(n.actionId()))
            .distinct()
            .map(
                spec ->
                    Map.of(
                        "actionId",
                        spec.id(),
                        "inputSchema",
                        spec.inputSchema(),
                        "outputSchema",
                        spec.outputSchema()))
            .toList();
    // No raw sentence, identity, account, amount, date, or credential enters the request features.
    Map<String, Object> shape =
        Map.of(
            "actions",
            definitions,
            "requested",
            requested,
            "triggerType",
            candidate.trigger().type());
    long end = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
    Map<String, Object> response = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      long left = (end - System.nanoTime()) / 1_000_000;
      if (left <= 0) break;
      try {
        response = provider.propose(shape, Duration.ofMillis(left));
        break;
      } catch (BackendException e) {
        if (e.status() != 503) throw e;
      }
    }
    if (response == null) throw new BackendException(503, "planner_unavailable");
    if (!response.keySet().equals(Set.of("nodes"))
        || !(response.get("nodes") instanceof List<?> rows)
        || rows.size() != source.size()) throw new BackendException(502, "planner_invalid_plan");
    List<WorkflowPlan.Node> nodes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object value : rows) {
      if (!(value instanceof Map<?, ?> row)
          || !row.keySet().equals(Set.of("id", "actionId", "params", "dependsOn"))
          || !(row.get("id") instanceof String id)
          || !seen.add(id)
          || !source.containsKey(id)
          || !(row.get("params") instanceof Map<?, ?> params)
          || !(row.get("dependsOn") instanceof List<?> dependencies))
        throw new BackendException(502, "planner_invalid_plan");
      var original = source.get(id);
      if (!original.actionId().equals(row.get("actionId"))
          || !params.keySet().equals(original.params().keySet())
          || dependencies.stream().anyMatch(d -> !(d instanceof String) || !source.containsKey(d))
          || !dependencies.containsAll(original.dependsOn())
          || dependencies.contains(id)) throw new BackendException(502, "planner_invalid_plan");
      Map<String, Object> bound = new LinkedHashMap<>();
      for (var entry : params.entrySet()) {
        String ref = "@" + id + ":" + entry.getKey();
        if (!ref.equals(entry.getValue()) || !values.containsKey(ref))
          throw new BackendException(502, "planner_untrusted_parameter");
        bound.put(entry.getKey().toString(), values.get(ref));
      }
      nodes.add(
          new WorkflowPlan.Node(
              id,
              original.actionId(),
              bound,
              dependencies.stream().map(Object::toString).toList()));
    }
    Set<String> ready = new HashSet<>();
    for (int i = 0; i < nodes.size(); i++)
      for (var node : nodes) if (ready.containsAll(node.dependsOn())) ready.add(node.id());
    if (ready.size() != nodes.size()) throw new BackendException(502, "planner_invalid_plan");
    // Full graph/schema/ownership/budget/permission validation follows in createOwned.
    return new WorkflowPlan(
        candidate.requestId(),
        nodes,
        candidate.trigger(),
        candidate.sourceEventId(),
        candidate.modelDegraded(),
        candidate.requireCompensation(),
        candidate.executionDeadline());
  }
}
