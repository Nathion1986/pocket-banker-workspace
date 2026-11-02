package com.pocketbanker.actions.workflow;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.notifications.NotificationService;
import com.pocketbanker.prediction.PredictionService;
import com.pocketbanker.profile.ProfileService;
import com.pocketbanker.sandbox.ReadOnlySandbox;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemActions implements SystemActionPort {
  private final BankBindingService bindings;
  private final BankAdapters banks;
  private final BackendStore db;
  private final NotificationService notifications;
  private final PredictionService prediction;
  private final com.pocketbanker.prediction.CashflowLedger cashflows;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final ProfileService profile;
  private final ReadOnlySandbox sandbox;
  private final com.pocketbanker.accounts.BoundTransactionReader statements;
  private final com.pocketbanker.security.VaultCodec vault;

  public Map<String, Object> execute(
      String user, String operation, String action, Map<String, Object> input) {
    return switch (action) {
      case "bill_analysis" -> bill(user, input);
      case "anomaly_detect" -> anomalies(user, input);
      case "subscription_detect" -> subscriptions(user, input);
      case "aa_collect" -> transaction.execute(status -> aa(user, operation, input));
      case "reminder" -> Map.of(
          "status",
          "SUCCEEDED",
          "notificationId",
          notifications.publish(
              user,
              operation,
              "reminder",
              Map.of("title", input.get("title")),
              Instant.parse(input.get("dueAt").toString())));
      case "morning_report" -> morning(user, operation);
      case "tuition_planning" -> Map.of(
          "status",
          "SUCCEEDED",
          "proposal",
          prediction.tuition(
              user,
              String.valueOf(input.get("eventId")),
              String.valueOf(input.get("amount")),
              String.valueOf(input.get("dueDate"))));
      case "sandbox_analysis" -> analyze(user, input);
      default -> throw new BackendException(400, "system_action_not_registered");
    };
  }

  private Map<String, Object> analyze(String user, Map<String, Object> input) {
    if (!Boolean.TRUE.equals(input.get("generated")))
      return sandbox.analyze(
          input.get("code").toString(), dataset(user, input.get("datasetId").toString()));
    if (!"transactions".equals(input.get("datasetId"))
        || !"weekend_daily_spend".equals(input.get("analysisType")))
      throw new BackendException(400, "analysis_scope_required");
    var verifier = new com.pocketbanker.sandbox.AnalysisVerifier();
    var data =
        verifier.daily(
            transactions(user),
            ((Number) input.get("year")).intValue(),
            ((Number) input.get("month")).intValue(),
            db.now());
    var output = sandbox.analyzeGenerated(input.get("code").toString(), data);
    output.put("result", verifier.verify(output.get("result"), data));
    output.put("verified", true);
    output.put(
        "period",
        Map.of(
            "year",
            input.get("year"),
            "month",
            input.get("month"),
            "through",
            data.get(data.size() - 1).get("date")));
    return output;
  }

  public List<Map<String, Object>> transactions(String user) {
    return statements.read(user, Map.of());
  }

  private Map<String, Object> bill(String user, Map<String, Object> input) {
    int year =
        Integer.parseInt(
            String.valueOf(
                input.getOrDefault("year", db.now().atZone(ZoneId.of("Asia/Shanghai")).getYear())));
    int month =
        Integer.parseInt(
            String.valueOf(
                input.getOrDefault(
                    "month", db.now().atZone(ZoneId.of("Asia/Shanghai")).getMonthValue())));
    if (year < 2000 || year > 2100 || month < 1 || month > 12)
      throw new BackendException(400, "invalid_bill_period");
    BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
    Map<String, BigDecimal> categories = new TreeMap<>();
    for (var row : transactions(user)) {
      var at =
          OffsetDateTime.parse(row.get("occurredAt").toString())
              .atZoneSameInstant(ZoneId.of("Asia/Shanghai"));
      if (at.getYear() != year || at.getMonthValue() != month) continue;
      BigDecimal value = new BigDecimal(row.get("amount").toString());
      if (value.signum() > 0) income = income.add(value);
      else {
        expense = expense.subtract(value);
        categories.merge(row.get("category").toString(), value.negate(), BigDecimal::add);
      }
    }
    return Map.of(
        "status",
        "SUCCEEDED",
        "year",
        year,
        "month",
        month,
        "income",
        income.toPlainString(),
        "expense",
        expense.toPlainString(),
        "categories",
        categories,
        "chart",
        categories.entrySet().stream()
            .map(e -> Map.of("label", e.getKey(), "amount", e.getValue().toPlainString()))
            .toList(),
        "complete",
        true);
  }

  public Map<String, Object> anomalies(String user) {
    return anomalies(user, Map.of());
  }

  public Map<String, Object> anomalies(String user, Map<String, Object> input) {
    var rows = statements.read(user, input);
    Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> groups = new HashMap<>();
    for (var row : rows) {
      BigDecimal value = new BigDecimal(row.get("amount").toString());
      Instant at = OffsetDateTime.parse(row.get("occurredAt").toString()).toInstant();
      if (value.signum() >= 0
          || at.isBefore(db.now().minusSeconds(86400 * 30L))
          || at.isAfter(db.now())) continue;
      boolean preciseTime = !"day".equals(row.get("timePrecision"));
      if (preciseTime)
        groups
            .computeIfAbsent(
                row.get("bank")
                    + ":"
                    + row.get("accountId")
                    + ":"
                    + row.get("merchant")
                    + ":"
                    + value.toPlainString(),
                k -> new ArrayList<>())
            .add(row);
      List<String> rules = new ArrayList<>();
      if (value.abs().compareTo(new BigDecimal("3000")) >= 0) rules.add("large_debit");
      if ("境外".equals(row.get("city"))) rules.add("foreign_city");
      if (preciseTime && at.atZone(ZoneId.of("Asia/Shanghai")).getHour() < 5)
        rules.add("night_time");
      if (!rules.isEmpty())
        mergeAnomaly(
            hits,
            row,
            rules,
            rules.contains("foreign_city") && rules.contains("large_debit") ? 90 : 50);
    }
    for (var group : groups.values()) {
      group.sort(Comparator.comparing(r -> r.get("occurredAt").toString()));
      for (int i = 1; i < group.size(); i++) {
        var current = group.get(i);
        Instant b = OffsetDateTime.parse(current.get("occurredAt").toString()).toInstant();
        Instant a = OffsetDateTime.parse(group.get(i - 1).get("occurredAt").toString()).toInstant();
        if (Duration.between(a, b).getSeconds() <= 120)
          mergeAnomaly(hits, current, List.of("duplicate_debit"), 80);
        if (i >= 2
            && new BigDecimal(current.get("amount").toString())
                    .abs()
                    .compareTo(new BigDecimal("300"))
                <= 0) {
          Instant first =
              OffsetDateTime.parse(group.get(i - 2).get("occurredAt").toString()).toInstant();
          if (Duration.between(first, b).getSeconds() <= 600)
            mergeAnomaly(hits, current, List.of("high_frequency"), 85);
        }
      }
    }
    return Map.of("status", "SUCCEEDED", "anomalies", new ArrayList<>(hits.values()));
  }

  @SuppressWarnings("unchecked")
  private void mergeAnomaly(
      Map<String, Map<String, Object>> hits,
      Map<String, Object> row,
      List<String> rules,
      int score) {
    String key = row.get("bank") + ":" + row.get("accountId") + ":" + row.get("id");
    var hit =
        hits.computeIfAbsent(
            key,
            k ->
                new LinkedHashMap<>(
                    Map.of(
                        "transactionId",
                        row.get("id"),
                        "bank",
                        row.get("bank"),
                        "accountId",
                        row.get("accountId"),
                        "amount",
                        new BigDecimal(row.get("amount").toString()).abs().toPlainString(),
                        "city",
                        row.get("city"),
                        "rules",
                        new ArrayList<String>(),
                        "score",
                        0)));
    var merged = new LinkedHashSet<>((List<String>) hit.get("rules"));
    merged.addAll(rules);
    hit.put("rules", new ArrayList<>(merged));
    hit.put("occurredAt", row.get("occurredAt"));
    hit.put("score", Math.max(((Number) hit.get("score")).intValue(), score));
  }

  private Map<String, Object> subscriptions(String user) {
    return subscriptions(user, Map.of());
  }

  private Map<String, Object> subscriptions(String user, Map<String, Object> input) {
    List<Map<String, Object>> output = new ArrayList<>();
    if (input.containsKey("bank"))
      bindings.requireAccount(user, input.get("bank").toString(), input.get("accountId"));
    for (var b : bindings.list(user))
      if ("active".equals(b.get("state"))
          && (!input.containsKey("bank") || input.get("bank").equals(b.get("bank")))
          && (!input.containsKey("accountId")
              || input.get("accountId").equals(b.get("account_id")))) {
        for (var record :
            banks.require(b.get("bank").toString()).subscriptions(b.get("account_id").toString())) {
          var copy = new LinkedHashMap<>(record);
          copy.put("bank", b.get("bank"));
          copy.put("accountId", b.get("account_id"));
          output.add(copy);
        }
      }
    return Map.of("status", "SUCCEEDED", "subscriptions", output);
  }

  @Transactional
  public Map<String, Object> aa(String user, String operation, Map<String, Object> input) {
    db.lockUser(user);
    var prior =
        db.one(
            "SELECT * FROM backend_aa_orders WHERE operation_key=? AND user_id=?", operation, user);
    if (prior.isPresent()) return aaView(user, text(prior.get(), "id"));
    List<String> participants = (List<String>) input.get("participants");
    if (new HashSet<>(participants).size() != participants.size() || participants.isEmpty())
      throw new BackendException(400, "invalid_aa_participants");
    Set<String> owned = new HashSet<>();
    profile.persons(user).forEach(p -> owned.add(p.get("id").toString()));
    if (!owned.containsAll(participants)) throw new BackendException(404, "participant_not_found");
    BigDecimal total = Money.positive(input.get("amount"));
    Instant due = Instant.parse(input.get("dueAt").toString());
    if (!due.isAfter(db.now())) throw new BackendException(400, "aa_deadline_in_past");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_aa_orders VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        operation,
        total,
        "CNY",
        "open",
        due,
        db.now());
    if (total.movePointRight(2).longValueExact() < participants.size())
      throw new BackendException(400, "aa_amount_too_small");
    long cents = total.movePointRight(2).longValueExact(),
        base = cents / participants.size(),
        extra = cents % participants.size();
    for (int i = 0; i < participants.size(); i++) {
      BigDecimal share = BigDecimal.valueOf(base + (i < extra ? 1 : 0), 2);
      db.update(
          "INSERT INTO backend_aa_members VALUES(?,?,?,?,?)",
          id,
          participants.get(i),
          share,
          "pending",
          null);
    }
    for (var personId : participants) {
      var person =
          profile.persons(user).stream()
              .filter(p -> personId.equals(p.get("id")))
              .findFirst()
              .orElseThrow();
      String recipient = null;
      if (person.containsKey("accountId") && person.get("bank") != null)
        recipient =
            banks
                .require(person.get("bank").toString())
                .account(person.get("accountId").toString())
                .get("owner")
                .toString();
      String invite = UUID.randomUUID().toString(),
          token = UUID.randomUUID() + "-" + UUID.randomUUID();
      db.update(
          "INSERT INTO backend_aa_invitations VALUES(?,?,?,?,?,?,?,?,?)",
          invite,
          id,
          personId,
          user,
          recipient,
          com.pocketbanker.security.PerceptionAccess.hash(token),
          vault.seal(user, token),
          "pending",
          db.now());
      if (recipient != null) {
        var share =
            db.one(
                    "SELECT amount FROM backend_aa_members WHERE order_id=? AND person_id=?",
                    id,
                    personId)
                .orElseThrow();
        notifications.publish(
            recipient,
            "aa-recipient-" + invite,
            "aa_payment_request",
            Map.of(
                "invitationId",
                invite,
                "orderId",
                id,
                "amount",
                share.get("amount").toString(),
                "currency",
                "CNY",
                "expiresAt",
                due.toString()),
            db.now());
        db.update("UPDATE backend_aa_invitations SET state='queued' WHERE id=?", invite);
      }
    }
    notifications.publish(
        user,
        "aa-invite-" + id,
        "aa_request",
        Map.of("orderId", id, "participants", participants, "amount", total.toPlainString()),
        db.now());
    return aaView(user, id);
  }

  @Transactional
  public Map<String, Object> aaView(String user, String id) {
    expireAa();
    var row =
        db.one("SELECT * FROM backend_aa_orders WHERE user_id=? AND id=?", user, id)
            .orElseThrow(() -> new BackendException(404, "aa_order_not_found"));
    var invitations =
        db
            .rows("SELECT * FROM backend_aa_invitations WHERE order_id=? ORDER BY person_id", id)
            .stream()
            .map(
                r -> {
                  Map<String, Object> view = new LinkedHashMap<>();
                  view.put("id", r.get("id"));
                  view.put("personId", r.get("person_id"));
                  view.put("state", r.get("state"));
                  if (Set.of("pending", "queued").contains(r.get("state")))
                    view.put("shareToken", vault.open(user, text(r, "token_cipher"), String.class));
                  return view;
                })
            .toList();
    return Map.of(
        "status",
        "SUCCEEDED",
        "orderId",
        id,
        "state",
        text(row, "state"),
        "total",
        row.get("total"),
        "expiresAt",
        time(row, "expires_at").toString(),
        "members",
        db.rows(
            "SELECT person_id,amount,state,payment_reference FROM backend_aa_members WHERE order_id=? ORDER BY person_id",
            id),
        "invitations",
        invitations);
  }

  @Transactional
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelay = 60000,
      initialDelayString = "${backend.proactive-initial-ms:5000}")
  public void expireAa() {
    db.update(
        "UPDATE backend_aa_orders SET state='expired' WHERE state='open' AND expires_at<=?",
        db.now());
    db.update(
        "UPDATE backend_aa_invitations SET state='expired' WHERE state IN ('pending','queued') AND order_id IN (SELECT id FROM backend_aa_orders WHERE state='expired')");
    for (var order :
        db.rows("SELECT DISTINCT order_id FROM backend_aa_invitations WHERE state='expired'"))
      cancelInvites(text(order, "order_id"));
  }

  @Transactional
  public Map<String, Object> cancelAa(String user, String id) {
    db.lockUser(user);
    aaView(user, id);
    if (!db.rows("SELECT person_id FROM backend_aa_members WHERE order_id=? AND state='paid'", id)
        .isEmpty()) throw new BackendException(409, "aa_payment_already_received");
    db.update("UPDATE backend_aa_orders SET state='cancelled' WHERE id=? AND state='open'", id);
    db.update(
        "UPDATE backend_aa_invitations SET state='cancelled' WHERE order_id=? AND state IN ('pending','queued')",
        id);
    cancelInvites(id);
    return aaView(user, id);
  }

  private void cancelInvites(String id) {
    for (var r :
        db.rows("SELECT id,recipient_user_id FROM backend_aa_invitations WHERE order_id=?", id))
      if (r.get("recipient_user_id") != null)
        notifications.cancel(text(r, "recipient_user_id"), "aa-recipient-" + r.get("id"));
  }

  public Map<String, Object> invitation(String user, String id, String token) {
    expireAa();
    var r =
        db.one("SELECT * FROM backend_aa_invitations WHERE id=?", id)
            .orElseThrow(() -> new BackendException(404, "aa_invitation_not_found"));
    boolean owned = user.equals(r.get("recipient_user_id"));
    boolean shared =
        r.get("recipient_user_id") == null
            && token != null
            && java.security.MessageDigest.isEqual(
                com.pocketbanker.security.PerceptionAccess.hash(token)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                text(r, "token_hash").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!owned && !shared) throw new BackendException(404, "aa_invitation_not_found");
    var order =
        db.one("SELECT * FROM backend_aa_orders WHERE id=?", r.get("order_id")).orElseThrow();
    var member =
        db.one(
                "SELECT amount,state FROM backend_aa_members WHERE order_id=? AND person_id=?",
                r.get("order_id"),
                r.get("person_id"))
            .orElseThrow();
    return Map.of(
        "invitationId",
        id,
        "orderId",
        r.get("order_id"),
        "state",
        order.get("state"),
        "amount",
        member.get("amount"),
        "currency",
        "CNY",
        "expiresAt",
        time(order, "expires_at").toString(),
        "payeeAccounts",
        bindings.list(text(r, "owner_id")).stream()
            .filter(b -> "active".equals(b.get("state")))
            .map(b -> Map.of("bank", b.get("bank"), "accountId", b.get("account_id")))
            .toList());
  }

  @Transactional
  public Map<String, Object> invitationPaid(String user, String invite, String reference) {
    invitation(user, invite, null);
    var r = db.one("SELECT * FROM backend_aa_invitations WHERE id=?", invite).orElseThrow();
    aaPaid(text(r, "owner_id"), text(r, "order_id"), text(r, "person_id"), reference);
    return invitation(user, invite, null);
  }

  @Transactional
  public Map<String, Object> aaPaid(String user, String id, String person, String reference) {
    db.lockUser(user);
    aaView(user, id);
    var order = db.one("SELECT * FROM backend_aa_orders WHERE id=?", id).orElseThrow();

    var member =
        db.one(
                "SELECT * FROM backend_aa_members WHERE order_id=? AND person_id=? FOR UPDATE",
                id,
                person)
            .orElseThrow(() -> new BackendException(404, "aa_member_not_found"));
    if ("paid".equals(text(member, "state"))) {
      if (!Objects.equals(reference, text(member, "payment_reference")))
        throw new BackendException(409, "aa_payment_conflict");
      return aaView(user, id);
    }
    if (!"open".equals(text(order, "state")) || !time(order, "expires_at").isAfter(db.now()))
      throw new BackendException(409, "aa_order_inactive");
    if (reference == null || reference.length() > 160 || reference.isBlank())
      throw new BackendException(400, "payment_reference_required");
    var personRow =
        profile.persons(user).stream()
            .filter(p -> person.equals(p.get("id")))
            .findFirst()
            .orElseThrow(() -> new BackendException(404, "participant_not_found"));
    if (!personRow.containsKey("bank") || !personRow.containsKey("accountId"))
      throw new BackendException(409, "participant_payment_account_required");
    String payerBank = personRow.get("bank").toString(),
        payerAccount = personRow.get("accountId").toString();
    var payer = banks.require(payerBank).account(payerAccount);
    var paid = banks.require(payerBank).outcome(reference, payer.get("owner").toString());
    boolean destination =
        bindings.list(user).stream()
            .anyMatch(
                binding ->
                    "active".equals(binding.get("state"))
                        && Objects.equals(binding.get("bank"), paid.get("toBank"))
                        && Objects.equals(binding.get("account_id"), paid.get("toAccountId")));
    if (!"SUCCEEDED".equals(paid.get("status"))
        || !"transfer".equals(paid.get("kind"))
        || !payerAccount.equals(paid.get("fromAccountId"))
        || !destination
        || new BigDecimal(paid.get("amount").toString())
                .compareTo(new BigDecimal(member.get("amount").toString()))
            != 0) throw new BackendException(409, "aa_payment_not_verified");
    if (db.one("SELECT order_id FROM backend_aa_members WHERE payment_reference=?", reference)
        .isPresent()) throw new BackendException(409, "aa_payment_already_used");
    db.update(
        "UPDATE backend_aa_members SET state='paid',payment_reference=? WHERE order_id=? AND person_id=?",
        reference,
        id,
        person);
    db.update(
        "UPDATE backend_aa_invitations SET state='paid' WHERE order_id=? AND person_id=?",
        id,
        person);
    if (db.rows("SELECT person_id FROM backend_aa_members WHERE order_id=? AND state<>'paid'", id)
        .isEmpty()) db.update("UPDATE backend_aa_orders SET state='completed' WHERE id=?", id);
    return aaView(user, id);
  }

  private Map<String, Object> morning(String user, String operation) {
    var forecast = prediction.forecast(user, 30);
    LocalDate day = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    List<Map<String, Object>> reminders = new ArrayList<>();
    for (var event :
        db.rows(
            "SELECT * FROM events WHERE user_id=? AND status='confirmed' AND superseded_by IS NULL ORDER BY due_date",
            user)) {
      String kind = text(event, "kind");
      String sourceDate = text(event, "due_date");
      if (sourceDate == null && (!"payment_due".equals(kind) || event.get("recurrence") == null))
        continue;
      LocalDate due = sourceDate == null ? day : LocalDate.parse(sourceDate);
      if ("calendar_birthday".equals(kind)) {
        boolean upcoming = false;
        for (int n = 0; n <= 7; n++)
          if (MonthDay.from(due).equals(MonthDay.from(day.plusDays(n)))) upcoming = true;
        if (!upcoming) continue;
      } else if (Set.of("payment_due", "tuition_due").contains(kind)) {
        if (event.get("recurrence") != null) {
          boolean found = false;
          for (int n = 0; n <= 7; n++) {
            LocalDate at = day.plusDays(n);
            if (event.get("recurrence").equals("FREQ=MONTHLY;BYMONTHDAY=" + at.getDayOfMonth())
                && !at.isBefore(due)) {
              due = at;
              found = true;
              break;
            }
          }
          if (!found) continue;
        } else if (due.isAfter(day.plusDays(7))) continue;
        if (cashflows
                .remaining(user, text(event, "id"), due, Money.positive(event.get("amount")))
                .signum()
            == 0) continue;
      } else if (due.isBefore(day) || due.isAfter(day.plusDays(7))) continue;
      reminders.add(Map.of("id", event.get("id"), "kind", kind, "due_date", due.toString()));
    }
    String id =
        notifications.publish(
            user,
            operation,
            "morning",
            Map.of("forecast", forecast, "events", reminders),
            db.now());
    if (new BigDecimal(forecast.get("minimumAvailable").toString()).signum() < 0)
      notifications.publish(
          user,
          operation + ":gap",
          "liquidity_gap",
          Map.of("minimumAvailable", forecast.get("minimumAvailable"), "type", "prediction"),
          db.now());
    LocalDate today = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    for (var event : reminders) {
      String kind = String.valueOf(event.get("kind")), date = String.valueOf(event.get("due_date"));
      if (kind.equals("calendar_birthday")) {
        LocalDate birthday = LocalDate.parse(date);
        boolean upcoming = false;
        for (int offset = 0; offset <= 3; offset++)
          if (MonthDay.from(birthday).equals(MonthDay.from(today.plusDays(offset))))
            upcoming = true;
        if (upcoming)
          notifications.publish(
              user,
              operation + ":birthday:" + event.get("id"),
              "birthday",
              Map.of("eventId", event.get("id"), "date", date),
              db.now());
      }
    }
    var recurring = (List<Map<String, Object>>) subscriptions(user).get("subscriptions");
    for (var item : recurring)
      if ("active".equals(item.get("status"))
          && !Instant.parse(item.get("nextDue").toString())
              .isAfter(db.now().plusSeconds(86400 * 7)))
        notifications.publish(
            user, operation + ":renewal:" + item.get("id"), "subscription_renewal", item, db.now());
    return Map.of("status", "SUCCEEDED", "notificationId", id, "forecast", forecast);
  }

  public Map<String, Object> datasetSchemas() {
    return Map.of(
        "transactions",
        Map.of(
            "fields",
            List.of(
                "id",
                "accountId",
                "amount",
                "currency",
                "merchant",
                "category",
                "city",
                "occurredAt",
                "bank"),
            "readOnly",
            true),
        "confirmed_events",
        Map.of(
            "fields",
            List.of("event_type", "kind", "amount", "due_date", "status"),
            "readOnly",
            true));
  }

  private List<Map<String, Object>> dataset(String user, String name) {
    if (name.equals("transactions")) return transactions(user);
    if (name.equals("confirmed_events")) {
      var rows =
          db.rows(
              "SELECT event_type,kind,amount,due_date,status FROM events WHERE user_id=? AND status='confirmed' AND event_type<>'prediction'",
              user);
      for (var row : rows)
        if (row.get("amount") != null)
          row.put("amount", new BigDecimal(row.get("amount").toString()).toPlainString());
      return rows;
    }
    throw new BackendException(400, "dataset_not_allowed");
  }

  @Transactional
  public Map<String, Object> compensate(
      String user, String operation, String action, Map<String, Object> input) {
    if (action.equals("reminder")) {
      notifications.cancel(user, operation);
      return Map.of("status", "CANCELLED");
    }
    if (action.equals("aa_collect")) {
      var row =
          db.one(
                  "SELECT id,state FROM backend_aa_orders WHERE user_id=? AND operation_key=?",
                  user,
                  operation)
              .orElseThrow(() -> new BackendException(404, "aa_order_not_found"));
      if (!db.rows(
              "SELECT person_id FROM backend_aa_members WHERE order_id=? AND state='paid'",
              text(row, "id"))
          .isEmpty()) throw new BackendException(409, "aa_payment_already_received");
      cancelAa(user, text(row, "id"));
      return Map.of("status", "CANCELLED");
    }
    throw new BackendException(409, "system_action_not_reversible");
  }
}
