package com.pocketbanker.contracts;

import java.util.*;

public record ActionSpec(
    String id,
    String version,
    String provider,
    String risk,
    boolean involvesMoney,
    boolean countsDaily,
    boolean reversible,
    Set<String> triggers,
    Map<String, String> fields,
    Set<String> required,
    Map<String, Object> outputSchema,
    Compensation compensation,
    String idempotencyKey) {
  public record Compensation(String action, String condition, Long windowSeconds) {}

  public boolean readOnly() {
    return id.startsWith("query_")
        || Set.of(
                "wealth_query",
                "card_query",
                "bill_analysis",
                "anomaly_detect",
                "subscription_detect",
                "sandbox_analysis",
                "tuition_planning")
            .contains(id);
  }

  public void validateOutput(Map<String, Object> output) {
    SchemaValidator.validate(output, outputSchema, 502, "action_result_contract_violation");
    if ("PENDING".equals(output.get("status"))
        && Set.of("transfer", "wealth_redeem").contains(id)
        && output.get("settleAt") == null)
      throw new BackendException(502, "missing_settlement_time");
  }

  @com.fasterxml.jackson.annotation.JsonProperty("inputSchema")
  public Map<String, Object> inputSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    fields.forEach(
        (name, type) -> {
          Map<String, Object> property = new LinkedHashMap<>();
          property.put("type", type.equals("money") ? "string" : type);
          if (type.equals("money"))
            property.put("pattern", "(?=.*[1-9])[0-9]{1,13}(?:\\.[0-9]{1,2})?");
          if (type.equals("string")) {
            property.put("minLength", 1);
            property.put("maxLength", name.equals("code") ? 16000 : 500);
          }
          if (type.equals("integer")) {
            property.put("minimum", 0);
            property.put("maximum", 1000000);
          }
          if (type.equals("array")) {
            property.put("items", Map.of("type", "string", "minLength", 1, "maxLength", 100));
            property.put("minItems", name.equals("blockedMerchants") ? 0 : 1);
            property.put("maxItems", 30);
            property.put("uniqueItems", true);
          }
          if (Set.of("bank", "toBank").contains(name))
            property.put("enum", List.of("A", "B", "C", "F"));
          if (name.equals("currency")) property.put("enum", List.of("CNY"));
          if (name.equals("datasetId"))
            property.put("enum", List.of("transactions", "confirmed_events"));
          if (name.equals("maxRisk")) {
            property.put("minimum", 1);
            property.put("maximum", 5);
          }
          if (name.equals("month")) {
            property.put("minimum", 1);
            property.put("maximum", 12);
          }
          if (name.equals("year")) {
            property.put("minimum", 2000);
            property.put("maximum", 2100);
          }
          if (name.equals("limit") && type.equals("integer")) property.put("minimum", 1);
          if (Set.of("dueAt").contains(name)) property.put("format", "date-time");
          if (Set.of("dueDate").contains(name)) property.put("format", "date");
          properties.put(name, property);
        });
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        required,
        "additionalProperties",
        false);
  }

  public void validate(Map<String, Object> input, boolean allowReferences) {
    if (input == null
        || input.size() > 30
        || !fields.keySet().containsAll(input.keySet())
        || !input.keySet().containsAll(required))
      throw new BackendException(400, "invalid_action_parameters");
    for (var e : input.entrySet()) {
      Object value = e.getValue();
      String type = fields.get(e.getKey());
      if (value == null) throw new BackendException(400, "null_action_parameter");
      if (allowReferences
          && value instanceof String s
          && s.matches("\\$[A-Za-z0-9_-]{1,40}\\.[A-Za-z0-9_]{1,80}")) continue;
      @SuppressWarnings("unchecked")
      var property =
          (Map<String, Object>)
              ((Map<String, Object>) inputSchema().get("properties")).get(e.getKey());
      SchemaValidator.validate(value, property, 400, "invalid_action_parameters");
      switch (type) {
        case "money" -> {
          if (!(value instanceof String))
            throw new BackendException(400, "money_must_be_decimal_string");
          Money.positive(value);
        }
        case "string" -> {
          if (!(value instanceof String s)
              || s.isBlank()
              || s.length() > (e.getKey().equals("code") ? 16000 : 500))
            throw new BackendException(400, "invalid_string_parameter");
        }
        case "integer" -> {
          try {
            long v = Long.parseLong(value.toString());
            if (!(value instanceof Number) || v < 0 || v > 1000000)
              throw new IllegalArgumentException();
          } catch (Exception x) {
            throw new BackendException(400, "invalid_integer_parameter");
          }
        }
        case "array" -> {
          if (!(value instanceof List<?> l)
              || (l.isEmpty() && !e.getKey().equals("blockedMerchants"))
              || l.size() > 30
              || l.stream().anyMatch(x -> !(x instanceof String s) || s.length() > 100))
            throw new BackendException(400, "invalid_array_parameter");
        }
        case "boolean" -> {
          if (!(value instanceof Boolean))
            throw new BackendException(400, "invalid_boolean_parameter");
        }
        default -> throw new IllegalStateException("unknown_contract_type");
      }
    }
    if (!allowReferences)
      SchemaValidator.validate(input, inputSchema(), 400, "invalid_action_parameters");
    if (input.containsKey("bank") && !Set.of("A", "B", "C", "F").contains(input.get("bank")))
      throw new BackendException(400, "invalid_bank");
    if (input.containsKey("toBank") && !Set.of("A", "B", "C", "F").contains(input.get("toBank")))
      throw new BackendException(400, "invalid_to_bank");
    if (id.equals("sandbox_analysis")
        && Boolean.TRUE.equals(input.get("generated"))
        && (!input.keySet().containsAll(Set.of("analysisType", "month", "year"))
            || !"weekend_daily_spend".equals(input.get("analysisType"))
            || !"transactions".equals(input.get("datasetId"))))
      throw new BackendException(400, "generated_analysis_scope_required");
    if (input.containsKey("currency") && !"CNY".equals(input.get("currency")))
      throw new BackendException(400, "unsupported_currency");
  }
}
