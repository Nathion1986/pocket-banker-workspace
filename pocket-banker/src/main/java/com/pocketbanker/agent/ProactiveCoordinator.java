package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.actions.workflow.SystemActions;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.notifications.NotificationService;
import com.pocketbanker.profile.ProfileService;
import com.pocketbanker.shadow.ScopedShadowGateway;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProactiveCoordinator {
  private final BackendStore db;
  private final NotificationService notifications;
  private final SystemActions system;
  private final BackendFacade facade;
  private final WorkflowExecutor executor;
  private final WorkflowTransactions tasks;
  private final FlowPerceptionCoordinator flows;
  private final ProfileService profile;
  private final ScopedShadowGateway shadow;

  public Map<String, Object> riskCheck(String user) {
    var report = system.anomalies(user);
    int protective = 0;
    for (Object object : (List<?>) report.get("anomalies")) {
      var anomaly = (Map<String, Object>) object;
      String reference = anomaly.get("transactionId").toString();
      String riskKey =
          com.pocketbanker.security.PerceptionAccess.hash(
              user + ":" + anomaly.get("bank") + ":" + anomaly.get("accountId") + ":" + reference);
      var previous =
          db.one(
              "SELECT id FROM events WHERE user_id=? AND kind='risk_transaction' AND payload_json LIKE ?",
              user,
              "%" + riskKey + "%");
      String riskEvent =
          previous.map(e -> text(e, "id")).orElseGet(() -> java.util.UUID.randomUUID().toString());
      if (previous.isPresent()
          && db.one(
                  "SELECT id FROM event_decisions WHERE event_id=? AND decision LIKE 'risk_%'",
                  riskEvent)
              .isPresent()) continue;
      if (previous.isEmpty())
        db.update(
            "INSERT INTO events(id,user_id,event_type,kind,title,amount,currency,occurred_at,source,confidence,extractor_version,status,created_at,expires_at,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            riskEvent,
            user,
            "finance",
            "risk_transaction",
            "待核实银行风险交易",
            new java.math.BigDecimal(anomaly.getOrDefault("amount", "1.00").toString()),
            "CNY",
            db.now(),
            "flow_mining",
            0.9,
            "risk-rules-v1",
            "candidate",
            db.now(),
            db.now().plusSeconds(86400),
            "{\"riskKey\":\"" + riskKey + "\"}");

      int score = travelAdjusted(user, anomaly);
      if (score >= 80 && anomaly.containsKey("bank")) {
        Map<String, Object> params =
            Map.of("bank", anomaly.get("bank"), "accountId", anomaly.get("accountId"));
        var plan =
            new WorkflowPlan(
                "risk-protect-" + riskKey,
                List.of(new WorkflowPlan.Node("protect", "freeze_card", params, List.of())),
                new WorkflowPlan.Trigger("immediate", null),
                null,
                false);
        facade.createOwned(user, plan);
        protective++;
      }
      notifications.publish(
          user,
          "risk-notice-" + riskKey,
          "urgent_risk",
          Map.of(
              "transactionId",
              reference,
              "eventId",
              riskEvent,
              "score",
              score,
              "suggestion",
              score >= 80 ? "保护性冻结已登记，请核实交易" : "请核实交易与行程"),
          db.now());
    }
    return Map.of("anomalies", report.get("anomalies"), "protectiveTasks", protective);
  }

  private int travelAdjusted(String user, Map<String, Object> anomaly) {
    if (anomaly.get("occurredAt") == null) return ((Number) anomaly.get("score")).intValue();
    LocalDate at =
        OffsetDateTime.parse(anomaly.get("occurredAt").toString())
            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
            .toLocalDate();
    boolean match =
        profile.facts(user).stream()
            .filter(f -> f.get("fact_key").toString().startsWith("travel_window"))
            .map(f -> f.get("value"))
            .filter(Map.class::isInstance)
            .map(v -> (Map<?, ?>) v)
            .anyMatch(
                trip -> {
                  if (!Objects.equals(trip.get("city"), anomaly.get("city"))
                      || !trip.containsKey("startDate")) return false;
                  LocalDate start = LocalDate.parse(trip.get("startDate").toString());
                  LocalDate end =
                      trip.containsKey("endDate")
                          ? LocalDate.parse(trip.get("endDate").toString())
                          : start;
                  return !at.isBefore(start) && !at.isAfter(end);
                });
    if (!match) return ((Number) anomaly.get("score")).intValue();
    List<String> rules = new ArrayList<>((List<String>) anomaly.get("rules"));
    rules.removeAll(List.of("foreign_city", "away_city"));
    int score =
        rules.contains("high_frequency")
            ? 85
            : rules.contains("duplicate_debit") ? 80 : rules.isEmpty() ? 0 : 50;
    anomaly.put("originalScore", anomaly.get("score"));
    anomaly.put("score", score);
    anomaly.put("rules", rules);
    anomaly.put("locationExplainedByTrip", true);
    return score;
  }

  @Scheduled(
      fixedDelayString = "${backend.proactive-poll-ms:60000}",
      initialDelayString = "${backend.proactive-initial-ms:60000}")
  public void poll() {
    shadow.expire();
    for (var expired :
        db.rows(
            "SELECT user_id,id FROM events WHERE status='candidate' AND expires_at<=?", db.now()))
      notifications.cancelSubject(text(expired, "user_id"), text(expired, "id"));
    db.update(
        "UPDATE events SET status='expired' WHERE status='candidate' AND expires_at<=?", db.now());
    db.update(
        "UPDATE backend_proposals SET state='expired' WHERE state='pending' AND expires_at<=?",
        db.now());
    for (var row : db.rows("SELECT DISTINCT user_id FROM backend_users")) {
      String user = text(row, "user_id"),
          day = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
      var pending =
          db.rows(
              "SELECT id,kind,due_date FROM events WHERE user_id=? AND status='candidate' AND superseded_by IS NULL AND (remind_after IS NULL OR remind_after<=?) ORDER BY created_at LIMIT 2",
              user,
              db.now());
      if (!pending.isEmpty())
        notifications.publish(
            user,
            "candidate-summary-" + user + ":" + day,
            "candidate_summary",
            Map.of("events", pending),
            db.now());
      for (var event :
          db.rows(
              "SELECT id,due_date,remind_after FROM events WHERE user_id=? AND status='candidate' AND superseded_by IS NULL AND due_date IS NOT NULL AND (remind_after IS NULL OR remind_after<=?)",
              user,
              db.now())) {
        try {
          LocalDate due = LocalDate.parse(text(event, "due_date"));
          if (!due.isAfter(LocalDate.parse(day).plusDays(3)))
            notifications.publish(
                user,
                "candidate-urgent-"
                    + text(event, "id")
                    + ":"
                    + (time(event, "remind_after") == null
                        ? "initial"
                        : time(event, "remind_after").getEpochSecond()),
                "candidate_urgent",
                Map.of("eventId", text(event, "id")),
                db.now());
        } catch (Exception ignored) {
        }
      }
    }
    for (var source :
        db.rows(
            "SELECT id,user_id FROM perception_sources WHERE kind='FLOW' AND status='active' AND paused=FALSE AND expires_at>?",
            db.now()))
      try {
        flows.sync(text(source, "user_id"), text(source, "id"));
      } catch (BackendException | com.pocketbanker.perception.PerceptionException ignored) {
      }
    tasks.recurringDue().forEach(executor::enqueue);
  }

  @Scheduled(cron = "${backend.morning-cron:0 0 8 * * *}", zone = "Asia/Shanghai")
  public void morning() {
    for (var row : db.rows("SELECT DISTINCT user_id FROM bank_bindings WHERE state='active'")) {
      String user = text(row, "user_id"),
          day = db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
      try {
        system.execute(user, "morning-" + user + ":" + day, "morning_report", Map.of());
      } catch (BackendException ignored) {
      }
    }
  }
}
