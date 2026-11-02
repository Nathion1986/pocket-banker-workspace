package com.pocketbanker.prediction;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.profile.ProfileService;
import com.pocketbanker.security.VaultCodec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PredictionService {
  private final BankBindingService bindings;
  private final BankAdapters banks;
  private final BackendStore db;
  private final VaultCodec vault;
  private final ProfileService profile;
  private final CashflowLedger cashflows;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final com.pocketbanker.orchestration.ModelCoordinator models;

  public Map<String, Object> forecast(String user, int days) {
    if (days < 1 || days > 366) throw new BackendException(400, "invalid_forecast_days");
    List<Map<String, Object>> accounts = new ArrayList<>();
    List<Map<String, Object>> failures = new ArrayList<>();
    Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
    // Read the bank's operation state before its balance: local task state may lag settlement.
    for (var node :
        db.rows(
            "SELECT n.operation_key,n.input_cipher FROM backend_nodes n JOIN backend_tasks t ON t.id=n.task_id WHERE t.user_id=? AND n.action_id='wealth_redeem' AND n.state='BANK_PENDING'",
            user)) {
      String operation = text(node, "operation_key");
      var input = vault.map(user, text(node, "input_cipher"));
      String bank = input.get("bank").toString();
      try {
        var actual = redemptionOutcome(user, operation, input);
        if ("PENDING".equals(actual.get("status")))
          pending.put(operation, Map.of("input", input, "outcome", actual));
      } catch (BackendException e) {
        failures.add(Map.of("bank", bank, "reason", "redemption_outcome_unavailable"));
      }
    }
    for (var b : bindings.list(user))
      if ("active".equals(b.get("state")))
        try {
          var a =
              new LinkedHashMap<>(
                  bindings.account(user, b.get("bank").toString(), b.get("account_id")));
          if (!"CNY".equals(a.get("currency")))
            throw new BackendException(409, "currency_conversion_required");
          if (Boolean.TRUE.equals(a.get("frozen"))) {
            a.put("available", "0");
            a.put("unavailableReason", "frozen");
          }
          accounts.add(a);
        } catch (BackendException e) {
          failures.add(Map.of("bank", b.get("bank"), "reason", e.getMessage()));
        }
    if (accounts.isEmpty()) throw new BackendException(409, "bank_binding_required");
    LocalDate today = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    List<CashflowSimulation.Flow> flows = new ArrayList<>();
    for (var e :
        db.rows(
            "SELECT * FROM events WHERE user_id=? AND status='confirmed' AND event_type='finance' AND superseded_by IS NULL AND amount IS NOT NULL",
            user)) {
      if (e.get("currency") != null && !"CNY".equals(e.get("currency")))
        throw new BackendException(409, "currency_conversion_required");
      String kind = text(e, "kind");
      if (!Set.of("tuition_due", "payment_due").contains(kind)) continue;
      BigDecimal amount = new BigDecimal(e.get("amount").toString());
      String date = text(e, "due_date"), recurrence = text(e, "recurrence");
      if (date != null && recurrence == null) {
        LocalDate day = LocalDate.parse(date);
        BigDecimal remaining = cashflows.remaining(user, text(e, "id"), day, amount);
        if (remaining.signum() > 0)
          flows.add(
              new CashflowSimulation.Flow(
                  day.isBefore(today) ? today : day,
                  remaining.negate(),
                  eventAccount(e),
                  text(e, "id")));
      }
      if (recurrence != null && recurrence.matches("FREQ=MONTHLY;BYMONTHDAY=\\d+")) {
        int day = Integer.parseInt(recurrence.split("=")[2]);
        for (int n = 0; n <= days; n++) {
          LocalDate at = today.plusDays(n);
          if (at.getDayOfMonth() == day
              && (date == null || !at.isBefore(LocalDate.parse(date)))
              && cashflows.remaining(user, text(e, "id"), at, amount).signum() > 0)
            flows.add(
                new CashflowSimulation.Flow(
                    at,
                    cashflows.remaining(user, text(e, "id"), at, amount).negate(),
                    eventAccount(e),
                    text(e, "id")));
        }
      }
    }
    for (var fact : profile.facts(user)) {
      if (fact.get("subject_id") != null
          && profile.persons(user).stream()
              .noneMatch(
                  p ->
                      fact.get("subject_id").equals(p.get("id"))
                          && "self".equals(p.get("relation")))) continue;
      String key = String.valueOf(fact.get("fact_key"));
      Object v = fact.get("value");
      if ((key.startsWith("income_schedule") || key.startsWith("expense_schedule"))
          && v instanceof Map<?, ?> p
          && p.containsKey("day")
          && p.containsKey("amount")) {
        int day = Integer.parseInt(p.get("day").toString());
        BigDecimal value = Money.positive(p.get("amount"));
        if (key.startsWith("expense_schedule")) value = value.negate();
        for (int i = 0; i <= days; i++) {
          LocalDate at = today.plusDays(i);
          if (at.getDayOfMonth() == day
              && (fact.get("valid_to") == null
                  || at.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                      .toInstant()
                      .isBefore(com.pocketbanker.data.BackendStore.time(fact, "valid_to")))) {
            String account =
                p.get("bank") != null && p.get("accountId") != null
                    ? p.get("bank") + ":" + p.get("accountId")
                    : null;
            flows.add(new CashflowSimulation.Flow(at, value, account, fact.get("id").toString()));
          }
        }
      }
    }
    // A settlement that races the balance snapshot is explicitly incomplete, never counted twice.
    for (var entry : pending.entrySet()) {
      @SuppressWarnings("unchecked")
      var input = (Map<String, Object>) entry.getValue().get("input");
      try {
        var result = redemptionOutcome(user, entry.getKey(), input);
        if (!"PENDING".equals(result.get("status"))) {
          failures.add(Map.of("bank", input.get("bank"), "reason", "balance_snapshot_changed"));
          continue;
        }
        if (result.containsKey("settleAt")) {
          LocalDate at =
              Instant.parse(result.get("settleAt").toString())
                  .atZone(ZoneId.of("Asia/Shanghai"))
                  .toLocalDate();
          if (!at.isBefore(today))
            flows.add(
                new CashflowSimulation.Flow(
                    at,
                    Money.positive(result.get("amount")),
                    input.get("bank") + ":" + input.get("accountId"),
                    entry.getKey()));
          else failures.add(Map.of("bank", input.get("bank"), "reason", "redemption_overdue"));
        } else failures.add(Map.of("bank", input.get("bank"), "reason", "redemption_time_unknown"));
      } catch (BackendException e) {
        failures.add(Map.of("bank", input.get("bank"), "reason", "redemption_outcome_unavailable"));
      }
    }
    Map<String, Object> out = CashflowSimulation.run(accounts, flows, today, days);
    out.put("accounts", accounts);
    out.put("complete", failures.isEmpty());
    out.put("failures", failures);
    out.put("type", "prediction");
    return out;
  }

  private String eventAccount(Map<String, Object> event) {
    String payload = text(event, "payload_json");
    if (payload == null) return null;
    try {
      var value =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(
                  payload,
                  new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      return value.get("bank") != null && value.get("accountId") != null
          ? value.get("bank") + ":" + value.get("accountId")
          : null;
    } catch (Exception e) {
      throw new BackendException(409, "invalid_cashflow_metadata");
    }
  }

  private Map<String, Object> redemptionOutcome(
      String user, String operation, Map<String, Object> input) {
    String bank = input.get("bank").toString();
    String account = bindings.requireAccount(user, bank, input.get("accountId"));
    var result = banks.require(bank).outcome(operation, user);
    SchemaValidator.validate(
        result, ContractSchemas.output("wealth_redeem", "bank"), 502, "invalid_redemption_outcome");
    if (!operation.equals(result.get("operationId"))
        || !"CNY".equals(result.get("currency"))
        || Money.positive(input.get("amount")).compareTo(Money.positive(result.get("amount"))) != 0
        || !input.get("productId").equals(result.get("productId"))
        || account.isBlank()) throw new BackendException(502, "redemption_outcome_mismatch");
    return result;
  }

  public Map<String, Object> tuition(String user, String eventId, String amount, String dueDate) {
    var event = profile.confirmed(user, eventId);
    BigDecimal required = Money.positive(amount);
    LocalDate due = LocalDate.parse(dueDate);
    if (!"tuition_due".equals(text(event, "kind"))
        || !"CNY".equals(text(event, "currency"))
        || event.get("amount") == null
        || required.compareTo(new BigDecimal(event.get("amount").toString())) != 0
        || !due.toString().equals(text(event, "due_date")))
      throw new BackendException(409, "tuition_fact_mismatch");
    if (due.isBefore(db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))
      throw new BackendException(409, "deadline_in_past");
    if (cashflows.settled(user, eventId, dueDate))
      throw new BackendException(409, "obligation_already_settled");
    required = cashflows.remaining(user, eventId, due, required);
    if (required.signum() == 0) throw new BackendException(409, "obligation_already_settled");
    List<Map<String, Object>> available = liveAccounts(user);
    BigDecimal total =
        available.stream()
            .map(a -> new BigDecimal(a.get("available").toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal gap = required.subtract(total).max(BigDecimal.ZERO);
    List<WorkflowPlan.Node> nodes = new ArrayList<>();
    Map<String, Object> selected = null;
    if (gap.signum() > 0) {
      for (var account : available) {
        String bank = account.get("bank").toString();
        List<Map<String, Object>> products = banks.require(bank).products();
        for (var holding : banks.require(bank).holdings(account.get("id").toString())) {
          var product =
              products.stream()
                  .filter(p -> p.get("id").equals(holding.get("productId")))
                  .findFirst()
                  .orElseThrow();
          int settle = Integer.parseInt(product.get("settleDays").toString());
          if (new BigDecimal(holding.get("amount").toString()).compareTo(gap) >= 0
              && !db.now()
                  .atZone(ZoneId.of("Asia/Shanghai"))
                  .toLocalDate()
                  .plusDays(settle + 1)
                  .isAfter(due)) {
            nodes.add(
                new WorkflowPlan.Node(
                    "redeem",
                    "wealth_redeem",
                    Map.of(
                        "bank",
                        bank,
                        "accountId",
                        account.get("id"),
                        "amount",
                        gap.toPlainString(),
                        "productId",
                        holding.get("productId")),
                    List.of()));
            selected = account;
            break;
          }
        }
        if (selected != null) break;
      }
      if (selected == null)
        return Map.of(
            "type",
            "proposal",
            "status",
            "insufficient_liquidity",
            "gap",
            gap.toPlainString(),
            "required",
            required.toPlainString());
    } else
      selected =
          available.stream()
              .max(Comparator.comparing(a -> new BigDecimal(a.get("available").toString())))
              .orElseThrow();
    String id = UUID.randomUUID().toString();
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("nodes", nodes);
    draft.put("sourceAccount", selected);
    draft.put("required", required.toPlainString());
    draft.put("available", available);
    draft.put("dueDate", due.toString());
    reviewProposal(
        user, id, nodes, Map.of("amount", required.toPlainString(), "dueDate", due.toString()));
    BigDecimal quoted = required;
    transaction.executeWithoutResult(
        status -> {
          db.lockUser(user);
          var current = profile.confirmed(user, eventId);
          if (cashflows
                  .remaining(user, eventId, due, Money.positive(current.get("amount")))
                  .compareTo(quoted)
              != 0) throw new BackendException(409, "obligation_balance_changed");
          db.update(
              "INSERT INTO backend_proposals VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              user,
              eventId,
              "tuition",
              vault.seal(user, draft),
              "已确认学费需求的资金建议，确认后才创建任务",
              "pending",
              db.now().plusSeconds(3600),
              null,
              db.now());
        });
    // schema 的 object 校验要求 Map（instanceof Map），Node record 直接放会 contract violation
    List<Map<String, Object>> stepMaps = new ArrayList<>();
    for (WorkflowPlan.Node node : nodes) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", node.id());
      m.put("actionId", node.actionId());
      m.put("params", node.params());
      m.put("dependsOn", node.dependsOn());
      stepMaps.add(m);
    }
    return Map.of(
        "id",
        id,
        "type",
        "proposal",
        "status",
        "pending",
        "gap",
        gap.toPlainString(),
        "required",
        required.toPlainString(),
        "steps",
        stepMaps,
        "dueDate",
        due.toString());
  }

  public void reviewProposal(
      String user, String id, List<WorkflowPlan.Node> nodes, Map<String, Object> context) {
    Map<String, Object> features = new LinkedHashMap<>(context);
    features.put(
        "nodes",
        nodes.stream().map(n -> Map.of("actionId", n.actionId(), "params", n.params())).toList());
    var review = models.review(user, "proposal-" + id, features, "prediction", true);
    if (!review.decision().equals("confirm"))
      throw new BackendException(409, "proposal_review_requires_clarification");
  }

  public List<Map<String, Object>> liveAccounts(String user) {
    List<Map<String, Object>> a = new ArrayList<>();
    for (var b : bindings.list(user))
      if ("active".equals(b.get("state")))
        a.add(bindings.account(user, b.get("bank").toString(), b.get("account_id")));
    if (a.isEmpty()) throw new BackendException(409, "bank_binding_required");
    for (var account : a) {
      if (!"CNY".equals(account.get("currency")))
        throw new BackendException(409, "currency_conversion_required");
      if (Boolean.TRUE.equals(account.get("frozen"))) account.put("available", "0.00");
    }
    return a;
  }

  public List<Map<String, Object>> proposals(String user) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r :
        db.rows("SELECT * FROM backend_proposals WHERE user_id=? ORDER BY created_at DESC", user)) {
      Map<String, Object> v = new LinkedHashMap<>();
      for (String k :
          List.of("id", "kind", "state", "explanation", "expires_at", "source_event_id", "task_id"))
        v.put(k, r.get(k));
      v.put("plan", vault.map(user, text(r, "plan_cipher")));
      out.add(v);
    }
    return out;
  }

  public WorkflowPlan proposalPlan(
      String user, String id, String payeeBank, String payeeAccount, String payeeName) {
    var row =
        db.one("SELECT * FROM backend_proposals WHERE id=? AND user_id=?", id, user)
            .orElseThrow(() -> new BackendException(404, "proposal_not_found"));
    if (!"pending".equals(text(row, "state")) || !time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "proposal_inactive");
    profile.confirmed(user, text(row, "source_event_id"));
    Map<String, Object> draft = vault.map(user, text(row, "plan_cipher"));
    if (payeeBank == null || payeeAccount == null || payeeName == null)
      throw new BackendException(400, "verified_payment_recipient_required");
    List<WorkflowPlan.Node> nodes = new ArrayList<>();
    Object original = draft.get("nodes");
    if (original instanceof List<?> list)
      for (Object o : list) {
        var m = (Map<?, ?>) o;
        nodes.add(
            new WorkflowPlan.Node(
                m.get("id").toString(),
                m.get("actionId").toString(),
                (Map<String, Object>) m.get("params"),
                (List<String>) m.get("dependsOn")));
      }
    Map<String, Object> target = (Map<String, Object>) draft.get("sourceAccount");
    String targetBank = target.get("bank").toString(), targetAccount = target.get("id").toString();
    var currentAccounts = liveAccounts(user);
    var currentTarget =
        currentAccounts.stream()
            .filter(a -> targetBank.equals(a.get("bank")) && targetAccount.equals(a.get("id")))
            .findFirst()
            .orElseThrow(() -> new BackendException(409, "funding_account_unavailable"));
    BigDecimal required = Money.positive(draft.get("required"));
    var obligation = profile.confirmed(user, text(row, "source_event_id"));
    var remainingBill =
        cashflows.remaining(
            user,
            text(row, "source_event_id"),
            LocalDate.parse(draft.get("dueDate").toString()),
            Money.positive(obligation.get("amount")));
    if (remainingBill.compareTo(required) != 0)
      throw new BackendException(409, "obligation_balance_changed");
    BigDecimal targetFee =
        required
            .multiply(new BigDecimal(currentTarget.getOrDefault("transferFeeRate", "0").toString()))
            .setScale(2, RoundingMode.CEILING);
    BigDecimal redemption =
        nodes.stream()
            .filter(n -> n.actionId().equals("wealth_redeem"))
            .map(n -> Money.positive(n.params().get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal remaining =
        required
            .add(targetFee)
            .subtract(new BigDecimal(currentTarget.get("available").toString()))
            .subtract(redemption)
            .max(BigDecimal.ZERO);
    int sequence = 0;
    for (var account : currentAccounts) {
      if (remaining.signum() == 0) break;
      if (targetAccount.equals(account.get("id")) && targetBank.equals(account.get("bank")))
        continue;
      BigDecimal rate = new BigDecimal(account.getOrDefault("transferFeeRate", "0").toString());
      BigDecimal cash =
          new BigDecimal(account.get("available").toString())
              .divide(BigDecimal.ONE.add(rate), 2, RoundingMode.DOWN)
              .min(remaining);
      if (account.containsKey("singleLimit"))
        cash = cash.min(new BigDecimal(account.get("singleLimit").toString()));
      if (cash.signum() <= 0) continue;
      nodes.add(
          new WorkflowPlan.Node(
              "gather" + sequence++,
              "transfer",
              Map.of(
                  "bank",
                  account.get("bank"),
                  "accountId",
                  account.get("id"),
                  "amount",
                  cash.toPlainString(),
                  "toBank",
                  targetBank,
                  "toAccountId",
                  targetAccount,
                  "toName",
                  "本人账户"),
              List.of()));
      remaining = remaining.subtract(cash);
    }
    if (remaining.signum() > 0) {
      boolean increased = false;
      for (int i = 0; i < nodes.size(); i++)
        if (nodes.get(i).actionId().equals("wealth_redeem")) {
          var node = nodes.get(i);
          BigDecimal amount = Money.positive(node.params().get("amount")).add(remaining);
          boolean sufficient =
              banks.require(targetBank).holdings(targetAccount).stream()
                  .anyMatch(
                      h ->
                          Objects.equals(h.get("productId"), node.params().get("productId"))
                              && new BigDecimal(h.get("amount").toString()).compareTo(amount) >= 0);
          if (!sufficient) throw new BackendException(409, "insufficient_liquidity_including_fees");
          Map<String, Object> params = new LinkedHashMap<>(node.params());
          params.put("amount", amount.toPlainString());
          nodes.set(i, new WorkflowPlan.Node(node.id(), node.actionId(), params, node.dependsOn()));
          increased = true;
          break;
        }
      if (!increased) throw new BackendException(409, "funding_balance_changed");
    }
    int settlementDays =
        currentAccounts.stream()
            .mapToInt(a -> Integer.parseInt(a.getOrDefault("settlementDays", 0).toString()))
            .max()
            .orElse(0);
    for (var node : nodes)
      if (node.actionId().equals("wealth_redeem")) {
        var product =
            banks.require(targetBank).products().stream()
                .filter(p -> Objects.equals(p.get("id"), node.params().get("productId")))
                .findFirst()
                .orElseThrow();
        settlementDays =
            Math.max(settlementDays, Integer.parseInt(product.get("settleDays").toString()));
      }
    LocalDate deadlineDate = LocalDate.parse(draft.get("dueDate").toString());
    Instant executeAt =
        deadlineDate
            .minusDays(settlementDays + 2L)
            .atTime(8, 0)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant();
    if (executeAt.isBefore(db.now())) executeAt = db.now();
    List<String> deps = nodes.stream().map(WorkflowPlan.Node::id).toList();
    nodes.add(
        new WorkflowPlan.Node(
            "pay",
            "transfer",
            Map.of(
                "bank",
                targetBank,
                "accountId",
                targetAccount,
                "amount",
                draft.get("required"),
                "toBank",
                payeeBank,
                "toAccountId",
                payeeAccount,
                "toName",
                payeeName),
            deps));
    return new WorkflowPlan(
        "proposal-" + id,
        nodes,
        new WorkflowPlan.Trigger("at", executeAt.toString()),
        text(row, "source_event_id"),
        false,
        false,
        deadlineDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString());
  }

  @Transactional
  public void reject(String user, String id) {
    if (db.update(
            "UPDATE backend_proposals SET state='rejected' WHERE user_id=? AND id=? AND state='pending'",
            user,
            id)
        == 0) throw new BackendException(404, "proposal_not_found");
  }
}
