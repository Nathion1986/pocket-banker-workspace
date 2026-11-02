package com.pocketbanker.prediction;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.notifications.NotificationService;
import com.pocketbanker.security.VaultCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expected obligations and their verified settlement. A task's generic success is not payment. */
@Service
@RequiredArgsConstructor
public class CashflowLedger {
  private final BackendStore db;
  private final VaultCodec vault;
  private final NotificationService notifications;
  private final com.pocketbanker.accounts.BoundTransactionReader statements;
  private final com.pocketbanker.accounts.BankBindingService bindings;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  public record SettlementInput(
      @jakarta.validation.constraints.NotBlank String bank,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160)
          String accountId,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200)
          String transactionId,
      @jakarta.validation.constraints.NotNull LocalDate dueDate) {}

  public Map<String, Object> match(String user, String event, SettlementInput input) {
    requireObligation(user, event, input.dueDate());
    var rows = statements.read(user, Map.of("bank", input.bank(), "accountId", input.accountId()));
    var debit =
        rows.stream()
            .filter(r -> input.transactionId().equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new BackendException(404, "settlement_transaction_not_found"));
    java.math.BigDecimal signed = new java.math.BigDecimal(debit.get("amount").toString());
    if (signed.signum() >= 0
        || !"CNY".equals(debit.get("currency"))
        || !"POSTED".equals(debit.getOrDefault("status", "POSTED")))
      throw new BackendException(409, "verified_debit_required");
    var at =
        java.time.OffsetDateTime.parse(debit.get("occurredAt").toString())
            .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDate();
    if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(at, input.dueDate())) > 31)
      throw new BackendException(409, "settlement_date_outside_window");
    String identity =
        com.pocketbanker.security.PerceptionAccess.hash(
            input.bank() + ":" + input.accountId() + ":" + input.transactionId());
    return transaction.execute(
        status -> {
          db.lockUser(user);
          bindings.requireScope(user, input.bank(), input.accountId(), "read");
          var bill = requireObligation(user, event, input.dueDate());
          BigDecimal amount = new BigDecimal(bill.get("amount").toString());
          expected(user, event, input.dueDate(), amount);
          var prior =
              db.one(
                  "SELECT * FROM backend_cashflow_settlements WHERE user_id=? AND identity_hash=?",
                  user,
                  identity);
          if (prior.isPresent()) {
            if (!event.equals(prior.get().get("event_id"))
                || !input.dueDate().toString().equals(prior.get().get("due_date")))
              throw new BackendException(409, "transaction_already_matched");
            return settlementView(user, event, input.dueDate());
          }
          var row =
              db.one(
                      "SELECT paid_amount,payment_task_id FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=? FOR UPDATE",
                      user,
                      event,
                      input.dueDate().toString())
                  .orElseThrow();
          String task = text(row, "payment_task_id");
          if (task != null) {
            if (db.one(
                    "SELECT id FROM backend_nodes WHERE task_id=? AND id='pay' AND dispatch_started=TRUE AND state IN ('EXECUTING','BANK_PENDING','RECONCILING')",
                    task)
                .isPresent())
              throw new BackendException(409, "payment_result_unknown_reconcile_first");
            db.update(
                "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,authorization_until=NULL WHERE id=? AND user_id=? AND state IN ('READY','SCHEDULED','RUNNING','RETRY_WAIT','WAITING_CONFIRMATION','WAITING_VERIFICATION')",
                task,
                user);
          }
          BigDecimal paid =
              new BigDecimal(row.get("paid_amount").toString())
                  .add(
                      (debit.get("counterpartyAmount") == null
                          ? signed.negate()
                          : new BigDecimal(debit.get("counterpartyAmount").toString())));
          if (paid.compareTo(amount) > 0)
            throw new BackendException(409, "settlement_exceeds_obligation");
          db.update(
              "INSERT INTO backend_cashflow_settlements VALUES(?,?,?,?,?,?,?,'verified',?)",
              user,
              identity,
              event,
              input.dueDate().toString(),
              (debit.get("counterpartyAmount") == null
                  ? signed.negate()
                  : new BigDecimal(debit.get("counterpartyAmount").toString())),
              input.bank(),
              vault.seal(user, input.accountId()),
              db.now());
          db.update(
              "UPDATE backend_cashflow_occurrences SET paid_amount=?,state=?,settled_at=? WHERE user_id=? AND event_id=? AND due_date=?",
              paid,
              paid.compareTo(amount) == 0 ? "settled" : "expected",
              paid.compareTo(amount) == 0 ? db.now() : null,
              user,
              event,
              input.dueDate().toString());
          if (paid.compareTo(amount) == 0) notifications.cancelSubject(user, event);
          return settlementView(user, event, input.dueDate());
        });
  }

  private Map<String, Object> requireObligation(String user, String event, LocalDate due) {
    var row =
        db.one("SELECT * FROM events WHERE user_id=? AND id=?", user, event)
            .orElseThrow(() -> new BackendException(404, "event_not_found"));
    if (!"confirmed".equals(row.get("status"))
        || row.get("superseded_by") != null
        || !Set.of("payment_due", "tuition_due").contains(row.get("kind"))
        || !"CNY".equals(row.get("currency")))
      throw new BackendException(409, "confirmed_obligation_required");
    String recurrence = text(row, "recurrence"), start = text(row, "due_date");
    if (recurrence == null) {
      if (!due.toString().equals(start))
        throw new BackendException(409, "obligation_date_mismatch");
    } else if (!recurrence.equals("FREQ=MONTHLY;BYMONTHDAY=" + due.getDayOfMonth())
        || start != null && due.isBefore(LocalDate.parse(start)))
      throw new BackendException(409, "obligation_date_mismatch");
    return row;
  }

  private Map<String, Object> settlementView(String user, String event, LocalDate date) {
    var row =
        db.one(
                "SELECT amount,paid_amount,state FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=?",
                user,
                event,
                date.toString())
            .orElseThrow();
    var amount = new BigDecimal(row.get("amount").toString());
    var paid = new BigDecimal(row.get("paid_amount").toString());
    return Map.of(
        "eventId",
        event,
        "dueDate",
        date,
        "amount",
        amount.toPlainString(),
        "paid",
        paid.toPlainString(),
        "remaining",
        amount.subtract(paid).toPlainString(),
        "state",
        row.get("state"),
        "evidence",
        "verified_bank_transaction");
  }

  @Transactional
  public BigDecimal remaining(String user, String event, LocalDate date, BigDecimal amount) {
    if (!expected(user, event, date, amount)) return BigDecimal.ZERO;
    var row =
        db.one(
                "SELECT paid_amount,payment_task_id FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=?",
                user,
                event,
                date.toString())
            .orElseThrow();
    return amount.subtract(new BigDecimal(row.get("paid_amount").toString())).max(BigDecimal.ZERO);
  }

  @Transactional
  public boolean expected(String user, String event, LocalDate date, BigDecimal amount) {
    db.lockUser(user);
    db.update(
        "INSERT INTO backend_cashflow_occurrences(user_id,event_id,due_date,amount,currency,state,created_at) VALUES(?,?,?,?,?,'expected',?) ON CONFLICT DO NOTHING",
        user,
        event,
        date.toString(),
        amount,
        "CNY",
        db.now());
    var row =
        db.one(
                "SELECT * FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=?",
                user,
                event,
                date.toString())
            .orElseThrow();
    if (amount.compareTo(new BigDecimal(row.get("amount").toString())) != 0)
      throw new BackendException(409, "cashflow_source_version_conflict");
    return "expected".equals(text(row, "state"));
  }

  public boolean settled(String user, String event, String date) {
    return db.one(
            "SELECT event_id FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=? AND state='settled'",
            user,
            event,
            date)
        .isPresent();
  }

  @Transactional
  public void bind(String user, String event, String task) {
    var row =
        db.one(
                "SELECT * FROM events WHERE user_id=? AND id=? AND kind='tuition_due' AND status='confirmed' AND superseded_by IS NULL",
                user,
                event)
            .orElseThrow(() -> new BackendException(409, "confirmed_tuition_required"));
    String date = text(row, "due_date");
    if (!expected(user, event, LocalDate.parse(date), new BigDecimal(row.get("amount").toString())))
      throw new BackendException(409, "obligation_already_settled");
    var prior =
        db.one(
                "SELECT payment_task_id FROM backend_cashflow_occurrences WHERE user_id=? AND event_id=? AND due_date=?",
                user,
                event,
                date)
            .orElseThrow();
    if (text(prior, "payment_task_id") != null && !task.equals(text(prior, "payment_task_id"))) {
      var previous =
          db.one(
              "SELECT state FROM backend_tasks WHERE user_id=? AND id=?",
              user,
              text(prior, "payment_task_id"));
      if (previous.isEmpty()
          || !Set.of("CANCELLED", "COMPENSATED", "FAILED").contains(text(previous.get(), "state")))
        throw new BackendException(409, "obligation_already_scheduled");
      if (db.one(
              "SELECT id FROM backend_nodes WHERE task_id=? AND state IN ('EXECUTING','BANK_PENDING','RECONCILING')",
              text(prior, "payment_task_id"))
          .isPresent()) throw new BackendException(409, "payment_result_unknown_reconcile_first");
    }
    db.update(
        "UPDATE backend_cashflow_occurrences SET payment_task_id=? WHERE user_id=? AND event_id=? AND due_date=?",
        task,
        user,
        event,
        date);
  }

  public boolean paymentAmountCurrent(String user, String task, String node, Object amount) {
    var obligations =
        db.rows(
            "SELECT amount,paid_amount,state FROM backend_cashflow_occurrences WHERE user_id=? AND payment_task_id=?",
            user,
            task);
    if (obligations.isEmpty() || !"pay".equals(node)) return true;
    for (var row : obligations)
      if (!"expected".equals(row.get("state"))
          || new BigDecimal(row.get("amount").toString())
                  .subtract(new BigDecimal(row.get("paid_amount").toString()))
                  .compareTo(com.pocketbanker.contracts.Money.positive(amount))
              != 0) return false;
    return true;
  }

  public void reconcileCompensation(String user, String task) {
    var paid =
        db.one(
            "SELECT output_cipher FROM backend_nodes WHERE task_id=? AND id='pay' AND state='COMPENSATED'",
            task);
    if (paid.isEmpty() || paid.get().get("output_cipher") == null) return;
    var output = vault.map(user, text(paid.get(), "output_cipher"));
    if (output.get("amount") == null) return;
    BigDecimal refund = com.pocketbanker.contracts.Money.positive(output.get("amount"));
    db.update(
        "UPDATE backend_cashflow_occurrences SET state='expected',paid_amount=paid_amount-?,settled_at=NULL,settlement_operation=NULL WHERE user_id=? AND payment_task_id=? AND settlement_operation=? AND state='settled' AND paid_amount>=?",
        refund,
        user,
        task,
        task + ":pay",
        refund);
  }

  @Transactional
  public void verifiedTask(String user, String task) {
    var obligations =
        db.rows(
            "SELECT * FROM backend_cashflow_occurrences WHERE user_id=? AND payment_task_id=? AND state='expected'",
            user,
            task);
    if (obligations.isEmpty()) return;
    var payment =
        db.one(
            "SELECT * FROM backend_nodes WHERE task_id=? AND id='pay' AND action_id='transfer' AND state='SUCCEEDED'",
            task);
    if (payment.isEmpty()) return;
    var outcome = vault.map(user, text(payment.get(), "output_cipher"));
    if (!"SUCCEEDED".equals(outcome.get("status"))
        || !"CNY".equals(outcome.get("currency"))
        || outcome.get("amount") == null) return;
    for (var obligation : obligations) {
      if (new BigDecimal(obligation.get("amount").toString())
              .subtract(new BigDecimal(obligation.get("paid_amount").toString()))
              .compareTo(new BigDecimal(outcome.get("amount").toString()))
          != 0) throw new BackendException(409, "payment_obligation_amount_mismatch");
      db.update(
          "UPDATE backend_cashflow_occurrences SET state='settled',paid_amount=amount,settled_at=?,settlement_operation=? WHERE user_id=? AND event_id=? AND due_date=? AND state='expected'",
          db.now(),
          task + ":pay",
          user,
          obligation.get("event_id"),
          obligation.get("due_date"));
      notifications.cancelSubject(user, text(obligation, "event_id"));
    }
  }
}
