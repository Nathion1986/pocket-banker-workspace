package com.pocketbanker.prediction;

import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.VaultCodec;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** All coordination modes only draft plans; execution uses the same confirmed workflow engine. */
@Service
@RequiredArgsConstructor
public class FundingCoordinator {
  private final PredictionService prediction;
  private final CashflowLedger cashflows;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final BackendStore db;
  private final VaultCodec vault;
  private final com.pocketbanker.adapters.nativebank.BankAdapters banks;
  private final com.pocketbanker.profile.ProfileService profile;

  public record Request(
      String mode,
      String targetBank,
      String payeeBank,
      String payeeAccount,
      String payeeName,
      String amount,
      Instant deadline,
      String billId,
      String retainAmount) {}

  public Map<String, Object> propose(String user, Request input) {
    if (input.mode() == null
        || !Set.of(
                "salary_gather",
                "split_payment",
                "channel_optimization",
                "repayment",
                "birthday_funding")
            .contains(input.mode())) throw new BackendException(400, "unknown_funding_mode");
    List<Map<String, Object>> accounts = new ArrayList<>(prediction.liveAccounts(user));
    accounts.sort(
        Comparator.comparing(
            a -> new BigDecimal(a.getOrDefault("transferFeeRate", "0").toString())));
    if (input.deadline() != null && !input.deadline().isAfter(db.now()))
      throw new BackendException(409, "deadline_in_past");
    BigDecimal retain =
        input.retainAmount() == null ? BigDecimal.ZERO : nonnegative(input.retainAmount());
    List<WorkflowPlan.Node> nodes = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    Instant deadline = input.deadline();
    if (input.mode().equals("repayment")) {
      if (input.payeeBank() != null || input.payeeAccount() != null || input.payeeName() != null)
        throw new BackendException(400, "repayment_uses_owned_bill_not_recipient");
      var destination =
          accounts.stream()
              .filter(a -> Objects.equals(input.targetBank(), a.get("bank")))
              .findFirst()
              .orElseThrow(() -> new BackendException(409, "repayment_bank_required"));
      String bank = destination.get("bank").toString(), account = destination.get("id").toString();
      banks.require(bank).requireSupported("credit_repay", Map.of());
      var bill =
          banks.require(bank).creditBills(account).stream()
              .filter(
                  row ->
                      new BigDecimal(row.get("outstanding").toString()).signum() > 0
                          && (input.billId() == null || input.billId().equals(row.get("billId"))))
              .min(Comparator.comparing(row -> Instant.parse(row.get("dueAt").toString())))
              .orElseThrow(() -> new BackendException(409, "outstanding_credit_bill_required"));
      BigDecimal requested =
          input.amount() == null
              ? Money.positive(bill.get("outstanding"))
              : Money.positive(input.amount());
      if (requested.compareTo(new BigDecimal(bill.get("outstanding").toString())) > 0)
        throw new BackendException(409, "repayment_exceeds_outstanding");
      Instant due = Instant.parse(bill.get("dueAt").toString());
      deadline = deadline == null || deadline.isAfter(due) ? due : deadline;
      if (!deadline.isAfter(db.now())) throw new BackendException(409, "repayment_deadline_passed");
      if (Boolean.TRUE.equals(destination.get("frozen"))
          || limit(destination).compareTo(requested) < 0)
        throw new BackendException(409, "repayment_account_unavailable");
      BigDecimal destinationFloor = retain.max(knownExpenses(user, destination, deadline));
      BigDecimal gap =
          requested
              .add(destinationFloor)
              .subtract(new BigDecimal(destination.get("available").toString()))
              .max(BigDecimal.ZERO);
      for (var source : accounts) {
        if (source.get("id").equals(account) || !arrives(source, deadline)) continue;
        BigDecimal amount =
            maxTransfer(source, retain.max(knownExpenses(user, source, deadline))).min(gap);
        if (amount.signum() == 0) continue;
        nodes.add(node("gather" + nodes.size(), source, amount, bank, account, "本人还款账户"));
        gap = gap.subtract(amount);
        if (gap.signum() == 0) break;
      }
      if (gap.signum() > 0)
        throw new BackendException(409, "insufficient_before_repayment_deadline");
      nodes.add(
          new WorkflowPlan.Node(
              "repay",
              "credit_repay",
              Map.of(
                  "bank",
                  bank,
                  "accountId",
                  account,
                  "amount",
                  requested.toPlainString(),
                  "billId",
                  bill.get("billId")),
              nodes.stream().map(WorkflowPlan.Node::id).toList()));
      total = requested;
    } else if (input.mode().equals("salary_gather")) {
      var destination =
          accounts.stream()
              .filter(a -> Objects.equals(input.targetBank(), a.get("bank")))
              .findFirst()
              .orElseThrow(() -> new BackendException(409, "target_bank_not_bound"));
      if (Boolean.TRUE.equals(destination.get("frozen")))
        throw new BackendException(409, "target_account_frozen");
      for (var source : accounts) {
        if (source.get("id").equals(destination.get("id")) || !arrives(source, deadline)) continue;
        BigDecimal floor = retain.max(knownExpenses(user, source, deadline));
        BigDecimal amount = maxTransfer(source, floor);
        if (amount.signum() == 0) continue;
        nodes.add(
            node(
                "gather" + nodes.size(),
                source,
                amount,
                destination.get("bank").toString(),
                destination.get("id").toString(),
                "本人账户"));
        total = total.add(amount);
      }
    } else if (input.mode().equals("birthday_funding")) {
      BigDecimal amount = Money.positive(input.amount());
      var source =
          accounts.stream()
              .filter(
                  a ->
                      !Boolean.TRUE.equals(a.get("frozen"))
                          && (input.targetBank() == null
                              || input.targetBank().equals(a.get("bank")))
                          && new BigDecimal(a.get("available").toString())
                                  .subtract(retain.max(knownExpenses(user, a, input.deadline())))
                                  .compareTo(amount)
                              >= 0
                          && limit(a).compareTo(amount) >= 0)
              .findFirst()
              .orElseThrow(() -> new BackendException(409, "no_eligible_birthday_funding"));
      nodes.add(
          new WorkflowPlan.Node(
              "birthday_reserve",
              "reserve",
              Map.of(
                  "bank",
                  source.get("bank"),
                  "accountId",
                  source.get("id"),
                  "amount",
                  amount.toPlainString(),
                  "purpose",
                  "生日预算"),
              List.of()));
      total = amount;
    } else {
      if (input.payeeBank() == null || input.payeeAccount() == null || input.payeeName() == null)
        throw new BackendException(400, "payment_recipient_required");
      BigDecimal requested = Money.positive(input.amount());
      BigDecimal remaining = requested;
      for (var source : accounts) {
        if (!arrives(source, deadline)) continue;
        BigDecimal eligible =
            maxTransfer(source, retain.max(knownExpenses(user, source, deadline)));
        if (input.mode().equals("channel_optimization") && eligible.compareTo(requested) < 0)
          continue;
        BigDecimal amount = eligible.min(remaining);
        if (amount.signum() == 0) continue;
        nodes.add(
            node(
                "payment" + nodes.size(),
                source,
                amount,
                input.payeeBank(),
                input.payeeAccount(),
                input.payeeName()));
        total = total.add(amount);
        remaining = remaining.subtract(amount);
        if (remaining.signum() == 0) break;
      }
      if (remaining.signum() > 0) throw new BackendException(409, "no_eligible_funding_channel");
    }
    if (nodes.isEmpty()) throw new BackendException(409, "no_funding_steps");
    String id = UUID.randomUUID().toString();
    var plan =
        new WorkflowPlan(
            "funding:" + id,
            nodes,
            new WorkflowPlan.Trigger("immediate", null),
            null,
            false,
            false,
            deadline == null ? null : deadline.toString());
    var personSnapshot = profile.referenceSnapshot(user, nodes);
    prediction.reviewProposal(user, id, nodes, Map.of("amount", total.toPlainString()));
    transaction.executeWithoutResult(
        status -> {
          db.lockUser(user);
          profile.requireReferenceSnapshot(user, personSnapshot);
          db.update(
              "INSERT INTO backend_proposals VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              user,
              null,
              input.mode(),
              vault.seal(user, plan),
              "资金协调建议，确认后创建任务",
              "pending",
              db.now().plusSeconds(900),
              null,
              db.now());
          profile.capturePersonReferences(user, "proposal", id, nodes);
        });
    return Map.of(
        "id",
        id,
        "type",
        "proposal",
        "state",
        "pending",
        "total",
        total.toPlainString(),
        "plan",
        plan);
  }

