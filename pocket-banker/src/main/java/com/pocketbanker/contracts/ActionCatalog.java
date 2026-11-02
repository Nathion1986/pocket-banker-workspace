package com.pocketbanker.contracts;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ActionCatalog {
  private final Map<String, ActionSpec> specs = new LinkedHashMap<>();

  public ActionCatalog() {
    add(
        "credit_repay",
        "bank",
        "YELLOW",
        true,
        true,
        false,
        "bank:string accountId:string amount:money currency:string billId:string",
        "bank accountId amount billId");
    add("query_balance", "bank", "GREEN", false, false, false, "bank:string accountId:string", "");
    add(
        "query_transactions",
        "bank",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string month:integer year:integer limit:integer",
        "");
    add(
        "transfer",
        "bank",
        "YELLOW",
        true,
        true,
        true,
        "bank:string accountId:string amount:money currency:string toBank:string toAccountId:string toName:string memo:string",
        "bank amount toBank toAccountId toName");
    add(
        "reserve",
        "bank",
        "YELLOW",
        true,
        false,
        true,
        "bank:string accountId:string amount:money purpose:string",
        "bank amount purpose");
    add(
        "release",
        "bank",
        "RED",
        false,
        false,
        false,
        "bank:string accountId:string reservationId:string",
        "bank reservationId");
    add(
        "freeze_card",
        "bank",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string",
        "bank");
    add(
        "unfreeze_card",
        "bank",
        "RED",
        false,
        false,
        false,
        "bank:string accountId:string",
        "bank");
    add(
        "stop_payment",
        "bank",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string",
        "bank");
    add(
        "wealth_query",
        "bank",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string maxRisk:integer settleDays:integer",
        "");
    add(
        "wealth_purchase",
        "bank",
        "YELLOW",
        true,
        true,
        false,
        "bank:string accountId:string amount:money productId:string currency:string",
        "bank amount productId");
    add(
        "wealth_redeem",
        "bank",
        "YELLOW",
        true,
        false,
        false,
        "bank:string accountId:string amount:money productId:string currency:string",
        "bank amount productId");
    add(
        "apply_card",
        "bank",
        "RED",
        false,
        false,
        false,
        "bank:string accountId:string limit:money",
        "bank limit");
    add(
        "virtual_card",
        "bank",
        "YELLOW",
        false,
        false,
        false,
        "bank:string accountId:string limit:money",
        "bank limit");
    add(
        "card_limits",
        "bank",
        "RED",
        false,
        false,
        false,
        "bank:string accountId:string cardId:string limit:money dailyLimit:money blockedMerchants:array",
        "bank cardId limit");
    add("card_query", "bank", "GREEN", false, false, false, "bank:string accountId:string", "");
    add(
        "card_payment",
        "bank",
        "YELLOW",
        true,
        true,
        false,
        "bank:string accountId:string cardId:string amount:money merchant:string category:string currency:string",
        "bank cardId amount merchant");
    add("bill_analysis", "system", "GREEN", false, false, false, "month:integer year:integer", "");
    add(
        "anomaly_detect",
        "system",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string",
        "");
    add(
        "subscription_detect",
        "system",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string",
        "");
    add(
        "cancel_subscription",
        "bank",
        "YELLOW",
        false,
        false,
        false,
        "bank:string accountId:string subscriptionId:string",
        "bank subscriptionId");
    add(
        "aa_collect",
        "system",
        "YELLOW",
        false,
        false,
        true,
        "amount:money participants:array dueAt:string currency:string",
        "amount participants dueAt");
    add(
        "order_flower",
        "flower",
        "YELLOW",
        true,
        true,
        true,
        "bank:string accountId:string amount:money reservationId:string recipient:string",
        "bank amount reservationId recipient");
    add(
        "order_cake",
        "cake",
        "YELLOW",
        true,
        true,
        true,
        "bank:string accountId:string amount:money reservationId:string recipient:string",
        "bank amount reservationId recipient");
    add(
        "cancel_flower",
        "flower",
        "YELLOW",
        false,
        false,
        false,
        "originalOperation:string",
        "originalOperation");
    add(
        "cancel_cake",
        "cake",
        "YELLOW",
        false,
        false,
        false,
        "originalOperation:string",
        "originalOperation");
    add(
        "release_budget",
        "bank",
        "GREEN",
        false,
        false,
        false,
        "bank:string accountId:string reservationId:string",
        "bank reservationId");
    add(
        "reminder",
        "system",
        "GREEN",
        false,
        false,
        true,
        "title:string dueAt:string",
        "title dueAt");
    add("morning_report", "system", "GREEN", false, false, false, "", "");
    add(
        "tuition_planning",
        "system",
        "GREEN",
        false,
        false,
        false,
        "amount:money dueDate:string eventId:string",
        "amount dueDate");
    add(
        "sandbox_analysis",
        "sandbox",
        "GREEN",
        false,
        false,
        false,
        "code:string datasetId:string generated:boolean analysisType:string month:integer year:integer",
        "code datasetId");
  }

  private void add(
      String id,
      String provider,
      String risk,
      boolean money,
      boolean daily,
      boolean reversible,
      String fields,
      String required) {
    Map<String, String> f = new LinkedHashMap<>();
    for (String part : fields.split(" "))
      if (!part.isBlank()) {
        String[] pair = part.split(":");
        f.put(pair[0], pair[1]);
      }
    Set<String> r = new HashSet<>();
    for (String s : required.split(" ")) if (!s.isBlank()) r.add(s);
    specs.put(
        id,
        new ActionSpec(
            id,
            "2.0.0",
            provider,
            risk,
            money,
            daily,
            reversible,
            Set.of(
                        "apply_card",
                        "virtual_card",
                        "card_limits",
                        "cancel_flower",
                        "cancel_cake",
                        "release",
                        "release_budget")
                    .contains(id)
                ? Set.of("immediate", "at")
                : Set.of("immediate", "at", "recurring", "event"),
            Map.copyOf(f),
            Set.copyOf(r),
            ContractSchemas.output(id, provider),
            reversible
                ? new ActionSpec.Compensation(
                    id.equals("reserve")
                        ? "release"
                        : id.equals("transfer")
                            ? "cancel_transfer"
                            : id.equals("aa_collect")
                                ? "cancel_aa"
                                : id.equals("reminder") ? "cancel_reminder" : "cancel_" + provider,
                    id.equals("transfer")
                        ? "pending_before_dispatch"
                        : id.equals("reserve")
                            ? "unconsumed_budget"
                            : id.equals("aa_collect")
                                ? "no_payment_received"
                                : id.equals("reminder") ? "before_delivery" : "before_fulfillment",
                    Set.of("flower", "cake").contains(provider) ? 120L : null)
                : null,
            "taskId:nodeId; same key with different parameters is rejected"));
  }

  public ActionSpec require(String id) {
    var s = specs.get(id);
    if (s == null) throw new BackendException(400, "action_not_registered");
    return s;
  }

  public List<ActionSpec> list() {
    return List.copyOf(specs.values());
  }
}
