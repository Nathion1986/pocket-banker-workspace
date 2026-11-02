package com.pocketbanker.contracts;

import java.util.*;

public final class ContractSchemas {
  public static Map<String, Object> creditBill() {
    return object(
        fields(
            "billId",
            string(),
            "accountId",
            string(),
            "currency",
            Map.of("enum", List.of("CNY")),
            "amount",
            money(),
            "paid",
            money(),
            "outstanding",
            money(),
            "dueAt",
            Map.of("type", "string", "format", "date-time"),
            "status",
            Map.of("enum", List.of("paid", "outstanding"))),
        "billId",
        "accountId",
        "currency",
        "amount",
        "paid",
        "outstanding",
        "dueAt",
        "status");
  }

  private ContractSchemas() {}

  public static Map<String, Object> string() {
    return Map.of("type", "string", "minLength", 1, "maxLength", 500);
  }

  public static Map<String, Object> money() {
    return Map.of("type", "string", "pattern", "-?[0-9]{1,15}(?:\\.[0-9]{1,2})?");
  }

  public static Map<String, Object> integer() {
    return Map.of("type", "integer", "minimum", 0);
  }

  public static Map<String, Object> dateTime() {
    return Map.of("type", "string", "format", "date-time");
  }

  public static Map<String, Object> array(Map<String, Object> item) {
    return Map.of("type", "array", "items", item);
  }