  private BigDecimal nonnegative(String value) {
    try {
      BigDecimal amount = new BigDecimal(value);
      if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() > 17)
        throw new IllegalArgumentException();
      return amount;
    } catch (Exception e) {
      throw new BackendException(400, "invalid_retain_amount");
    }
  }

  private boolean arrives(Map<String, Object> account, Instant deadline) {
    long days = Long.parseLong(account.getOrDefault("settlementDays", 0).toString());
    return days >= 0
        && days <= 366
        && (deadline == null || !db.now().plusSeconds(days * 86400).isAfter(deadline));
  }

  private BigDecimal limit(Map<String, Object> account) {
    BigDecimal single =
        new BigDecimal(account.getOrDefault("singleLimit", "9999999999999.99").toString());
    BigDecimal daily =
        new BigDecimal(account.getOrDefault("remainingDailyLimit", single.toString()).toString());
    return single.min(daily).max(BigDecimal.ZERO);
  }

  private BigDecimal knownExpenses(String user, Map<String, Object> account, Instant deadline) {
    LocalDate today = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    LocalDate horizon =
        deadline == null
            ? today.withDayOfMonth(today.lengthOfMonth())
            : deadline.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    // Salary collection retains the upcoming month as well when run on its last day.
    if (deadline == null && horizon.equals(today)) horizon = today.plusMonths(1);
    if (horizon.isAfter(today.plusDays(366)))
      throw new BackendException(400, "funding_horizon_too_long");
    BigDecimal result = BigDecimal.ZERO;
    for (var fact : profile.facts(user)) {
      if (!fact.get("fact_key").toString().startsWith("expense_schedule")
          || !(fact.get("value") instanceof Map<?, ?> value)) continue;
      if (fact.get("subject_id") != null
          && profile.persons(user).stream()
              .noneMatch(
                  p ->
                      fact.get("subject_id").equals(p.get("id"))
                          && "self".equals(p.get("relation")))) continue;
      if (value.containsKey("bank") && !account.get("bank").equals(value.get("bank"))) continue;
      if (value.containsKey("accountId") && !account.get("id").equals(value.get("accountId")))
        continue;
      for (LocalDate day = today; !day.isAfter(horizon); day = day.plusDays(1))
        if (day.getDayOfMonth() == Integer.parseInt(value.get("day").toString())
            && (fact.get("valid_to") == null
                || day.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                    .toInstant()
                    .isBefore(com.pocketbanker.data.BackendStore.time(fact, "valid_to"))))
          result = result.add(Money.positive(value.get("amount")));
    }
    for (var event :
        db.rows(
            "SELECT * FROM events WHERE user_id=? AND status='confirmed' AND superseded_by IS NULL AND kind IN ('tuition_due','payment_due')",
            user)) {
      if (event.get("payload_json") != null) {
        try {
          var metadata =
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .readValue(
                      event.get("payload_json").toString(),
                      new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
          if (metadata.get("bank") != null && !account.get("bank").equals(metadata.get("bank")))
            continue;
          if (metadata.get("accountId") != null
              && !account.get("id").equals(metadata.get("accountId"))) continue;
        } catch (Exception e) {
          throw new BackendException(409, "invalid_cashflow_metadata");
        }
      }
      if (event.get("amount") == null || !"CNY".equals(event.get("currency")))
        throw new BackendException(409, "currency_conversion_required");
      String recurrence = com.pocketbanker.data.BackendStore.text(event, "recurrence"),
          date = com.pocketbanker.data.BackendStore.text(event, "due_date");
      if (recurrence == null && date != null) {
        LocalDate due = LocalDate.parse(date);
        if (!due.isAfter(horizon))
          result =
              result.add(
                  cashflows.remaining(
                      user, event.get("id").toString(), due, Money.positive(event.get("amount"))));
      } else if (recurrence != null) {
        for (LocalDate due = today; !due.isAfter(horizon); due = due.plusDays(1))
          if (recurrence.equals("FREQ=MONTHLY;BYMONTHDAY=" + due.getDayOfMonth())
              && (date == null || !due.isBefore(LocalDate.parse(date))))
            result =
                result.add(
                    cashflows.remaining(
                        user,
                        event.get("id").toString(),
                        due,
                        Money.positive(event.get("amount"))));
      }
    }
    return result;
  }

  private BigDecimal maxTransfer(Map<String, Object> account, BigDecimal retain) {
    if (Boolean.TRUE.equals(account.get("frozen"))) return BigDecimal.ZERO;
    BigDecimal cash =
        new BigDecimal(account.get("available").toString()).subtract(retain).max(BigDecimal.ZERO);
    BigDecimal rate = new BigDecimal(account.getOrDefault("transferFeeRate", "0").toString());
    return cash.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.DOWN).min(limit(account));
  }

  private WorkflowPlan.Node node(
      String id,
      Map<String, Object> source,
      BigDecimal amount,
      String bank,
      String account,
      String name) {
    return new WorkflowPlan.Node(
        id,
        "transfer",
        Map.of(
            "bank",
            source.get("bank"),
            "accountId",
            source.get("id"),
            "amount",
            amount.toPlainString(),
            "toBank",
            bank,
            "toAccountId",
            account,
            "toName",
            name),
        List.of());
  }

  public WorkflowPlan accept(String user, String id) {
    var row =
        db.one(
                "SELECT * FROM backend_proposals WHERE user_id=? AND id=? AND state='pending'",
                user,
                id)
            .orElseThrow(() -> new BackendException(404, "funding_proposal_not_found"));
    if (!com.pocketbanker.data.BackendStore.time(row, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "proposal_expired");
    if ("tuition".equals(row.get("kind")))
      throw new BackendException(400, "tuition_recipient_confirmation_required");
    if (!profile.personReferencesCurrent(user, "proposal", id))
      throw new BackendException(409, "proposal_person_changed");
    return vault.open(
        user, com.pocketbanker.data.BackendStore.text(row, "plan_cipher"), WorkflowPlan.class);
  }
}
