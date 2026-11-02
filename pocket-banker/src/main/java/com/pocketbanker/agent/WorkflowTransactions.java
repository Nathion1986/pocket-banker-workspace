package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowTransactions implements ConfirmedEventTrigger {
  private final BackendStore db;
  private final TaskResultProjection resultProjection;
  private final com.pocketbanker.profile.ProfileService profile;
  private final VaultCodec vault;
  private final ObjectMapper mapper;
  private final ActionCatalog catalog;
  private final ActionAvailability availability;
  private final BackendPermissionService permission;
  private final BackendAudit audit;
  private final com.pocketbanker.prediction.CashflowLedger cashflows;
  private final com.pocketbanker.shadow.ScopedShadowGateway shadow;

  @Value("${backend.otp-key:}")
  private String otpKey;

  @Value("${backend.mode:local-demo}")
  private String mode;

  public record Claim(
      String user,
      String task,
      String node,
      String action,
      Map<String, Object> params,
      String operation,
      long generation,
      int attempt,
      boolean reconcile) {}

  @Transactional
  public String createProposal(String user, String proposal, WorkflowPlan plan) {
    db.lockUser(user);
    var row =
        db.one(
                "SELECT * FROM backend_proposals WHERE user_id=? AND id=? FOR UPDATE",
                user,
                proposal)
            .orElseThrow(() -> new BackendException(404, "proposal_not_found"));
    if (!"pending".equals(text(row, "state")) || !time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "proposal_inactive");
    String task = create(user, plan);
    if ("tuition".equals(text(row, "kind")))
      cashflows.bind(user, text(row, "source_event_id"), task);
    db.update(
        "UPDATE backend_proposals SET state='confirmed',task_id=? WHERE user_id=? AND id=?",
        task,
        user,
        proposal);
    return task;
  }

  @Transactional
  public String create(String user, WorkflowPlan plan) {
    permission.requireUnlocked(user);
    if (number(
            db.one(
                    "SELECT COUNT(*) AS count FROM backend_tasks WHERE user_id=? AND created_at>?",
                    user,
                    db.now().minusSeconds(60))
                .orElseThrow(),
            "count")
        >= 40) throw new BackendException(429, "task_rate_limit");
    validate(plan, user);
    String hash = hash(plan);
    var prior =
        db.one(
            "SELECT * FROM backend_tasks WHERE user_id=? AND request_key=?",
            user,
            plan.requestId());
    if (prior.isPresent()) {
      if (!hash.equals(text(prior.get(), "input_hash")))
        throw new BackendException(409, "task_request_content_conflict");
      return text(prior.get(), "id");
    }
    String task = UUID.randomUUID().toString(), risk = "GREEN";
    for (var node : plan.nodes())
      risk =
          BackendPermissionService.max(
              risk,
              permission.risk(
                  user,
                  catalog.require(node.actionId()),
                  node.params(),
                  plan.modelDegraded(),
                  null));
    String state =
        risk.equals("GREEN")
            ? "READY"
            : risk.equals("RED") ? "WAITING_VERIFICATION" : "WAITING_CONFIRMATION";
    Instant next = next(plan.trigger());
    db.update(
        "INSERT INTO backend_tasks(id,user_id,request_key,input_hash,plan_cipher,state,required_risk,trigger_type,trigger_value,next_run_at,source_event_id,version,created_at,updated_at,expires_at,model_degraded) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        task,
        user,
        plan.requestId(),
        hash,
        vault.seal(user, plan),
        state,
        risk,
        plan.trigger().type(),
        plan.trigger().value(),
        next,
        plan.sourceEventId(),
        1,
        db.now(),
        db.now(),
        db.now().plusSeconds(3600),
        plan.modelDegraded());
    for (var node : plan.nodes())
      db.update(
          "INSERT INTO backend_nodes(id,task_id,action_id,input_cipher,depends_json,state,operation_key) VALUES(?,?,?,?,?,?,?)",
          node.id(),
          task,
          node.actionId(),
          vault.seal(user, node.params()),
          json(node.dependsOn()),
          "PENDING",
          task + ":" + node.id());
    profile.capturePersonReferences(user, "task", task, plan.nodes());
    if (risk.equals("GREEN")) authorize(task, risk, 1);
    if (risk.equals("RED")) challenge(task, user);
    audit.write(
        user, task, null, "task_created", Map.of("state", state, "risk", risk, "version", 1));
    return task;
  }

  public void validate(WorkflowPlan plan, String user) {
    if (plan.nodes() == null || plan.nodes().isEmpty() || plan.nodes().size() > 30)
      throw new BackendException(400, "invalid_plan_nodes");
    if (plan.trigger() != null
        && "event".equals(plan.trigger().type())
        && "coverage_hint".equals(plan.trigger().value())
        && healthUnsafe(plan))
      throw new BackendException(400, "health_purpose_excludes_financial_actions");
    Map<String, WorkflowPlan.Node> nodes = new LinkedHashMap<>();
    for (var node : plan.nodes()) {
      if (node.id() == null
          || !node.id().matches("[A-Za-z0-9_-]{1,40}")
          || nodes.put(node.id(), node) != null)
        throw new BackendException(400, "duplicate_or_invalid_node");
      var action = catalog.require(node.actionId());
      action.validate(node.params(), true);
      if (plan.trigger() == null || !action.triggers().contains(plan.trigger().type()))
        throw new BackendException(400, "unsupported_action_trigger");
      availability.require(user, action, node.params());
      if (plan.requireCompensation()
          && (action.involvesMoney() || action.risk().equals("RED"))
          && !availability.compensationSupported(action, node.params()))
        throw new BackendException(400, "plan_compensation_unavailable");
    }
    for (var node : plan.nodes()) {
      if (node.dependsOn() == null
          || node.dependsOn().size() > 30
          || !nodes.keySet().containsAll(node.dependsOn()))
        throw new BackendException(400, "unknown_dependency");
      for (var entry : node.params().entrySet()) {
        Object v = entry.getValue();
        if ((v instanceof Collection<?> values
                && values.stream()
                    .anyMatch(value -> value instanceof String ref && ref.startsWith("$")))
            || v instanceof Map<?, ?>)
          throw new BackendException(400, "nested_output_reference_not_supported");
        if (v instanceof String s && s.startsWith("$")) {
          String producer = s.substring(1).split("\\.")[0];
          String[] ref = s.substring(1).split("\\.");
          if (ref.length != 2 || !nodes.containsKey(producer))
            throw new BackendException(400, "invalid_output_reference");
          var producerSchema = catalog.require(nodes.get(producer).actionId()).outputSchema();
          var properties = (Map<String, Object>) producerSchema.get("properties");
          if (!properties.containsKey(ref[1]))
            throw new BackendException(400, "unknown_output_reference");
          var target =
              (Map<String, Object>)
                  ((Map<String, Object>)
                          catalog.require(node.actionId()).inputSchema().get("properties"))
                      .get(entry.getKey());
          var source = (Map<String, Object>) properties.get(ref[1]);
          Object sourceType = source.get("type");
          if (sourceType == null
              && source.get("enum") instanceof List<?> values
              && values.stream().allMatch(String.class::isInstance)) sourceType = "string";
          if (!Objects.equals(sourceType, target.get("type")))
            throw new BackendException(400, "reference_type_mismatch");
          if (!node.dependsOn().contains(producer))
            throw new BackendException(400, "reference_requires_dependency");
        }
      }
    }
    Set<String> completed = new HashSet<>();
    for (int round = 0; round < nodes.size(); round++)
      for (var node : nodes.values())
        if (completed.containsAll(node.dependsOn())) completed.add(node.id());
    if (completed.size() != nodes.size()) throw new BackendException(400, "plan_has_cycle");
    if (plan.sourceEventId() != null) {
      if (healthOrigin(user, plan.sourceEventId()) && healthUnsafe(plan))
        throw new BackendException(400, "health_purpose_excludes_financial_actions");
      var event =
          db.one(
                  "SELECT status,event_type,superseded_by FROM events WHERE id=? AND user_id=?",
                  plan.sourceEventId(),
                  user)
              .orElseThrow(() -> new BackendException(404, "source_event_not_found"));
      if (!"confirmed".equals(text(event, "status"))
          || "prediction".equals(text(event, "event_type"))
          || text(event, "superseded_by") != null)
        throw new BackendException(409, "confirmed_fact_required");
    }
    Map<String, java.math.BigDecimal> budgets = new HashMap<>();
    for (var node : plan.nodes())
      if (node.actionId().equals("reserve"))
        budgets.put(node.id(), Money.positive(node.params().get("amount")));
    Map<String, java.math.BigDecimal> allocated = new HashMap<>();
    for (var node : plan.nodes())
      if (Set.of("order_flower", "order_cake", "release_budget").contains(node.actionId())) {
        Object value = node.params().get("reservationId");
        if (!(value instanceof String reference)
            || !reference.matches("\\$[A-Za-z0-9_-]+\\.reservationId"))
          throw new BackendException(400, "reservation_must_reference_same_plan");
        String parent = reference.substring(1).split("\\.")[0];
        if (!budgets.containsKey(parent))
          throw new BackendException(400, "reservation_producer_required");
        if (node.params().containsKey("amount"))
          allocated.merge(
              parent, Money.positive(node.params().get("amount")), java.math.BigDecimal::add);
      }
    allocated.forEach(
        (id, amount) -> {
          if (amount.compareTo(budgets.get(id)) > 0)
            throw new BackendException(400, "plan_exceeds_reserved_budget");
        });
    Instant scheduled = next(plan.trigger());
    if (plan.executionDeadline() != null) {
      Instant deadline;
      try {
        deadline = Instant.parse(plan.executionDeadline());
      } catch (Exception e) {
        throw new BackendException(400, "invalid_execution_deadline");
      }
      if (!deadline.isAfter(db.now()))
        throw new BackendException(400, "execution_deadline_expired");
      if (scheduled != null && scheduled.isAfter(deadline))
        throw new BackendException(400, "plan_deadline_before_trigger");
    }
  }

  private boolean healthUnsafe(WorkflowPlan plan) {
    return plan.nodes() != null
        && plan.nodes().stream()
            .anyMatch(
                node -> {
                  var action = catalog.require(node.actionId());
                  return action.involvesMoney()
                      || !action.readOnly() && !"reminder".equals(action.id());
                });
  }

  private boolean healthOrigin(String user, String event) {
    return db.one(
            "SELECT e.kind,s.kind AS source_kind FROM events e LEFT JOIN perception_log r ON r.id=e.receipt_id LEFT JOIN perception_sources s ON s.id=r.source_id WHERE e.id=? AND e.user_id=?",
            event,
            user)
        .map(
            row ->
                "coverage_hint".equals(row.get("kind")) || "HEALTH".equals(row.get("source_kind")))
        .orElse(false);
  }

  private Instant next(WorkflowPlan.Trigger trigger) {
    try {
      return switch (trigger.type()) {
        case "immediate" -> db.now();
        case "at" -> {
          Instant value = Instant.parse(trigger.value());
          if (value.isBefore(db.now().minusSeconds(5))) throw new IllegalArgumentException();
          yield value;
        }
        case "recurring" -> {
          var cron = CronExpression.parse(trigger.value());
          var due = cron.next(db.now().atZone(ZoneId.of("Asia/Shanghai")));
          if (due == null) throw new IllegalArgumentException();
          yield due.toInstant();
        }
        case "event" -> {
          if (trigger.value() == null || !trigger.value().matches("[A-Za-z0-9_.-]{1,80}"))
            throw new IllegalArgumentException();
          yield null;
        }
        default -> throw new IllegalArgumentException();
      };
    } catch (Exception e) {
      throw new BackendException(400, "invalid_trigger");
    }
  }

  @Transactional
  public Map<String, Object> confirm(String user, String task, boolean approved, long version) {
    var row = lock(user, task);
    permission.requireUnlocked(user);
    if (number(row, "version") != version) throw new BackendException(409, "task_snapshot_changed");
    String state = text(row, "state");
    if (!approved) {
      if (Set.of("WAITING_CONFIRMATION", "WAITING_VERIFICATION", "READY", "SCHEDULED")
          .contains(state)) cancelPending(task);
      return view(user, task);
    }
    if (Set.of("READY", "SCHEDULED", "RUNNING", "SUCCEEDED").contains(state))
      return view(user, task);
    if (!"WAITING_CONFIRMATION".equals(state) || !"YELLOW".equals(text(row, "required_risk")))
      throw new BackendException(409, "strong_verification_required");
    if (!time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "confirmation_expired");
    authorize(task, "YELLOW", version);
    audit.write(
        user, task, null, "confirmation", Map.of("decision", "approved", "version", version));
    return view(user, task);
  }

  @Transactional
  public Map<String, Object> verify(
      String user, String task, String code, String factor, long version) {
    if (!"local-demo".equals(mode)) throw new BackendException(404, "demo_verification_disabled");
    var row = lock(user, task);
    permission.requireUnlocked(user);
    if (number(row, "version") != version) throw new BackendException(409, "task_snapshot_changed");
    if (Set.of("READY", "RUNNING", "SUCCEEDED", "SCHEDULED").contains(text(row, "state"))
        && "RED".equals(text(row, "approved_risk"))) return view(user, task);
    if (!"WAITING_VERIFICATION".equals(text(row, "state")))
      throw new BackendException(409, "task_not_awaiting_verification");
    if (!time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "verification_expired");
    boolean first =
        code != null
            && PerceptionAccess.equal(text(row, "challenge_hash"), PerceptionAccess.hash(code));
    boolean second =
        factor != null
            && PerceptionAccess.equal(
                text(row, "second_factor_hash"), PerceptionAccess.hash(factor));
    if (!first || !second) {
      db.update(
          "UPDATE backend_tasks SET verify_failures=verify_failures+1,updated_at=? WHERE id=?",
          db.now(),
          task);
      db.update(
          "UPDATE backend_users SET failures=failures+1,locked_until=CASE WHEN failures+1>=5 THEN ? ELSE locked_until END,updated_at=? WHERE user_id=?",
          db.now().plusSeconds(60),
          db.now(),
          user);
      audit.write(
          user,
          task,
          null,
          "verification_failed",
          Map.of("factorCount", (first ? 1 : 0) + (second ? 1 : 0)));
      if (number(row, "verify_failures") + 1 >= 5) {
        challenge(task, user);
        audit.write(user, task, null, "verification_rotated", Map.of("reason", "attempt_limit"));
      }
      if (number(db.lockUser(user), "failures") >= 5)
        audit.write(
            user, task, null, "circuit_opened", Map.of("reason", "verification_attempt_limit"));
      return Map.of(
          "error", "verification_failed", "taskId", task, "state", "WAITING_VERIFICATION");
    }
    db.update("UPDATE backend_tasks SET passed_factors=2 WHERE id=?", task);
    authorize(task, "RED", version);
    audit.write(user, task, null, "verification", Map.of("decision", "verified", "factorCount", 2));
    return view(user, task);
  }

  public Map<String, Object> demoChallenge(String user, String task) {
    if (!mode.equals("local-demo")) throw new BackendException(404, "demo_verification_disabled");
    var row = owned(user, task);
    if (!"WAITING_VERIFICATION".equals(text(row, "state")))
      throw new BackendException(409, "task_not_awaiting_verification");
    String id = text(row, "challenge_id");
    return Map.of(
        "challengeId",
        id,
        "code",
        otp(id, "sms"),
        "secondFactor",
        otp(id, "device"),
        "mode",
        "local-demo",
        "expiresAt",
        time(row, "expires_at").toString());
  }

  /** Package-private: the App facade has verified and consumed both factors in this transaction. */
  Map<String, Object> verifyApplication(String user, String task, long version) {
    requireApplicationVerification(user, task, version);
    db.update("UPDATE backend_tasks SET passed_factors=2 WHERE id=?", task);
    authorize(task, "RED", version);
    audit.write(
        user,
        task,
        null,
        "verification",
        Map.of("decision", "verified", "factorCount", 2, "method", "password_totp"));
    return view(user, task);
  }

  Map<String, Object> requireApplicationVerification(String user, String task, long version) {
    var row = lock(user, task);
    permission.requireUnlocked(user);
    if (number(row, "version") != version) throw new BackendException(409, "task_snapshot_changed");
    if (!"WAITING_VERIFICATION".equals(text(row, "state"))
        || !"RED".equals(text(row, "required_risk")))
      throw new BackendException(409, "task_not_awaiting_verification");
    if (!time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "verification_expired");
    return row;
  }

  private void challenge(String task, String user) {
    if (otpKey.isBlank()) throw new BackendException(503, "otp_adapter_not_configured");
    String id = UUID.randomUUID().toString();
    db.update(
        "UPDATE backend_tasks SET challenge_id=?,challenge_hash=?,second_factor_hash=?,expires_at=?,passed_factors=0,verify_failures=0 WHERE id=?",
        id,
        PerceptionAccess.hash(otp(id, "sms")),
        PerceptionAccess.hash(otp(id, "device")),
        db.now().plusSeconds(300),
        task);
  }

  private String otp(String id, String kind) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(otpKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] b = m.doFinal((id + ":" + kind).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(b).substring(0, 12);
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }

  private void authorize(String task, String risk, long version) {
    var row =
        db.one("SELECT trigger_type,next_run_at FROM backend_tasks WHERE id=?", task).orElseThrow();
    String state = "immediate".equals(text(row, "trigger_type")) ? "READY" : "SCHEDULED";
    db.update(
        "UPDATE backend_tasks SET state=?,approved_risk=?,approved_version=?,authorization_until=?,updated_at=? WHERE id=?",
        state,
        risk,
        version,
        db.now().plusSeconds(900),
        db.now(),
        task);
  }

  @Transactional
  public Claim claim(String task) {
    var initial =
        db.one("SELECT * FROM backend_tasks WHERE id=?", task)
            .orElseThrow(() -> new BackendException(404, "task_not_found"));
    String user = text(initial, "user_id");
    permission.requireUnlocked(user);
    var row = lock(user, task);
    if (!Set.of(
            "READY",
            "RUNNING",
            "RETRY_WAIT",
            "RECONCILING",
            "SCHEDULED",
            "PAUSED",
            "CANCELLING",
            "COMPENSATING")
        .contains(text(row, "state"))) return null;
    boolean restricted =
        !"RUN".equals(text(row, "desired_control")) || "COMPENSATING".equals(text(row, "state"));
    Instant due = time(row, "next_run_at");
    if (due == null || due.isAfter(db.now())) return null;
    List<Map<String, Object>> nodes =
        db.rows("SELECT * FROM backend_nodes WHERE task_id=? ORDER BY id", task);
    Set<String> done = new HashSet<>();
    nodes.stream()
        .filter(n -> "SUCCEEDED".equals(text(n, "state")))
        .forEach(n -> done.add(text(n, "id")));
    if (done.size() == nodes.size()) {
      if (!restricted) finishTask(row);
      return null;
    }
    for (var n : nodes) {
      String node = text(n, "id"), state = text(n, "state"), operation = text(n, "operation_key");
      if (state.equals("EXECUTING")) {
        Instant lease = time(n, "lease_until");
        if (lease != null && lease.isAfter(db.now())) continue;
        state = Boolean.TRUE.equals(n.get("dispatch_started")) ? "RECONCILING" : "PENDING";
      }
      if (!Set.of("PENDING", "RETRY_WAIT", "RECONCILING", "BANK_PENDING").contains(state)) continue;
      if (restricted && !Set.of("RECONCILING", "BANK_PENDING").contains(state)) continue;
      Instant retry = time(n, "next_attempt_at");
      if (retry != null && retry.isAfter(db.now())) continue;
      if (!done.containsAll(stringList(text(n, "depends_json")))) continue;
      var params = resolve(user, task, vault.map(user, text(n, "input_cipher")));
      var spec = catalog.require(text(n, "action_id"));
      if (spec.involvesMoney()
          && nodes.stream()
              .anyMatch(
                  other ->
                      !node.equals(text(other, "id"))
                          && Set.of("EXECUTING", "RECONCILING", "BANK_PENDING")
                              .contains(text(other, "state"))
                          && catalog.require(text(other, "action_id")).involvesMoney())) continue;
      spec.validate(params, false);
      String risk;
      try {
        if (!Set.of("RECONCILING", "BANK_PENDING").contains(state))
          availability.require(user, spec, params);
        risk =
            Set.of("RECONCILING", "BANK_PENDING").contains(state)
                ? Objects.toString(row.get("approved_risk"), "GREEN")
                : permission.risk(
                    user, spec, params, (Boolean) row.get("model_degraded"), operation);
      } catch (BackendException failure) {
        db.update(
            "UPDATE backend_nodes SET state='FAILED',error_code=? WHERE task_id=? AND id=?",
            failure.getMessage(),
            task,
            node);
        db.update(
            "UPDATE backend_tasks SET state='COMPENSATING',updated_at=? WHERE id=?",
            db.now(),
            task);
        return null;
      }
      if (!Set.of("RECONCILING", "BANK_PENDING").contains(state)
          && text(row, "source_event_id") != null
          && db.one(
                  "SELECT id FROM events WHERE id=? AND user_id=? AND status='confirmed' AND superseded_by IS NULL",
                  text(row, "source_event_id"),
                  user)
              .isEmpty()) {
        db.update(
            "UPDATE backend_tasks SET state='WAITING_USER',approved_risk=NULL WHERE id=?", task);
        return null;
      }
      if (!Set.of("RECONCILING", "BANK_PENDING").contains(state)
          && BackendPermissionService.rank(risk) > 0
          && (text(row, "approved_risk") == null
              || BackendPermissionService.rank(risk)
                  > BackendPermissionService.rank(text(row, "approved_risk"))
              || time(row, "authorization_until") == null
              || !time(row, "authorization_until").isAfter(db.now())
              || number(row, "version") != number(row, "approved_version"))) {
        db.update(
            "UPDATE backend_nodes SET input_cipher=? WHERE task_id=? AND id=?",
            vault.seal(user, params),
            task,
            node);
        String wait = risk.equals("RED") ? "WAITING_VERIFICATION" : "WAITING_CONFIRMATION";
        db.update(
            "UPDATE backend_tasks SET state=?,required_risk=?,approved_risk=NULL,updated_at=?,expires_at=? WHERE id=?",
            wait,
            risk,
            db.now(),
            db.now().plusSeconds(3600),
            task);
        if (risk.equals("RED")) challenge(task, user);
        audit.write(user, task, node, "reauthorization_required", Map.of("risk", risk));
        return null;
      }
      if (!Set.of("RECONCILING", "BANK_PENDING").contains(state))
        permission.reserve(user, operation, spec, params);
      long generation = number(n, "generation") + 1;
      int attempt = (int) number(n, "attempts") + 1;
      db.update(
          "UPDATE backend_nodes SET state='EXECUTING',lease_until=?,generation=?,attempts=?,input_cipher=? WHERE task_id=? AND id=?",
          db.now().plusSeconds(30),
          generation,
          attempt,
          vault.seal(user, params),
          task,
          node);
      db.update("UPDATE backend_tasks SET state='RUNNING',updated_at=? WHERE id=?", db.now(), task);
      db.update(
          "INSERT INTO backend_attempts VALUES(?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          task,
          node,
          attempt,
          "started",
          null,
          db.now(),
          null);
      audit.write(
          user,
          task,
          node,
          "node_claimed",
          Map.of("action", spec.id(), "generation", generation, "attempt", attempt, "risk", risk));
      return new Claim(
          user,
          task,
          node,
          spec.id(),
          params,
          operation,
          generation,
          attempt,
          Set.of("RECONCILING", "BANK_PENDING").contains(state));
    }
    return null;
  }

  @Transactional
  public boolean validateModelReview(Claim claim, String decision, boolean degraded) {
    var row = lock(claim.user(), claim.task());
    var node =
        db.one(
                "SELECT * FROM backend_nodes WHERE task_id=? AND id=? FOR UPDATE",
                claim.task(),
                claim.node())
            .orElseThrow();
    if (number(node, "generation") != claim.generation()
        || !"RUN".equals(text(row, "desired_control"))
        || !"RUNNING".equals(text(row, "state"))) return false;
    if (!decision.equals("confirm")) {
      permission.settle(
          claim.user(), claim.operation(), catalog.require(claim.action()), claim.params(), false);
      db.update(
          "UPDATE backend_nodes SET state='MANUAL_REQUIRED',lease_until=NULL,error_code='model_review_not_confirmed' WHERE task_id=? AND id=?",
          claim.task(),
          claim.node());
      db.update(
          "UPDATE backend_tasks SET state='MANUAL_REQUIRED',updated_at=? WHERE id=?",
          db.now(),
          claim.task());
      return false;
    }
    if (degraded && !Boolean.TRUE.equals(row.get("model_degraded"))) {
      db.update("UPDATE backend_tasks SET model_degraded=TRUE WHERE id=?", claim.task());
      String required =
          permission.risk(
              claim.user(),
              catalog.require(claim.action()),
              claim.params(),
              true,
              claim.operation());
      if (text(row, "approved_risk") == null
          || BackendPermissionService.rank(required)
              > BackendPermissionService.rank(text(row, "approved_risk"))) {
        permission.settle(
            claim.user(),
            claim.operation(),
            catalog.require(claim.action()),
            claim.params(),
            false);
        db.update(
            "UPDATE backend_nodes SET state='PENDING',lease_until=NULL WHERE task_id=? AND id=?",
            claim.task(),
            claim.node());
        db.update(
            "UPDATE backend_tasks SET state=?,required_risk=?,approved_risk=NULL WHERE id=?",
            required.equals("RED") ? "WAITING_VERIFICATION" : "WAITING_CONFIRMATION",
            required,
            claim.task());
        if (required.equals("RED")) challenge(claim.task(), claim.user());
        return false;
      }
    }
    return true;
  }

  public boolean wasDispatched(Claim claim) {
    return db.one(
            "SELECT dispatch_started FROM backend_nodes WHERE task_id=? AND id=?",
            claim.task(),
            claim.node())
        .map(n -> Boolean.TRUE.equals(n.get("dispatch_started")))
        .orElse(false);
  }

  @Transactional
  public boolean allowDispatch(Claim claim) {
    var row = lock(claim.user(), claim.task());
    var node =
        db.one(
                "SELECT * FROM backend_nodes WHERE task_id=? AND id=? FOR UPDATE",
                claim.task(),
                claim.node())
            .orElseThrow();
    if (number(node, "generation") != claim.generation()
        || !"EXECUTING".equals(text(node, "state"))) return false;
    if (!"RUN".equals(text(row, "desired_control")) || !"RUNNING".equals(text(row, "state")))
      return false;
    if (!time(node, "lease_until").isAfter(db.now())) return false;
    if (text(row, "source_event_id") != null
        && db.one(
                "SELECT id FROM events WHERE id=? AND user_id=? AND status='confirmed' AND superseded_by IS NULL",
                text(row, "source_event_id"),
                claim.user())
            .isEmpty()) {
      db.update(
          "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,authorization_until=NULL WHERE id=?",
          claim.task());
      return false;
    }
    if (!profile.personReferencesCurrent(claim.user(), "task", claim.task())) {
      db.update(
          "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,authorization_until=NULL WHERE id=?",
          claim.task());
      return false;
    }
    var draft = plan(claim.user(), claim.task());
    if (draft.sourceEventId() != null
        && healthOrigin(claim.user(), draft.sourceEventId())
        && healthUnsafe(draft)) {
      db.update(
          "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,authorization_until=NULL WHERE id=?",
          claim.task());
      return false;
    }
    if (draft.executionDeadline() != null
        && !Instant.parse(draft.executionDeadline()).isAfter(db.now()))
      throw new BackendException(409, "execution_deadline_expired");
    if (!cashflows.paymentAmountCurrent(
        claim.user(), claim.task(), claim.node(), claim.params().get("amount"))) {
      db.update(
          "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,authorization_until=NULL WHERE id=?",
          claim.task());
      return false;
    }
    var spec = catalog.require(claim.action());
    availability.require(claim.user(), spec, claim.params());
    String risk =
        permission.risk(
            claim.user(),
            spec,
            claim.params(),
            (Boolean) row.get("model_degraded"),
            claim.operation());
    if (BackendPermissionService.rank(risk) > 0
        && (text(row, "approved_risk") == null
            || BackendPermissionService.rank(risk)
                > BackendPermissionService.rank(text(row, "approved_risk"))
            || number(row, "approved_version") != number(row, "version")
            || time(row, "authorization_until") == null
            || !time(row, "authorization_until").isAfter(db.now())))
      throw new BackendException(409, "dispatch_authorization_expired");
    permission.reserve(claim.user(), claim.operation(), spec, claim.params());
    db.update(
        "UPDATE backend_nodes SET dispatch_started=TRUE WHERE task_id=? AND id=?",
        claim.task(),
        claim.node());
    return true;
  }

  @Transactional
  public void releaseUnsent(Claim claim) {
    var row = lock(claim.user(), claim.task());
    var node =
        db.one("SELECT * FROM backend_nodes WHERE task_id=? AND id=?", claim.task(), claim.node())
            .orElseThrow();
    if (number(node, "generation") != claim.generation()
        || !"EXECUTING".equals(text(node, "state"))) return;
    boolean sent = Boolean.TRUE.equals(node.get("dispatch_started"));
    String state =
        sent
            ? "RECONCILING"
            : "CANCEL".equals(text(row, "desired_control")) ? "CANCELLED" : "PENDING";
    db.update(
        "UPDATE backend_nodes SET state=?,lease_until=NULL,next_attempt_at=? WHERE task_id=? AND id=?",
        state,
        db.now(),
        claim.task(),
        claim.node());
    if (!sent)
      permission.settle(
          claim.user(), claim.operation(), catalog.require(claim.action()), claim.params(), false);
    if ("CANCEL".equals(text(row, "desired_control")))
      db.update(
          "UPDATE backend_tasks SET state=? WHERE id=?",
          sent ? "CANCELLING" : "COMPENSATING",
          claim.task());
  }

  @Transactional
  public void complete(Claim claim, Map<String, Object> output, String state, String error) {
    var task = lock(claim.user(), claim.task());
    var node =
        db.one(
                "SELECT * FROM backend_nodes WHERE task_id=? AND id=? FOR UPDATE",
                claim.task(),
                claim.node())
            .orElseThrow();
    if (number(node, "generation") != claim.generation()
        || !"EXECUTING".equals(text(node, "state"))) return;
    Instant retry =
        Set.of("RETRY_WAIT", "RECONCILING", "BANK_PENDING").contains(state)
            ? db.now().plusSeconds(Math.min(60, 1L << Math.min(claim.attempt(), 5)))
            : null;
    if (claim.attempt() > 5 && Set.of("RETRY_WAIT", "RECONCILING").contains(state))
      state = "MANUAL_REQUIRED";
    db.update(
        "UPDATE backend_nodes SET state=?,output_cipher=?,lease_until=NULL,next_attempt_at=?,error_code=? WHERE task_id=? AND id=?",
        state,
        output == null ? null : vault.seal(claim.user(), output),
        retry,
        error,
        claim.task(),
        claim.node());
    db.update(
        "UPDATE backend_attempts SET state=?,error_code=?,finished_at=? WHERE task_id=? AND node_id=? AND attempt_number=?",
        state,
        error,
        db.now(),
        claim.task(),
        claim.node(),
        claim.attempt());
    var spec = catalog.require(claim.action());
    if (state.equals("SUCCEEDED"))
      permission.settle(claim.user(), claim.operation(), spec, claim.params(), true);
    if (Set.of("FAILED", "CANCELLED").contains(state))
      permission.settle(claim.user(), claim.operation(), spec, claim.params(), false);
    String taskState =
        state.equals("FAILED")
            ? "COMPENSATING"
            : state.equals("MANUAL_REQUIRED") ? "MANUAL_REQUIRED" : "RUNNING";
    String concurrentControl = text(task, "state");
    if (concurrentControl.equals("PAUSED")) taskState = "PAUSED";
    if (concurrentControl.equals("MANUAL_REQUIRED")) taskState = "MANUAL_REQUIRED";
    if (concurrentControl.equals("COMPENSATING")) taskState = "COMPENSATING";
    if ("CANCEL".equals(text(task, "desired_control")))
      taskState = state.equals("RECONCILING") ? "CANCELLING" : "COMPENSATING";
    db.update(
        "UPDATE backend_tasks SET state=?,updated_at=? WHERE id=?",
        taskState,
        db.now(),
        claim.task());
    audit.write(
        claim.user(),
        claim.task(),
        claim.node(),
        "node_result",
        Map.of("state", state, "error", error == null ? "none" : error));
    cashflows.verifiedTask(claim.user(), claim.task());
    resultProjection.refresh(claim.user(), claim.task());
    if (state.equals("SUCCEEDED")
        && "RUN".equals(text(task, "desired_control"))
        && !Set.of("MANUAL_REQUIRED", "PAUSED", "COMPENSATING").contains(concurrentControl)
        && db.rows(
                "SELECT id FROM backend_nodes WHERE task_id=? AND state<>'SUCCEEDED'", claim.task())
            .isEmpty()) finishTask(task);
  }

  private void finishTask(Map<String, Object> row) {
    String task = text(row, "id"), user = text(row, "user_id");
    db.update("UPDATE backend_tasks SET state='SUCCEEDED',updated_at=? WHERE id=?", db.now(), task);
    if ("recurring".equals(text(row, "trigger_type"))) {
      Instant next =
          CronExpression.parse(text(row, "trigger_value"))
              .next(db.now().atZone(ZoneId.of("Asia/Shanghai")))
              .toInstant();
      db.update("UPDATE backend_tasks SET next_run_at=? WHERE id=?", next, task);
    }
    resultProjection.refresh(user, task);
    cashflows.verifiedTask(user, task);
    audit.write(user, task, null, "task_completed", Map.of("state", "SUCCEEDED"));
  }

  @Transactional
  public Map<String, Object> control(
      String user, String task, String command, String value, long version) {
    var row = lock(user, task);
    if (number(row, "version") != version) throw new BackendException(409, "task_snapshot_changed");
    String state = text(row, "state");
    if (Set.of("resume", "retry").contains(command)
        && "MANUAL_REQUIRED".equals(state)
        && !db.rows(
                "SELECT node_id FROM backend_compensations WHERE task_id=? AND state='MANUAL_REQUIRED'",
                task)
            .isEmpty()) {
      db.update(
          "UPDATE backend_compensations SET state='RECONCILING',attempts=0,generation=generation+1,lease_until=NULL,next_attempt_at=? WHERE task_id=? AND state='MANUAL_REQUIRED'",
          db.now(),
          task);
      db.update(
          "UPDATE backend_tasks SET state='COMPENSATING',desired_control='CANCEL',updated_at=? WHERE id=?",
          db.now(),
          task);
      audit.write(
          user,
          task,
          null,
          "task_control",
          Map.of("decision", command, "reason", "resume_compensation_only"));
      return view(user, task);
    }
    if ("SUCCEEDED".equals(state)
        && "recurring".equals(text(row, "trigger_type"))
        && row.get("parent_id") == null
        && Set.of("cancel", "pause", "resume").contains(command)) {
      if (command.equals("resume") && !"PAUSE".equals(text(row, "desired_control")))
        throw new BackendException(409, "task_cannot_resume");
      String desired =
          command.equals("resume") ? "RUN" : command.equals("pause") ? "PAUSE" : "CANCEL";
      Instant next =
          command.equals("resume")
              ? CronExpression.parse(text(row, "trigger_value"))
                  .next(db.now().atZone(ZoneId.of("Asia/Shanghai")))
                  .toInstant()
              : command.equals("cancel") ? null : time(row, "next_run_at");
      db.update(
          "UPDATE backend_tasks SET desired_control=?,next_run_at=?,updated_at=? WHERE id=?",
          desired,
          next,
          db.now(),
          task);
      if (command.equals("cancel"))
        for (var child :
            db.rows(
                "SELECT id,version FROM backend_tasks WHERE parent_id=? AND state NOT IN ('SUCCEEDED','CANCELLED','COMPENSATED','FAILED')",
                task)) control(user, text(child, "id"), "cancel", null, number(child, "version"));
      audit.write(
          user,
          task,
          null,
          "task_control",
          Map.of("decision", command, "reason", "future_recurring_rounds"));
      return view(user, task);
    }
    if (command.equals("cancel")) {
      if (Set.of("SUCCEEDED", "CANCELLED", "COMPENSATED").contains(state)) return view(user, task);
      boolean inFlight =
          db
              .rows(
                  "SELECT action_id,state,dispatch_started FROM backend_nodes WHERE task_id=?",
                  task)
              .stream()
              .anyMatch(
                  n ->
                      Set.of("EXECUTING", "RECONCILING").contains(text(n, "state"))
                          || ("MANUAL_REQUIRED".equals(text(n, "state"))
                              && Boolean.TRUE.equals(n.get("dispatch_started"))
                              && !catalog.require(text(n, "action_id")).readOnly()));
      if (inFlight)
        for (var n :
            db.rows(
                "SELECT id,action_id,dispatch_started FROM backend_nodes WHERE task_id=? AND state='MANUAL_REQUIRED'",
                task))
          if (Boolean.TRUE.equals(n.get("dispatch_started"))
              && !catalog.require(text(n, "action_id")).readOnly())
            db.update(
                "UPDATE backend_nodes SET state='RECONCILING',generation=generation+1,next_attempt_at=? WHERE task_id=? AND id=?",
                db.now(),
                task,
                text(n, "id"));
      db.update(
          "UPDATE backend_tasks SET state=?,desired_control='CANCEL',updated_at=? WHERE id=?",
          inFlight ? "CANCELLING" : "COMPENSATING",
          db.now(),
          task);
      if (db.rows(
                  "SELECT id FROM backend_nodes WHERE task_id=? AND state IN ('SUCCEEDED','BANK_PENDING')",
                  task)
              .isEmpty()
          && !inFlight) cancelPending(task);
    } else if (command.equals("pause")) {
      if (!Set.of("SUCCEEDED", "CANCELLED", "COMPENSATED", "FAILED").contains(state))
        db.update(
            "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',updated_at=? WHERE id=?",
            db.now(),
            task);
    } else if (command.equals("resume")) {
      if (!Set.of("PAUSED", "MANUAL_REQUIRED").contains(state))
        throw new BackendException(409, "task_cannot_resume");
      db.update(
          "UPDATE backend_nodes SET state=CASE WHEN error_code='model_review_not_confirmed' THEN 'PENDING' ELSE 'RECONCILING' END,next_attempt_at=?,generation=generation+1 WHERE task_id=? AND state IN ('MANUAL_REQUIRED','EXECUTING')",
          db.now(),
          task);
      db.update(
          "UPDATE backend_tasks SET state='READY',desired_control='RUN',next_run_at=?,updated_at=? WHERE id=?",
          db.now(),
          db.now(),
          task);
    } else if (command.equals("manual")) {
      if (Set.of("SUCCEEDED", "COMPENSATED", "CANCELLED").contains(state))
        throw new BackendException(409, "task_already_finished");
      db.update(
          "UPDATE backend_tasks SET state='MANUAL_REQUIRED',desired_control='PAUSE',updated_at=? WHERE id=?",
          db.now(),
          task);
    } else if (command.equals("reschedule")) {
      if (!db.rows("SELECT id FROM backend_nodes WHERE task_id=? AND state<>'PENDING'", task)
          .isEmpty()) throw new BackendException(409, "task_already_started");
      Instant date;
      try {
        date = Instant.parse(value);
        if (date.isBefore(db.now())) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new BackendException(400, "invalid_trigger");
      }
      WorkflowPlan oldPlan = plan(user, task);
      var updated =
          new WorkflowPlan(
              oldPlan.requestId(),
              oldPlan.nodes(),
              new WorkflowPlan.Trigger("at", value),
              oldPlan.sourceEventId(),
              oldPlan.modelDegraded(),
              oldPlan.requireCompensation(),
              oldPlan.executionDeadline());
      String risk = text(row, "required_risk");
      db.update(
          "UPDATE backend_tasks SET trigger_type='at',trigger_value=?,next_run_at=?,plan_cipher=?,version=version+1,state=?,approved_risk=NULL,approved_version=NULL,authorization_until=NULL,desired_control='RUN',expires_at=?,updated_at=? WHERE id=?",
          value,
          date,
          vault.seal(user, updated),
          risk.equals("RED")
              ? "WAITING_VERIFICATION"
              : risk.equals("GREEN") ? "SCHEDULED" : "WAITING_CONFIRMATION",
          db.now().plusSeconds(3600),
          db.now(),
          task);
      if (risk.equals("RED")) challenge(task, user);
      if (risk.equals("GREEN")) authorize(task, "GREEN", version + 1);
    } else if (command.equals("renew_authorization")) {
      permission.requireUnlocked(user);
      if (!"WAITING_VERIFICATION".equals(state))
        throw new BackendException(409, "task_not_awaiting_verification");
      challenge(task, user);
    } else if (command.equals("retry")) {
      if (!Set.of("FAILED", "MANUAL_REQUIRED").contains(state))
        throw new BackendException(409, "task_cannot_retry");
      db.update(
          "UPDATE backend_nodes SET state=CASE WHEN error_code='model_review_not_confirmed' OR state='FAILED' THEN 'PENDING' ELSE 'RECONCILING' END,next_attempt_at=?,generation=generation+1 WHERE task_id=? AND state IN ('FAILED','MANUAL_REQUIRED')",
          db.now(),
          task);
      db.update(
          "UPDATE backend_tasks SET state='READY',desired_control='RUN',updated_at=? WHERE id=?",
          db.now(),
          task);
    } else throw new BackendException(400, "unknown_task_control");
    audit.write(user, task, null, "task_control", Map.of("decision", command));
    return view(user, task);
  }

  private void cancelPending(String task) {
    db.update("UPDATE backend_tasks SET state='CANCELLED',updated_at=? WHERE id=?", db.now(), task);
    db.update(
        "UPDATE backend_nodes SET state='CANCELLED' WHERE task_id=? AND (state IN ('PENDING','RETRY_WAIT') OR (state='MANUAL_REQUIRED' AND dispatch_started=FALSE))",
        task);
    db.update(
        "UPDATE quota_reservations SET state='released' WHERE state='reserved' AND operation_key IN (SELECT operation_key FROM backend_nodes WHERE task_id=? AND state IN ('CANCELLED','FAILED'))",
        task);
  }

  @Transactional
  public void compensationResult(
      String user, String task, String node, boolean success, String error) {
    lock(user, task);
    db.update(
        "UPDATE backend_nodes SET state=?,error_code=? WHERE task_id=? AND id=?",
        success ? "COMPENSATED" : "MANUAL_REQUIRED",
        error,
        task,
        node);
    if (success) {
      var n =
          db.one("SELECT operation_key FROM backend_nodes WHERE task_id=? AND id=?", task, node)
              .orElseThrow();
      db.update(
          "UPDATE quota_reservations SET state='released' WHERE operation_key=?",
          text(n, "operation_key"));
    }
    cashflows.reconcileCompensation(user, task);
    resultProjection.refresh(user, task);
    audit.write(
        user,
        task,
        node,
        "compensation",
        Map.of("state", success ? "COMPENSATED" : "MANUAL_REQUIRED"));
  }

  @Transactional
  public void finishCompensation(String user, String task) {
    lock(user, task);
    boolean manual =
        db.rows("SELECT action_id,state FROM backend_nodes WHERE task_id=?", task).stream()
            .anyMatch(
                n ->
                    Set.of(
                                "SUCCEEDED",
                                "MANUAL_REQUIRED",
                                "RECONCILING",
                                "BANK_PENDING",
                                "EXECUTING")
                            .contains(text(n, "state"))
                        && !("SUCCEEDED".equals(text(n, "state"))
                            && catalog.require(text(n, "action_id")).readOnly()));
    db.update(
        "UPDATE backend_tasks SET state=?,updated_at=? WHERE id=?",
        manual ? "MANUAL_REQUIRED" : "COMPENSATED",
        db.now(),
        task);
    db.update(
        "UPDATE backend_nodes SET state='CANCELLED' WHERE task_id=? AND (state IN ('PENDING','RETRY_WAIT') OR (state='MANUAL_REQUIRED' AND dispatch_started=FALSE))",
        task);
    cashflows.reconcileCompensation(user, task);
    resultProjection.refresh(user, task);
  }

  public Map<String, Object> view(String user, String id) {
    var row = owned(user, id);
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key :
        List.of(
            "id",
            "state",
            "required_risk",
            "version",
            "trigger_type",
            "trigger_value",
            "created_at",
            "updated_at",
            "next_run_at",
            "desired_control",
            "source_event_id",
            "parent_id",
            "run_number")) result.put(key, row.get(key));
    result.put(
        "kind",
        text(row, "state").equals("WAITING_VERIFICATION")
            ? "verify_request"
            : text(row, "state").equals("WAITING_CONFIRMATION") ? "confirm_request" : "task_state");
    var nodes = nodeViews(user, id);
    result.put("nodes", nodes);
    if ("SUCCEEDED".equals(text(row, "state"))
        && nodes.size() == 1
        && nodes.get(0).get("result") instanceof Map<?, ?> output) {
      result.put(
          "reply",
          resultReply(
              nodes.get(0).get("action_id").toString(),
              (Map<String, Object>) nodes.get(0).get("result")));
    }
    if ("recurring".equals(text(row, "trigger_type")) && row.get("parent_id") == null)
      result.put(
          "trigger_state",
          "CANCEL".equals(text(row, "desired_control"))
              ? "STOPPED"
              : "PAUSE".equals(text(row, "desired_control")) ? "PAUSED" : "ACTIVE");
    result.put("pending_id", id);
    return result;
  }

  /** 成功结果的用户可读摘要（影子渲染，金额占位符走 shadow）。 */
  private String resultReply(String action, Map<String, Object> output) {
    switch (action) {
      case "query_balance" -> {
        return shadow.render(
            "可用余额合计{{balance}}元。", Map.of("balance", output.get("totalAvailable")));
      }
            case "subscription_detect" -> {
        Object subs = output.get("subscriptions");
        if (!(subs instanceof List<?> list) || list.isEmpty()) return "当前没有自动扣费的订阅。";
        StringBuilder sb = new StringBuilder("共有 " + list.size() + " 个自动扣费订阅：");
        int shown = 0;
        for (Object item : list) {
          if (shown >= 5) break;
          if (!(item instanceof Map<?, ?> sub)) continue;
          String merchant = String.valueOf(sub.get("merchant"));
          String amount = String.valueOf(sub.get("amount"));
          String next = sub.get("next_debit") == null ? "" : "（下次 " + sub.get("next_debit") + "）";
          sb.append(shown == 0 ? "" : "；").append(merchant).append(" ").append(amount).append("元").append(next);
          shown++;
        }
        return sb.append("。回复「取消订阅」可一键取消。").toString();
      }
      case "morning_report" -> {
        Object forecast = output.get("forecast");
        if (!(forecast instanceof Map<?, ?> fv)) return "晨报已生成。";
        String min = String.valueOf(fv.get("minimumAvailable"));
        String gap = fv.get("firstGap") == null ? null : String.valueOf(fv.get("firstGap"));
        Object events = output.get("events");
        int reminders = events instanceof List<?> l ? l.size() : 0;
        return "晨报：30天最低可用 "
            + (min == null ? "未知" : min + "元")
            + (gap == null || "null".equals(gap) ? "，暂无缺口" : "，" + gap + " 预计出现缺口")
            + "；近7天提醒 "
            + reminders
            + " 项。";
      }
      case "anomaly_detect" -> {
        Object list = output.get("anomalies");
        if (!(list instanceof List<?> l) || l.isEmpty()) return "未发现异常交易。";
        return "发现 " + l.size() + " 笔异常交易，建议核对后冻结卡片。";
      }
      default -> {
        return null;
      }
    }
  }

  public List<Map<String, Object>> nodeViews(String user, String task) {
    owned(user, task);
    List<Map<String, Object>> list = new ArrayList<>();
    for (var n : db.rows("SELECT * FROM backend_nodes WHERE task_id=? ORDER BY id", task)) {
      Map<String, Object> v = new LinkedHashMap<>();
      for (String field :
          List.of("id", "action_id", "state", "attempts", "error_code", "next_attempt_at"))
        v.put(field, n.get(field));
      v.put("input", vault.map(user, text(n, "input_cipher")));
      if (text(n, "output_cipher") != null)
        v.put("result", vault.map(user, text(n, "output_cipher")));
      var spec = catalog.require(text(n, "action_id"));
      v.put(
          "compensationSupported",
          availability.compensationSupported(spec, (Map<String, Object>) v.get("input")));
      db.one(
              "SELECT state,attempts,error_code,next_attempt_at FROM backend_compensations WHERE task_id=? AND node_id=?",
              task,
              text(n, "id"))
          .ifPresent(c -> v.put("compensation", c));
      list.add(v);
    }
    return list;
  }

  public List<Map<String, Object>> list(String user, int page) {
    return db.rows(
        "SELECT id,state,required_risk,version,created_at,updated_at FROM backend_tasks WHERE user_id=? ORDER BY created_at DESC LIMIT 50 OFFSET ?",
        user,
        page * 50);
  }

  public Map<String, Object> owned(String user, String task) {
    return db.one("SELECT * FROM backend_tasks WHERE id=? AND user_id=?", task, user)
        .orElseThrow(() -> new BackendException(404, "task_not_found"));
  }

  private Map<String, Object> lock(String user, String task) {
    db.lockUser(user);
    return db.one("SELECT * FROM backend_tasks WHERE id=? AND user_id=? FOR UPDATE", task, user)
        .orElseThrow(() -> new BackendException(404, "task_not_found"));
  }

  public boolean hasUnresolved(String task) {
    return !db.rows(
            "SELECT n.id FROM backend_nodes n JOIN backend_tasks t ON t.id=n.task_id WHERE n.task_id=? AND (n.state IN ('EXECUTING','RECONCILING') OR (n.state='BANK_PENDING' AND t.desired_control<>'CANCEL'))",
            task)
        .isEmpty();
  }

  public List<Map<String, Object>> compensable(String user, String task) {
    owned(user, task);
    return db.rows(
        "SELECT * FROM backend_nodes WHERE task_id=? AND state='SUCCEEDED' ORDER BY id DESC", task);
  }

  public Map<String, Object> input(String user, Map<String, Object> node) {
    return vault.map(user, text(node, "input_cipher"));
  }

  public List<String> due() {
    return db
        .rows(
            "SELECT id FROM backend_tasks WHERE state IN ('READY','RUNNING','SCHEDULED','RETRY_WAIT','RECONCILING','COMPENSATING','CANCELLING','PAUSED') AND (next_run_at<=? OR state='COMPENSATING') ORDER BY updated_at LIMIT 100",
            db.now())
        .stream()
        .map(r -> text(r, "id"))
        .toList();
  }

  @Transactional
  public void expireConfirmations() {
    for (var item :
        db.rows(
            "SELECT id,user_id FROM backend_tasks WHERE state='WAITING_CONFIRMATION' AND expires_at<=? LIMIT 100",
            db.now())) {
      String user = text(item, "user_id"), task = text(item, "id");
      var current = lock(user, task);
      if ("WAITING_CONFIRMATION".equals(text(current, "state"))
          && !time(current, "expires_at").isAfter(db.now())) {
        cancelPending(task);
        audit.write(user, task, null, "confirmation_expired", Map.of("state", "CANCELLED"));
      }
    }
  }

  @Override
  @Transactional
  public void confirmedEvent(String user, String eventId, String kind) {
    db.lockUser(user);
    if (db.one(
            "SELECT id FROM events WHERE id=? AND user_id=? AND kind=? AND status='confirmed' AND event_type<>'prediction' AND superseded_by IS NULL",
            eventId,
            user,
            kind)
        .isEmpty()) return;
    for (var row :
        db.rows(
            "SELECT id FROM backend_tasks WHERE user_id=? AND trigger_type='event' AND trigger_value=? AND state='SCHEDULED' AND desired_control='RUN' AND approved_risk IS NOT NULL",
            user,
            kind)) {
      String id = text(row, "id");
      var current = lock(user, id);
      if (!"SCHEDULED".equals(text(current, "state"))) continue;
      String occurrence = "event:" + eventId;
      if (db.one(
              "SELECT task_id FROM backend_trigger_runs WHERE template_id=? AND occurrence_key=?",
              id,
              occurrence)
          .isPresent()) continue;
      var template = plan(user, id);
      if (healthOrigin(user, eventId) && healthUnsafe(template)) continue;
      if (template.executionDeadline() != null
          && !Instant.parse(template.executionDeadline()).isAfter(db.now())) {
        cancelPending(id);
        continue;
      }
      var activated =
          new WorkflowPlan(
              template.requestId(),
              template.nodes(),
              template.trigger(),
              eventId,
              template.modelDegraded(),
              template.requireCompensation(),
              template.executionDeadline());
      db.update(
          "INSERT INTO backend_trigger_runs(template_id,occurrence_key,task_id) VALUES(?,?,?)",
          id,
          occurrence,
          id);
      db.update(
          "UPDATE backend_tasks SET state='READY',next_run_at=?,source_event_id=?,plan_cipher=?,updated_at=? WHERE id=?",
          db.now(),
          eventId,
          vault.seal(user, activated),
          db.now(),
          id);
      audit.write(user, id, null, "event_triggered", Map.of("eventId", eventId));
    }
  }

  @Transactional
  public List<String> recurringDue() {
    List<String> created = new ArrayList<>();
    for (var row :
        db.rows(
            "SELECT * FROM backend_tasks WHERE trigger_type='recurring' AND parent_id IS NULL AND state='SUCCEEDED' AND desired_control='RUN' AND next_run_at<=? LIMIT 20",
            db.now())) {
      String user = text(row, "user_id"), parent = text(row, "id");
      var locked = lock(user, parent);
      if (time(locked, "next_run_at").isAfter(db.now())) continue;
      Instant upcoming =
          CronExpression.parse(text(locked, "trigger_value"))
              .next(db.now().atZone(ZoneId.of("Asia/Shanghai")))
              .toInstant();
      if (!db.rows(
              "SELECT id FROM backend_tasks WHERE parent_id=? AND state NOT IN ('SUCCEEDED','CANCELLED','COMPENSATED','FAILED')",
              parent)
          .isEmpty()) {
        db.update("UPDATE backend_tasks SET next_run_at=? WHERE id=?", upcoming, parent);
        audit.write(
            user, parent, null, "recurring_round_skipped", Map.of("reason", "prior_round_active"));
        continue;
      }
      WorkflowPlan template = plan(user, parent);
      if (template.executionDeadline() != null
          && !Instant.parse(template.executionDeadline()).isAfter(db.now())) {
        db.update("UPDATE backend_tasks SET next_run_at=NULL WHERE id=?", parent);
        continue;
      }
      long run = number(locked, "run_number") + 1;
      var child =
          new WorkflowPlan(
              text(locked, "request_key") + ":run:" + run,
              template.nodes(),
              new WorkflowPlan.Trigger("immediate", null),
              template.sourceEventId(),
              template.modelDegraded(),
              template.requireCompensation(),
              template.executionDeadline());
      String id;
      try {
        id = create(user, child);
      } catch (BackendException failure) {
        db.update(
            "UPDATE backend_tasks SET state='WAITING_USER',next_run_at=NULL WHERE id=?", parent);
        audit.write(
            user, parent, null, "recurring_round_blocked", Map.of("error", failure.getMessage()));
        continue;
      }
      db.update(
          "INSERT INTO backend_trigger_runs(template_id,occurrence_key,task_id) VALUES(?,?,?)",
          parent,
          "round:" + time(locked, "next_run_at"),
          id);
      db.update("UPDATE backend_tasks SET parent_id=?,run_number=? WHERE id=?", parent, run, id);
      Instant next =
          CronExpression.parse(text(locked, "trigger_value"))
              .next(db.now().atZone(ZoneId.of("Asia/Shanghai")))
              .toInstant();
      db.update(
          "UPDATE backend_tasks SET run_number=?,next_run_at=? WHERE id=?", run, next, parent);
      created.add(id);
    }
    return created;
  }

  public WorkflowPlan plan(String user, String task) {
    return vault.open(user, text(owned(user, task), "plan_cipher"), WorkflowPlan.class);
  }

  private Map<String, Object> resolve(String user, String task, Map<String, Object> source) {
    Map<String, Object> resolved = new LinkedHashMap<>(source);
    for (var e : source.entrySet())
      if (e.getValue() instanceof String s && s.startsWith("$")) {
        String[] path = s.substring(1).split("\\.");
        var producer =
            db.one(
                    "SELECT * FROM backend_nodes WHERE task_id=? AND id=? AND state='SUCCEEDED'",
                    task,
                    path[0])
                .orElseThrow(() -> new BackendException(409, "dependency_result_unavailable"));
        Object value = vault.map(user, text(producer, "output_cipher")).get(path[1]);
        if (value == null) throw new BackendException(409, "dependency_field_missing");
        resolved.put(e.getKey(), value);
      }
    return resolved;
  }

  public Instant now() {
    return db.now();
  }

  public String hash(Object value) {
    return PerceptionAccess.hash(json(value));
  }

  private String json(Object value) {
    try {
      return mapper
          .writer()
          .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (Exception e) {
      throw new BackendException(400, "invalid_plan_json");
    }
  }

  private List<String> stringList(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }
}