  public static Map<String, Object> object(Map<String, Object> properties, String... required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        List.of(required),
        "additionalProperties",
        true);
  }

  public static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i].toString(), pairs[i + 1]);
    return out;
  }

  public static Map<String, Object> account() {
    return object(
        fields(
            "id",
            string(),
            "owner",
            string(),
            "bank",
            Map.of("enum", List.of("A", "B", "C", "F")),
            "currency",
            Map.of("enum", List.of("CNY")),
            "balance",
            money(),
            "reserved",
            money(),
            "available",
            money(),
            "frozen",
            Map.of("type", "boolean"),
            "singleLimit",
            money(),
            "transferFeeRate",
            Map.of("type", "string", "pattern", "[0-9]+(?:\\.[0-9]{1,6})?"),
            "settlementDays",
            integer()),
        "id",
        "owner",
        "bank",
        "currency",
        "balance",
        "reserved",
        "available",
        "frozen",
        "creditLimit",
        "singleLimit",
        "transferFeeRate",
        "settlementDays");
  }

  public static Map<String, Object> transaction() {
    return object(
        fields(
            "id",
            string(),
            "accountId",
            string(),
            "amount",
            money(),
            "currency",
            Map.of("enum", List.of("CNY")),
            "merchant",
            string(),
            "category",
            string(),
            "city",
            string(),
            "occurredAt",
            dateTime(),
            "status",
            Map.of("enum", List.of("POSTED", "PENDING", "CANCELLED")),
            "counterpartyBank",
            Map.of("enum", List.of("A", "B", "C", "F")),
            "counterpartyAccountId",
            string(),
            "counterpartyAmount",
            money()),
        "id",
        "accountId",
        "amount",
        "currency",
        "merchant",
        "category",
        "city",
        "occurredAt");
  }

  public static Map<String, Object> product() {
    return object(
        fields(
            "id",
            string(),
            "name",
            string(),
            "minAmount",
            money(),
            "risk",
            Map.of("type", "integer", "minimum", 1, "maximum", 5),
            "annualRate",
            money(),
            "periodDays",
            integer(),
            "settleDays",
            integer()),
        "id",
        "name",
        "minAmount",
        "risk",
        "annualRate",
        "periodDays",
        "settleDays");
  }

  public static Map<String, Object> holding() {
    return object(
        fields("accountId", string(), "productId", string(), "amount", money()),
        "accountId",
        "productId",
        "amount");
  }

  public static Map<String, Object> reservation() {
    return object(
        fields(
            "id",
            string(),
            "amount",
            money(),
            "remaining",
            money(),
            "purpose",
            string(),
            "status",
            Map.of("enum", List.of("active", "released"))),
        "id",
        "amount",
        "remaining",
        "purpose",
        "status");
  }

  public static Map<String, Object> card() {
    return object(
        fields(
            "cardId",
            string(),
            "accountId",
            string(),
            "status",
            string(),
            "virtual",
            Map.of("type", "boolean"),
            "limit",
            money(),
            "dailyLimit",
            money(),
            "blockedMerchants",
            array(string())),
        "cardId",
        "accountId",
        "status",
        "virtual",
        "limit",
        "dailyLimit",
        "blockedMerchants");
  }

  public static Map<String, Object> subscription() {
    return object(
        fields(
            "id",
            string(),
            "merchant",
            string(),
            "amount",
            money(),
            "status",
            Map.of("enum", List.of("active", "cancelled")),
            "nextDue",
            dateTime(),
            "period",
            Map.of("enum", List.of("weekly", "monthly", "yearly")),
            "evidenceCount",
            integer()),
        "id",
        "merchant",
        "amount",
        "status",
        "nextDue",
        "period",
        "evidenceCount");
  }

  public static Map<String, Object> nullable(Map<String, Object> schema) {
    return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
  }

  public static Map<String, Object> forecast() {
    return object(
        fields(
            "accounts",
            array(account()),
            "complete",
            Map.of("type", "boolean"),
            "failures",
            array(Map.of("type", "object")),
            "timeline",
            array(
                object(
                    fields(
                        "date",
                        Map.of("type", "string", "format", "date"),
                        "flow",
                        money(),
                        "available",
                        money()),
                    "date",
                    "flow",
                    "available")),
            "minimumAvailable",
            money(),
            "firstGap",
            nullable(Map.of("type", "string", "format", "date")),
            "type",
            Map.of("enum", List.of("prediction"))),
        "accounts",
        "complete",
        "failures",
        "timeline",
        "minimumAvailable",
        "firstGap",
        "type");
  }

  public static Map<String, Object> proposal() {
    return object(
        fields(
            "id",
            string(),
            "type",
            Map.of("enum", List.of("proposal")),
            "status",
            Map.of("enum", List.of("pending", "insufficient_liquidity")),
            "gap",
            money(),
            "required",
            money(),
            "steps",
            array(
                object(
                    fields(
                        "id",
                        string(),
                        "actionId",
                        string(),
                        "params",
                        Map.of("type", "object"),
                        "dependsOn",
                        array(string())),
                    "id",
                    "actionId",
                    "params",
                    "dependsOn")),
            "dueDate",
            Map.of("type", "string", "format", "date")),
        "type",
        "status",
        "gap",
        "required");
  }

  public static Map<String, Object> output(String id, String provider) {
    Map<String, Object> p =
        fields(
            "operationId",
            string(),
            "status",
            Map.of("enum", List.of("SUCCEEDED", "PENDING", "CANCELLED")));
    List<String> required = new ArrayList<>(List.of("operationId", "status"));
    switch (id) {
      case "query_balance" -> {
        p.putAll(
            fields(
                "accounts",
                array(account()),
                "complete",
                Map.of("type", "boolean"),
                "errors",
                array(object(fields("bank", string(), "error", string()), "bank", "error")),
                "knownAvailable",
                money(),
                "totalAvailable",
                money()));
        required.addAll(List.of("accounts", "complete", "errors", "knownAvailable"));
      }
      case "query_transactions" -> {
        p.putAll(
            fields(
                "transactions",
                array(transaction()),
                "returned",
                integer(),
                "complete",
                Map.of("type", "boolean")));
        required.addAll(List.of("transactions", "returned", "complete"));
      }
      case "wealth_query" -> {
        p.put(
            "banks",
            array(
                object(
                    fields(
                        "bank",
                        string(),
                        "products",
                        array(product()),
                        "holdings",
                        array(holding())),
                    "bank",
                    "products",
                    "holdings")));
        required.add("banks");
      }
      case "card_query" -> {
        p.put("cards", array(card()));
        required.add("cards");
      }
      case "bill_analysis" -> {
        p.putAll(
            fields(
                "year",
                integer(),
                "month",
                integer(),
                "income",
                money(),
                "expense",
                money(),
                "categories",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    Map.of("type", "number", "minimum", 0)),
                "chart",
                array(object(fields("label", string(), "amount", money()), "label", "amount")),
                "complete",
                Map.of("type", "boolean")));
        required.addAll(
            List.of("year", "month", "income", "expense", "categories", "chart", "complete"));
      }
      case "anomaly_detect" -> {
        p.put(
            "anomalies",
            array(
                object(
                    fields(
                        "transactionId",
                        string(),
                        "accountId",
                        string(),
                        "bank",
                        string(),
                        "amount",
                        money(),
                        "rules",
                        array(string()),
                        "score",
                        integer()),
                    "transactionId",
                    "accountId",
                    "bank",
                    "amount",
                    "rules",
                    "score")));
        required.add("anomalies");
      }
      case "subscription_detect" -> {
        p.put("subscriptions", array(subscription()));
        required.add("subscriptions");
      }
      case "aa_collect" -> {
        p.putAll(
            fields(
                "orderId",
                string(),
                "state",
                Map.of("enum", List.of("open", "completed", "cancelled", "expired")),
                "total",
                Map.of("anyOf", List.of(money(), Map.of("type", "number", "minimum", 0))),
                "members",
                array(
                    object(
                        fields(
                            "person_id",
                            string(),
                            "amount",
                            Map.of(
                                "anyOf", List.of(money(), Map.of("type", "number", "minimum", 0))),
                            "state",
                            string()),
                        "person_id",
                        "amount",
                        "state")),
                "invitations",
                array(
                    object(
                        fields("id", string(), "personId", string(), "state", string()),
                        "id",
                        "personId",
                        "state")),
                "expiresAt",
                dateTime()));
        required.addAll(
            List.of("orderId", "state", "total", "members", "invitations", "expiresAt"));
      }
      case "reminder" -> {
        p.put("notificationId", string());
        required.add("notificationId");
      }
      case "morning_report" -> {
        p.putAll(fields("notificationId", string(), "forecast", forecast()));
        required.addAll(List.of("notificationId", "forecast"));
      }
      case "tuition_planning" -> {
        p.put("proposal", proposal());
        required.add("proposal");
      }
      case "sandbox_analysis" -> {
        p.put(
            "result",
            Map.of(
                "description",
                "Read-only computed JSON result, bounded to 64 KiB; arbitrary analysis shape is intentional"));
        p.put("isolation", Map.of("enum", List.of("process", "docker")));
        required.addAll(List.of("result", "isolation"));
      }
      default -> {
        p.putAll(
            fields(
                "kind", string(), "amount", money(), "currency", Map.of("enum", List.of("CNY"))));
        required.addAll(List.of("kind", "amount", "currency"));
        switch (id) {
          case "transfer" -> {
            p.putAll(
                fields(
                    "toAccountId",
                    string(),
                    "toBank",
                    string(),
                    "fromAccountId",
                    string(),
                    "fee",
                    money(),
                    "settleAt",
                    dateTime()));
            required.addAll(List.of("toAccountId", "toBank", "fromAccountId", "fee", "settleAt"));
          }
          case "reserve", "release", "release_budget" -> {
            p.put("reservationId", string());
            required.add("reservationId");
          }
          case "freeze_card", "unfreeze_card", "stop_payment" -> {
            p.put("frozen", Map.of("type", "boolean"));
            required.add("frozen");
          }
          case "wealth_purchase", "wealth_redeem" -> {
            p.putAll(fields("productId", string(), "settleAt", dateTime()));
            required.add("productId");
          }
          case "apply_card", "virtual_card" -> {
            p.putAll(
                fields("cardId", string(), "limit", money(), "virtual", Map.of("type", "boolean")));
            required.addAll(List.of("cardId", "limit", "virtual"));
          }
          case "card_limits" -> {
            p.putAll(
                fields(
                    "cardId",
                    string(),
                    "limit",
                    money(),
                    "dailyLimit",
                    money(),
                    "blockedMerchants",
                    array(string())));
            required.addAll(List.of("cardId", "limit", "dailyLimit", "blockedMerchants"));
          }
          case "card_payment" -> {
            p.putAll(fields("cardId", string(), "merchant", string(), "fromAccountId", string()));
            required.addAll(List.of("cardId", "merchant", "fromAccountId"));
          }
          case "credit_repay" -> {
            p.putAll(
                fields(
                    "billId",
                    string(),
                    "fromAccountId",
                    string(),
                    "outstandingBefore",
                    money(),
                    "outstandingAfter",
                    money()));
            required.addAll(
                List.of("billId", "fromAccountId", "outstandingBefore", "outstandingAfter"));
          }
          case "cancel_subscription" -> {
            p.putAll(
                fields(
                    "subscriptionId",
                    string(),
                    "subscriptionStatus",
                    Map.of("enum", List.of("cancelled"))));
            required.addAll(List.of("subscriptionId", "subscriptionStatus"));
          }
          case "order_flower", "order_cake", "cancel_flower", "cancel_cake" -> {
            p.putAll(
                fields(
                    "orderId",
                    string(),
                    "paymentOperationId",
                    string(),
                    "fulfillmentStatus",
                    Map.of("enum", List.of("pending_payment", "paid", "fulfilled", "cancelled"))));
            required.addAll(List.of("orderId", "fulfillmentStatus"));
          }
          default -> throw new IllegalArgumentException("missing_action_output_contract:" + id);
        }
      }
    }
    return object(p, required.toArray(String[]::new));
  }
}
