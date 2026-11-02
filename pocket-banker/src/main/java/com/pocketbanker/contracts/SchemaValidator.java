package com.pocketbanker.contracts;

import java.math.BigDecimal;
import java.util.*;

/** The JSON Schema subset emitted by this application; the same contract runs at the boundary. */
public final class SchemaValidator {
  private SchemaValidator() {}

  public static void validate(Object value, Map<String, Object> schema, int status, String code) {
    try {
      check(value, schema);
    } catch (RuntimeException failure) {
      throw new BackendException(status, code);
    }
  }

  @SuppressWarnings("unchecked")
  private static void check(Object value, Map<String, Object> schema) {
    if (schema.isEmpty()) return;
    if (schema.get("anyOf") instanceof List<?> options) {
      for (Object option : options)
        try {
          check(value, (Map<String, Object>) option);
          return;
        } catch (RuntimeException ignored) {
        }
      throw new IllegalArgumentException();
    }
    if (schema.get("enum") instanceof Collection<?> allowed && !allowed.contains(value))
      throw new IllegalArgumentException();
    switch (Objects.toString(schema.get("type"), "")) {
      case "object" -> {
        if (!(value instanceof Map<?, ?> object)) throw new IllegalArgumentException();
        for (Object required : (Collection<?>) schema.getOrDefault("required", List.of()))
          if (!object.containsKey(required)) throw new IllegalArgumentException();
        Map<String, Object> properties =
            (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        for (var entry : object.entrySet()) {
          Object property = properties.get(entry.getKey());
          if (property instanceof Map<?, ?> child)
            check(entry.getValue(), (Map<String, Object>) child);
          else if (Boolean.FALSE.equals(schema.get("additionalProperties")))
            throw new IllegalArgumentException();
          else if (schema.get("additionalProperties") instanceof Map<?, ?> extra)
            check(entry.getValue(), (Map<String, Object>) extra);
        }
      }
      case "array" -> {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException();
        if (list.size() < ((Number) schema.getOrDefault("minItems", 0)).intValue()
            || list.size() > ((Number) schema.getOrDefault("maxItems", 1000000)).intValue())
          throw new IllegalArgumentException();
        if (Boolean.TRUE.equals(schema.get("uniqueItems"))
            && new HashSet<>(list).size() != list.size()) throw new IllegalArgumentException();
        Map<String, Object> child = (Map<String, Object>) schema.getOrDefault("items", Map.of());
        list.forEach(item -> check(item, child));
      }
      case "string" -> {
        if (!(value instanceof String text)) throw new IllegalArgumentException();
        if (text.length() < ((Number) schema.getOrDefault("minLength", 0)).intValue()
            || text.length() > ((Number) schema.getOrDefault("maxLength", 1000000)).intValue())
          throw new IllegalArgumentException();
        if (schema.get("pattern") instanceof String pattern && !text.matches(pattern))
          throw new IllegalArgumentException();
        if ("date".equals(schema.get("format"))) java.time.LocalDate.parse(text);
        if ("date-time".equals(schema.get("format"))) java.time.OffsetDateTime.parse(text);
      }
      case "number", "integer" -> {
        if (!(value instanceof Number number)) throw new IllegalArgumentException();
        BigDecimal v = new BigDecimal(number.toString());
        if ("integer".equals(schema.get("type")) && v.stripTrailingZeros().scale() > 0)
          throw new IllegalArgumentException();
        if (schema.containsKey("minimum")
            && v.compareTo(new BigDecimal(schema.get("minimum").toString())) < 0)
          throw new IllegalArgumentException();
        if (schema.containsKey("maximum")
            && v.compareTo(new BigDecimal(schema.get("maximum").toString())) > 0)
          throw new IllegalArgumentException();
      }
      case "null" -> {
        if (value != null) throw new IllegalArgumentException();
      }
      case "boolean" -> {
        if (!(value instanceof Boolean)) throw new IllegalArgumentException();
      }
      default -> {}
    }
  }
}
