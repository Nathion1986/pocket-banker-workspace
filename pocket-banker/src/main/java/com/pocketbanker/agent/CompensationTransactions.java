package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.actions.workflow.FinancialActions;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.BackendAudit;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable inverse steps: a failed child rollback must never release its parent's budget. */
@Service
@RequiredArgsConstructor
public class CompensationTransactions {
  private final BackendStore db;
  private final TaskResultProjection resultProjection;
  private final WorkflowTransactions tasks;
  private final FinancialActions actions;
  private final ActionCatalog catalog;
  private final BackendAudit audit;

  public record Claim(
      String user,
      String task,
      String node,
      String action,
      String operation,
      Map<String, Object> input,
      long generation,
      int attempt,
      boolean reconcile,
      Instant windowUntil) {}

  @Transactional
  public Claim claim(String user, String task) {
    db.lockUser(user);
    var row =
        db.one("SELECT * FROM backend_tasks WHERE id=? AND user_id=? FOR UPDATE", task, user)
            .orElseThrow();
    if (!"COMPENSATING".equals(text(row, "state")) || tasks.hasUnresolved(task)) return null;
    var plan = tasks.plan(user, task);
    List<WorkflowPlan.Node> order = new ArrayList<>();
    Set<String> done = new HashSet<>();
    for (int i = 0; i < plan.nodes().size(); i++)
      for (var draft : plan.nodes())
        if (!done.contains(draft.id()) && done.containsAll(draft.dependsOn())) {
          order.add(draft);
          done.add(draft.id());
        }
    Collections.reverse(order);
    for (var draft : order) {
      var node =
          db.one("SELECT * FROM backend_nodes WHERE task_id=? AND id=?", task, draft.id())
              .orElseThrow();
      if (!Set.of("SUCCEEDED", "BANK_PENDING").contains(text(node, "state"))
          || catalog.require(draft.actionId()).readOnly()) continue;
      var input = tasks.input(user, node);
      var saved =
          db.one(
              "SELECT * FROM backend_compensations WHERE task_id=? AND node_id=? FOR UPDATE",
              task,
              draft.id());
      if (saved.isEmpty()) {
        Instant until = null;
        Long window =
            catalog.require(draft.actionId()).compensation() == null
                ? null
                : catalog.require(draft.actionId()).compensation().windowSeconds();
        if (window != null) {
          var completed =
              db.one(
                      "SELECT MAX(finished_at) AS completed FROM backend_attempts WHERE task_id=? AND node_id=? AND state='SUCCEEDED'",
                      task,
                      draft.id())
                  .orElseThrow();
          Instant at = time(completed, "completed");
          until = (at == null ? db.now() : at).plusSeconds(window);
        }
        db.update(
            "INSERT INTO backend_compensations(task_id,node_id,operation_key,state,window_until) VALUES(?,?,?,'PENDING',?)",
            task,
            draft.id(),
            text(node, "operation_key"),
            until);
        saved =
            db.one(
                "SELECT * FROM backend_compensations WHERE task_id=? AND node_id=?",
                task,
                draft.id());
      }
      var c = saved.orElseThrow();
      String state = text(c, "state");
      if (state.equals("COMPENSATED")) continue;
      // Serial inverse order also waits for unknown/failed compensation of a descendant.
      if (state.equals("MANUAL_REQUIRED")) return null;
      if (state.equals("EXECUTING")
          && time(c, "lease_until") != null
          && time(c, "lease_until").isAfter(db.now())) return null;
      if (time(c, "next_attempt_at") != null && time(c, "next_attempt_at").isAfter(db.now()))
        return null;
      boolean reconcile = Set.of("RECONCILING", "EXECUTING").contains(state);
      if (!actions.supportsCompensation(draft.actionId(), input)
          || number(c, "attempts") >= 6
          || (!reconcile
              && time(c, "window_until") != null
              && !time(c, "window_until").isAfter(db.now()))) {
        db.update(
            "UPDATE backend_compensations SET state='MANUAL_REQUIRED',error_code=? WHERE task_id=? AND node_id=?",
            !actions.supportsCompensation(draft.actionId(), input)
                ? "provider_compensation_unavailable"
                : "compensation_budget_exhausted",
            task,
            draft.id());
        return null;
      }
      long generation = number(c, "generation") + 1;
      int attempt = (int) number(c, "attempts") + 1;
      db.update(
          "UPDATE backend_compensations SET state='EXECUTING',generation=?,attempts=?,lease_until=? WHERE task_id=? AND node_id=?",
          generation,
          attempt,
          db.now().plusSeconds(30),
          task,
          draft.id());
      audit.write(
          user,
          task,
          draft.id(),
          "compensation_claimed",
          Map.of("generation", generation, "attempt", attempt));
      return new Claim(
          user,
          task,
          draft.id(),
          draft.actionId(),
          text(node, "operation_key"),
          input,
          generation,
          attempt,
          reconcile,
          time(c, "window_until"));
    }
    return null;
  }

  @Transactional
  public void complete(Claim claim, String state, String error) {
    db.lockUser(claim.user());
    var c =
        db.one(
                "SELECT * FROM backend_compensations WHERE task_id=? AND node_id=? FOR UPDATE",
                claim.task(),
                claim.node())
            .orElseThrow();
    if (number(c, "generation") != claim.generation() || !"EXECUTING".equals(text(c, "state")))
      return;
    db.update(
        "UPDATE backend_compensations SET state=?,error_code=?,lease_until=NULL,next_attempt_at=? WHERE task_id=? AND node_id=?",
        state,
        error,
        "RECONCILING".equals(state)
            ? db.now().plusSeconds(Math.min(60, 1L << claim.attempt()))
            : null,
        claim.task(),
        claim.node());
    resultProjection.refresh(claim.user(), claim.task());
    if (state.equals("COMPENSATED"))
      tasks.compensationResult(claim.user(), claim.task(), claim.node(), true, null);
    else
      audit.write(
          claim.user(),
          claim.task(),
          claim.node(),
          "compensation_result",
          Map.of("state", state, "error", error == null ? "none" : error));
  }

  @Transactional
  public void finish(String user, String task) {
    db.lockUser(user);
    if (!"COMPENSATING".equals(text(tasks.owned(user, task), "state")) || tasks.hasUnresolved(task))
      return;
    if (!db.rows(
            "SELECT node_id FROM backend_compensations WHERE task_id=? AND state IN ('PENDING','EXECUTING','RECONCILING')",
            task)
        .isEmpty()) return;
    tasks.finishCompensation(user, task);
  }
}
