package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.ActionCatalog;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.VaultCodec;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * One evolving observation per task; partial/unknown/refunded results never masquerade as success.
 */
@Service
@RequiredArgsConstructor
public class TaskResultProjection {
  private final BackendStore db;
  private final VaultCodec vault;
  private final ObjectMapper mapper;
  private final ActionCatalog catalog;

  public void refresh(String user, String task) {
    var row =
        db.one("SELECT state FROM backend_tasks WHERE user_id=? AND id=?", user, task)
            .orElseThrow();
    List<Map<String, Object>> results = new ArrayList<>();
    boolean financial = false;
    for (var n : db.rows("SELECT * FROM backend_nodes WHERE task_id=? ORDER BY id", task)) {
      Map<String, Object> observation = new LinkedHashMap<>();
      observation.put("nodeId", n.get("id"));
      observation.put("actionId", n.get("action_id"));
      observation.put("state", n.get("state"));
      if (n.get("output_cipher") != null) {
        var outcome = vault.map(user, text(n, "output_cipher"));
        for (String field :
            List.of("operationId", "amount", "currency", "orderId", "productId", "status"))
          if (outcome.containsKey(field)) observation.put(field, outcome.get(field));
        if (catalog.require(text(n, "action_id")).involvesMoney()
            && Set.of("SUCCEEDED", "COMPENSATED").contains(text(n, "state"))) financial = true;
      }
      if (n.get("error_code") != null) observation.put("error", n.get("error_code"));
      results.add(observation);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("taskId", task);
    body.put("taskState", row.get("state"));
    body.put("financialObserved", financial);
    body.put("results", results);
    body.put(
        "compensations",
        db.rows(
            "SELECT node_id,state,error_code FROM backend_compensations WHERE task_id=? ORDER BY node_id",
            task));
    String payload;
    try {
      payload = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("invalid_task_result_projection");
    }
    var prior = db.one("SELECT id FROM events WHERE user_id=? AND task_id=?", user, task);
    if (prior.isEmpty()) {
      String id = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO events(id,user_id,event_type,kind,title,occurred_at,source,confidence,extractor_version,status,created_at,expires_at,confirmed_at,task_id,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          user,
          financial ? "finance" : "system",
          "task_result",
          "任务执行状态与已核验结果",
          db.now(),
          "system",
          1.0,
          "executor-result-v2",
          "confirmed",
          db.now(),
          db.now().plusSeconds(31536000),
          db.now(),
          task,
          payload);
    } else
      db.update(
          "UPDATE events SET event_type=?,payload_json=?,occurred_at=? WHERE id=?",
          financial ? "finance" : "system",
          payload,
          db.now(),
          prior.get().get("id"));
  }
}
