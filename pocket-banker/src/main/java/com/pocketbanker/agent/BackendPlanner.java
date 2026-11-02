package com.pocketbanker.agent;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.orchestration.IntentResult;
import com.pocketbanker.profile.ProfileService;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BackendPlanner {
  private final BankBindingService bindings;
  private final ProfileService profile;
  private final BackendStore db;
  private final ActionCatalog catalog;
  private final ActionAvailability availability;
  private final StructuredPlanningService structured;
  private final LongTailAnalysisPlanner longTail;
  private final com.pocketbanker.security.BackendAudit audit;

  public record Planning(
      WorkflowPlan plan, List<String> missing, Map<String, Object> slots, String intent) {}

  public Planning plan(
      String user, String request, String text, IntentResult intent, Map<String, Object> previous) {
    String kind = intent.getIntent();
    Map<String, Object> slots = new LinkedHashMap<>(intent.getSlots());
    if (slots.containsKey("operationIntent"))
      return new Planning(
          null,
          Boolean.TRUE.equals(slots.get("operationIntentClarify"))
              ? List.of("operation_intent")
              : List.of(),
          slots,
          "chat");
    if (Boolean.TRUE.equals(slots.get("amountInvalid")))
      return new Planning(null, List.of("amount"), slots, kind);
    if (previous.get("pendingSlots") instanceof Map<?, ?> saved) {
      if (bareAmountWouldReplaceKnownValue(text, intent, saved))
        return new Planning(
            null,
            List.of("explicit_amount_revision"),
            new LinkedHashMap<>((Map<String, Object>) saved),
            previous.get("pendingIntent").toString());
      // 只有"像是对上一问的补充回答"才继承 pendingIntent；长句新话题（规则识别为 chat）不并入门户
      boolean looksLikeAnswer =
          text.length() <= 8
              || text.matches("[0-9.]+(?:元)?")
              || text.contains("对，")
              || text.contains("是的");
      if (kind.equals(previous.get("pendingIntent"))
          || (kind.equals("chat") && looksLikeAnswer)
          || text.contains("改成")
          || text.contains("再转")
          || text.matches("[0-9.]+(?:元)?")) {
        kind = previous.getOrDefault("pendingIntent", "transfer").toString();
        Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) saved);
        merged.putAll(slots);
        if (saved.get("executionDate") instanceof String day) {
          var time =
              com.pocketbanker.agent.rules.ExecutionTimeSlots.extract(
                  text, db.now(), LocalDate.parse(day));
          if (time.containsKey("triggerType")) {
            merged.remove("executionTimeInvalid");
            merged.remove("executionTimeMissing");
          }
          merged.putAll(time);
        }
        slots = merged;
        if (text.matches("[0-9.]+(?:元)?"))
          slots.put("amount", Money.positive(text.replace("元", "")).toPlainString());
      }
    }
    if (text.contains("再转") && previous.get("lastSlots") instanceof Map<?, ?> saved) {
      Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) saved);
      merged.putAll(slots);
      slots = merged;
    }
    List<String> missing = new ArrayList<>();
    String action = kind.equals("wealth") ? "wealth_query" : kind;
    if (kind.equals("chat") && structured.enabled()) {
      // 规则未命中 → GLM 开放理解：口语映射到意图+槽位（金额中文化数字、相对日期归一）
      Map<String, Object> features = new LinkedHashMap<>();
      features.put("text", text);
      features.put("today", db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
      features.put("banks", List.of("A", "B", "C"));
      try {
        List<String> names = new ArrayList<>();
        profile.persons(user).forEach(p -> names.add(String.valueOf(p.get("name"))));
        features.put("persons", names);
      } catch (RuntimeException ignored) {
      }
      var understood = structured.understand(features);
      if (understood != null) {
        String guessed = String.valueOf(understood.getOrDefault("intent", "chat"));
        if (Set.of("query_balance","query_transactions","bill_analysis","anomaly_detect",
                "subscription_detect","wealth_query","wealth_purchase","wealth_redeem","transfer",
                "reserve","tuition_planning","birthday_dag","aa_collect","freeze_card",
                "unfreeze_card","apply_card","card_limits","cancel_subscription","morning_report")
            .contains(guessed)) {
          kind = guessed;
          if (understood.get("slots") instanceof Map<?, ?> llmSlots)
            for (var entry : llmSlots.entrySet()) {
              String key = String.valueOf(entry.getKey());
              if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank())
                slots.putIfAbsent(key, entry.getValue());
            }
          action = kind; // 关键：kind 被 GLM 改写后，节点组装用的 action 必须同步刷新
        } else if (understood.get("ask") instanceof String ask && !ask.isBlank()) {
          return new Planning(null, List.of("model_ask"), Map.of("ask", ask), "chat");
        }
      }
    }
    if (Set.of("chat", "task_list").contains(kind))
      return new Planning(null, List.of(), slots, kind);
    if (slots.containsKey("currency") && !"CNY".equals(slots.get("currency")))
      return new Planning(null, List.of("supported_currency"), slots, kind);
    String bank = slots.get("bank") == null ? null : slots.get("bank").toString();
    boolean needsBank =
        Set.of(
                "transfer",
                "freeze_card",
                "unfreeze_card",
                "stop_payment",
                "wealth_purchase",
                "wealth_redeem",
                "apply_card",
                "virtual_card",
                "card_limits",
                "cancel_subscription",
                "birthday_dag")
            .contains(kind);
    if (needsBank && bank == null) {
      String neededAction = kind.equals("birthday_dag") ? "reserve" : action;
      List<String> available =
          bindings.list(user).stream()
              .filter(b -> "active".equals(b.get("state")))
              .map(b -> b.get("bank").toString())
              .filter(b -> availability.bankSupports(b, neededAction))
              .distinct()
              .toList();
      Object preferred = profile.value(user, "preferred_bank").orElse(null);
      if (preferred != null && available.contains(preferred.toString()))
        bank = preferred.toString();
      else if (available.size() == 1) bank = available.get(0);
      else missing.add("bank");
    }
    if (bank != null) slots.put("bank", bank);
    if (Set.of("transfer", "wealth_purchase", "wealth_redeem", "birthday_dag", "aa_collect")
            .contains(kind)
        && !slots.containsKey("amount")) missing.add("amount");
    if (kind.equals("transfer")) {
      if (!slots.containsKey("to_name")) missing.add("recipient");
      else {
        Optional<Map<String, Object>> person;
        try {
          person = profile.resolve(user, slots.get("to_name").toString());
        } catch (BackendException e) {
          if (e.getMessage().equals("recipient_ambiguous"))
            return new Planning(null, List.of("recipient_choice"), slots, kind);
          throw e;
        }
        if (person.isEmpty() || !person.get().containsKey("accountId"))
          missing.add("confirmed_recipient_account");
        else {
          slots.put("toBank", person.get().get("bank"));
          slots.put("toAccountId", person.get().get("accountId"));
        }
      }
    }
    if (Set.of("wealth_purchase", "wealth_redeem").contains(kind)
        && !slots.containsKey("productId")) missing.add("productId");
    if (kind.equals("cancel_subscription") && !slots.containsKey("subscriptionId"))
      missing.add("subscriptionId");
    if (kind.equals("aa_collect")) {
      if (slots.get("participantNames") instanceof List<?> names) {
        List<String> selected = new ArrayList<>();
        for (Object name : names) {
          try {
            var person = profile.resolve(user, name.toString());
            if (person.isEmpty()) missing.add("confirmed_participant");
            else selected.add(person.get().get("id").toString());
          } catch (BackendException e) {
            missing.add("participant_choice");
          }
        }
        if (selected.size() == names.size()) slots.put("participants", selected);
      }
      if (!slots.containsKey("participants")) missing.add("participants");
      if (!slots.containsKey("dueAt")) missing.add("dueAt");
    }
    if (Set.of("cancel_flower", "cancel_cake").contains(kind)
        && !slots.containsKey("originalOperation")) missing.add("originalOperation");
    if (kind.equals("tuition_planning")) {
      // 感知→规划直连：无显式 eventId 时自动关联最近一条已确认的学费/缴费事件（画像时间线驱动规划）
      if (!slots.containsKey("eventId")) {
        var seeded =
            db.rows(
                "SELECT id,due_date,amount FROM events WHERE user_id=? AND kind IN ('tuition_due','payment_due') "
                    + "AND status='confirmed' AND superseded_by IS NULL ORDER BY created_at DESC LIMIT 1",
                user);
        if (!seeded.isEmpty()) {
          slots.put("eventId", BackendStore.text(seeded.get(0), "id"));
          if (!slots.containsKey("due_date") && BackendStore.text(seeded.get(0), "due_date") != null)
            slots.put("due_date", BackendStore.text(seeded.get(0), "due_date"));
          if (!slots.containsKey("amount") && BackendStore.text(seeded.get(0), "amount") != null)
            slots.put("amount", BackendStore.text(seeded.get(0), "amount"));
        }
      }
      if (!slots.containsKey("eventId")) missing.add("confirmed_tuition_event");
      if (!slots.containsKey("due_date")) missing.add("dueDate");
    }
    if (kind.equals("birthday_dag")) {
      if (!slots.containsKey("to_name")) missing.add("recipient");
      if (!slots.containsKey("flowerAmount")) missing.add("flowerAmount");
      if (!slots.containsKey("cakeAmount")) missing.add("cakeAmount");
    }
    if (kind.equals("sandbox_analysis") && !slots.containsKey("month"))
      missing.add("analysis_period");
    if (Boolean.TRUE.equals(slots.get("executionTimeMissing"))) missing.add("execution_time");
    if (Boolean.TRUE.equals(slots.get("executionTimeInvalid"))) missing.add("valid_execution_time");
    if (!missing.isEmpty()) return new Planning(null, List.copyOf(missing), slots, kind);
    Map<String, Object> params = new LinkedHashMap<>();
    for (String key :
        List.of(
            "bank",
            "amount",
            "memo",
            "currency",
            "toBank",
            "toAccountId",
            "productId",
            "subscriptionId",
            "participants",
            "dueAt",
            "month",
            "year",
            "eventId",
            "originalOperation",
            "limit",
            "dailyLimit",
            "cardId",
            "blockedMerchants",
            "accountId",
            "title")) if (slots.containsKey(key)) params.put(key, slots.get(key));
    if (slots.containsKey("to_name")) params.put("toName", slots.get("to_name"));
    if (slots.containsKey("due_date")) params.put("dueDate", slots.get("due_date"));
    List<WorkflowPlan.Node> nodes;
    if (kind.equals("sandbox_analysis")) {
      try {
        String code = longTail.generate(slots.get("analysisType").toString());
        nodes =
            List.of(
                new WorkflowPlan.Node(
                    "analysis",
                    "sandbox_analysis",
                    Map.of(
                        "code",
                        code,
                        "datasetId",
                        "transactions",
                        "analysisType",
                        slots.get("analysisType"),
                        "generated",
                        true,
                        "month",
                        slots.get("month"),
                        "year",
                        slots.getOrDefault(
                            "year", db.now().atZone(ZoneId.of("Asia/Shanghai")).getYear())),
                    List.of()));
      } catch (BackendException e) {
        return new Planning(null, List.of("analysis_provider"), slots, kind);
      }
    } else if (kind.equals("birthday_dag")) {
      String recipient = slots.get("to_name").toString();
      String selectedBank = bank;
      nodes =
          List.of(
              new WorkflowPlan.Node(
                  "reserve",
                  "reserve",
                  Map.of(
                      "bank",
                      selectedBank,
                      "amount",
                      slots.get("amount"),
                      "purpose",
                      "birthday-" + request),
                  List.of()),
              new WorkflowPlan.Node(
                  "flower",
                  "order_flower",
                  Map.of(
                      "bank",
                      selectedBank,
                      "amount",
                      slots.get("flowerAmount"),
                      "recipient",
                      recipient,
                      "reservationId",
                      "$reserve.reservationId"),
                  List.of("reserve")),
              new WorkflowPlan.Node(
                  "cake",
                  "order_cake",
                  Map.of(
                      "bank",
                      selectedBank,
                      "amount",
                      slots.get("cakeAmount"),
                      "recipient",
                      recipient,
                      "reservationId",
                      "$reserve.reservationId"),
                  List.of("flower", "reserve")),
              new WorkflowPlan.Node(
                  "release",
                  "release_budget",
                  Map.of("bank", selectedBank, "reservationId", "$reserve.reservationId"),
                  List.of("reserve", "flower", "cake")),
              new WorkflowPlan.Node(
                  "remind",
                  "reminder",
                  Map.of("title", "生日订单提醒", "dueAt", db.now().plusSeconds(60).toString()),
                  List.of("release")));
    } else {
      var definition = catalog.require(action);
      List<String> absent =
          definition.required().stream()
              .filter(name -> !params.containsKey(name))
              .sorted()
              .toList();
      if (!absent.isEmpty()) return new Planning(null, absent, slots, kind);
      // Use only fields declared by the target Action, never copy unvalidated NLU extras.
      availability.require(user, catalog.require(action), params);
      Map<String, Object> filtered = new LinkedHashMap<>();
      final String resolvedAction = action;
      params.forEach(
          (k, v) -> {
            if (catalog.require(resolvedAction).fields().containsKey(k)) filtered.put(k, v);
          });
      nodes = List.of(new WorkflowPlan.Node("action", resolvedAction, filtered, List.of()));
    }
    WorkflowPlan.Trigger trigger =
        slots.containsKey("triggerType")
            ? new WorkflowPlan.Trigger(
                slots.get("triggerType").toString(), slots.get("triggerValue").toString())
            : new WorkflowPlan.Trigger("immediate", null);
    var draft = new WorkflowPlan("message:" + request, nodes, trigger, null, false);
    try {
      var selected = structured.refine(draft);
      audit.write(
          user,
          null,
          null,
          "planner_candidate",
          Map.of(
              "reason",
              structured.enabled() ? "structured_catalog_plan" : "rule_catalog_plan",
              "traceId",
              com.pocketbanker.security.PerceptionAccess.hash(user + ":" + request)));
      return new Planning(selected, List.of(), slots, kind);
    } catch (BackendException failure) {
      return new Planning(
          null,
          List.of(failure.status() == 503 ? "planner_unavailable" : "planner_valid_plan"),
          slots,
          kind);
    }
  }

  static boolean bareAmountWouldReplaceKnownValue(
      String text, IntentResult intent, Map<?, ?> saved) {
    if (!saved.containsKey("amount")
        || !"chat".equals(intent.getIntent())
        || !intent.getSlots().containsKey("amount")) return false;
    var tokens = com.pocketbanker.agent.rules.MoneyTokens.extract(text).tokens();
    return tokens.size() == 1 && tokens.get(0).raw().equals(text.strip());
  }
}
