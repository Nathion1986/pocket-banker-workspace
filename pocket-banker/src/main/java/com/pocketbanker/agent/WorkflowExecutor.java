package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.actions.workflow.FinancialActions;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class WorkflowExecutor {
  private final WorkflowTransactions tx;
  private final FinancialActions actions;
  private final BackendStore db;
  private final ActionCatalog catalog;
  private final ThreadPoolTaskExecutor executor;
  private final com.pocketbanker.orchestration.ModelCoordinator models;
  private final com.pocketbanker.security.BackendCircuit circuit;
  private final CompensationTransactions compensation;
  private final ThreadPoolTaskExecutor nodeWorkers;
  private final Set<String> running = ConcurrentHashMap.newKeySet();

  public WorkflowExecutor(
      WorkflowTransactions tx,
      FinancialActions actions,
      BackendStore db,
      ActionCatalog catalog,
      @Qualifier("workflowWorkers") ThreadPoolTaskExecutor executor,
      com.pocketbanker.orchestration.ModelCoordinator models,
      com.pocketbanker.security.BackendCircuit circuit,
      CompensationTransactions compensation,
      @Qualifier("workflowNodeWorkers") ThreadPoolTaskExecutor nodeWorkers) {
    this.tx = tx;
    this.actions = actions;
    this.db = db;
    this.catalog = catalog;
    this.executor = executor;
    this.models = models;
    this.circuit = circuit;
    this.compensation = compensation;
    this.nodeWorkers = nodeWorkers;
  }

  public void enqueue(String task) {
    if (!running.add(task)) return;
    try {
      executor.execute(
          () -> {
            try {
              run(task);
            } finally {
              running.remove(task);
            }
          });
    } catch (RuntimeException e) {
      running.remove(task); /* durable READY is picked up on next scheduler scan */
    }
  }

  public void run(String task) {
    if (db.one("SELECT id FROM backend_tasks WHERE id=?", task).isEmpty()) return;
    for (int round = 0; round < 30; round++) {
      var current = db.one("SELECT state,user_id FROM backend_tasks WHERE id=?", task);
      if (current.isEmpty()) return;
      if ("COMPENSATING".equals(text(current.get(), "state")) && !tx.hasUnresolved(task)) {
        compensate(text(current.get(), "user_id"), task);
        return;
      }
      List<WorkflowTransactions.Claim> batch = new ArrayList<>();
      for (int slot = 0; slot < 4; slot++) {
        try {
          var claim = tx.claim(task);
          if (claim == null) break;
          batch.add(claim);
        } catch (BackendException e) {
          break;
        }
      }
      if (batch.isEmpty()) {
        var row = db.one("SELECT state,user_id FROM backend_tasks WHERE id=?", task);
        if (row.isPresent()
            && "COMPENSATING".equals(text(row.get(), "state"))
            && !tx.hasUnresolved(task)) compensate(text(row.get(), "user_id"), task);
        return;
      }
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (var claim : batch) {
        try {
          futures.add(nodeWorkers.submit(() -> process(claim)));
        } catch (org.springframework.core.task.TaskRejectedException e) {
          tx.releaseUnsent(claim);
        }
      }
      for (var future : futures) {
        try {
          future.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (java.util.concurrent.ExecutionException e) {
          /* lease recovery retains unreported results */
        }
      }
    }
  }

  private void process(WorkflowTransactions.Claim claim) {
    try {
      if (!claim.reconcile()) {
        var spec = catalog.require(claim.action());
        if (spec.involvesMoney() || spec.risk().equals("RED")) {
          var review =
              models.reviewAction(
                  claim.user(),
                  "action-" + claim.task(),
                  Map.of("actionId", claim.action(), "params", claim.params()),
                  claim.task(),
                  claim.node());
          if (!tx.validateModelReview(claim, review.decision(), review.degraded())) {
            tx.releaseUnsent(claim);
            return;
          }
        }
      }
      Map<String, Object> result;
      if (claim.reconcile()) {
        try {
          result = actions.outcome(claim.user(), claim.operation(), claim.action(), claim.params());
        } catch (BackendException e) {
          if (e.status() == 404) {
            if (!tx.allowDispatch(claim)) {
              tx.complete(claim, null, "MANUAL_REQUIRED", "bank_absence_not_final_after_control");
              return;
            }
            result =
                actions.execute(claim.user(), claim.operation(), claim.action(), claim.params());
          } else throw e;
        }
      } else {
        if (!tx.allowDispatch(claim)) {
          tx.releaseUnsent(claim);
          return;
        }
        result = actions.execute(claim.user(), claim.operation(), claim.action(), claim.params());
      }
      String status = String.valueOf(result.get("status"));
      tx.complete(
          claim,
          result,
          status.equals("SUCCEEDED")
              ? "SUCCEEDED"
              : status.equals("PENDING") ? "BANK_PENDING" : "CANCELLED",
          null);

    } catch (BackendException e) {
      boolean unknown = e.status() >= 500;
      boolean readOnly = catalog.require(claim.action()).readOnly();
      boolean dispatched = tx.wasDispatched(claim);
      String failure =
          unknown
              ? (readOnly || !dispatched ? "RETRY_WAIT" : "RECONCILING")
              : claim.reconcile() ? "MANUAL_REQUIRED" : "FAILED";
      if (!unknown) circuit.failure(claim.user(), "business_failure");
      tx.complete(claim, null, failure, e.getMessage());

      return;
    } catch (RuntimeException e) {
      tx.complete(
          claim,
          null,
          tx.wasDispatched(claim) ? "RECONCILING" : "RETRY_WAIT",
          "execution_result_unknown");
      return;
    }
  }

  public void compensate(String user, String task) {
    for (int i = 0; i < 30; i++) {
      var claim = compensation.claim(user, task);
      if (claim == null) {
        compensation.finish(user, task);
        return;
      }
      try {
        Map<String, Object> result = null;
        if (claim.reconcile())
          result =
              actions.compensationOutcome(user, claim.operation(), claim.action(), claim.input());
        if (result == null || !"CANCELLED".equals(result.get("status"))) {
          if (claim.windowUntil() != null && !claim.windowUntil().isAfter(db.now()))
            throw new BackendException(409, "compensation_window_closed");
          result = actions.compensate(user, claim.operation(), claim.action(), claim.input());
        }
        if (!"CANCELLED".equals(result.get("status")))
          throw new BackendException(502, "compensation_not_verified");
        compensation.complete(claim, "COMPENSATED", null);
      } catch (BackendException e) {
        compensation.complete(
            claim, e.status() >= 500 ? "RECONCILING" : "MANUAL_REQUIRED", e.getMessage());
        compensation.finish(user, task);
        return;
      } catch (Exception e) {
        compensation.complete(claim, "RECONCILING", "compensation_result_unknown");
        return;
      }
    }
  }

  @Scheduled(
      fixedDelayString = "${backend.workflow-poll-ms:1000}",
      initialDelayString = "${backend.workflow-initial-ms:1000}")
  public void poll() {
    tx.expireConfirmations();
    for (String id : tx.due()) enqueue(id);
  }
}
