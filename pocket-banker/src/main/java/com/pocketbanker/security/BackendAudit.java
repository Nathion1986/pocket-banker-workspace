package com.pocketbanker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.BackendStore;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BackendAudit {
  private final BackendStore db;
  private final ObjectMapper mapper;
  private static final Set<String> ALLOWED =
      Set.of(
          "state",
          "risk",
          "reason",
          "generation",
          "attempt",
          "action",
          "version",
          "policyVersion",
          "providers",
          "degraded",
          "decision",
          "eventId",
          "notificationId",
          "operationHash",
          "factorCount",
          "traceId",
          "error",
          "modelCount");

  public void write(String user, String task, String node, String type, Map<String, ?> details) {
    Map<String, Object> safe = new LinkedHashMap<>();
    details.forEach(
        (k, v) -> {
          if (ALLOWED.contains(k) || Set.of("stage", "topology", "purpose").contains(k))
            safe.put(k, v);
        });
    safe.putIfAbsent("traceId", task == null ? UUID.randomUUID().toString() : task);
    try {
      db.update(
          "INSERT INTO backend_audit VALUES(?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          user,
          task,
          node,
          type,
          mapper.writeValueAsString(safe),
          db.now());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("audit_encoding_failed");
    }
  }

  public List<Map<String, Object>> trail(String user, int page) {
    return db.rows(
        "SELECT * FROM backend_audit WHERE user_id=? ORDER BY created_at DESC,id LIMIT 100 OFFSET ?",
        user,
        page * 100);
  }

  public List<Map<String, Object>> taskTrail(String user, String task, int page) {
    return db.rows(
        "SELECT * FROM backend_audit WHERE user_id=? AND task_id=? ORDER BY created_at DESC,id LIMIT 100 OFFSET ?",
        user,
        task,
        page * 100);
  }

  public void linkModelTrace(String user, String sourceTrace, String task) {
    for (var row :
        db.rows(
            "SELECT id,detail_json FROM backend_audit WHERE user_id=? AND task_id IS NULL AND detail_json LIKE ?",
            user,
            "%" + sourceTrace + "%")) {
      try {
        var details = mapper.readTree(BackendStore.text(row, "detail_json"));
        if (!sourceTrace.equals(details.path("traceId").asText())) continue;
        ((com.fasterxml.jackson.databind.node.ObjectNode) details).put("traceId", task);
        db.update(
            "UPDATE backend_audit SET task_id=?,detail_json=? WHERE id=? AND user_id=?",
            task,
            mapper.writeValueAsString(details),
            BackendStore.text(row, "id"),
            user);
      } catch (java.io.IOException e) {
        throw new IllegalStateException("audit_encoding_failed");
      }
    }
  }
}
