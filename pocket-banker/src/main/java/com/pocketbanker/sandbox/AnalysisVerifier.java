package com.pocketbanker.sandbox;

import com.pocketbanker.contracts.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Independent reference calculation for a supported long-tail question, including zero-spend days.
 */
@Component
public class AnalysisVerifier {
  public static Map<String, Object> schema() {
    return Map.of(
        "fields",
        Map.of("date", "date", "dayType", "weekend|weekday", "expenseMinor", "integer"),
        "readOnly",
        true);
  }

  public List<Map<String, Object>> daily(
      List<Map<String, Object>> transactions, int year, int month, Instant now) {
    if (year < 2000 || year > 2100 || month < 1 || month > 12)
      throw new BackendException(400, "invalid_bill_period");
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalDate start = LocalDate.of(year, month, 1),
        end = start.withDayOfMonth(start.lengthOfMonth());
    if (start.isAfter(today)) throw new BackendException(400, "analysis_period_in_future");
    if (end.isAfter(today)) end = today;
    Map<LocalDate, Long> amounts = new HashMap<>();
    for (var row : transactions) {
      LocalDate date =
          OffsetDateTime.parse(row.get("occurredAt").toString())
              .atZoneSameInstant(zone)
              .toLocalDate();
      if (date.isBefore(start) || date.isAfter(end)) continue;
      BigDecimal value = new BigDecimal(row.get("amount").toString());
      if (value.signum() < 0)
        amounts.merge(date, value.negate().movePointRight(2).longValueExact(), Math::addExact);
    }
    List<Map<String, Object>> data = new ArrayList<>();
    for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1))
      data.add(
          Map.of(
              "date",
              day.toString(),
              "dayType",
              day.getDayOfWeek().getValue() >= 6 ? "weekend" : "weekday",
              "expenseMinor",
              amounts.getOrDefault(day, 0L)));
    return data;
  }

  public Map<String, Object> expected(List<Map<String, Object>> data) {
    long weekend = 0, weekday = 0;
    int weekendDays = 0, weekdayDays = 0;
    for (var row : data) {
      if (row.get("dayType").equals("weekend")) {
        weekend += ((Number) row.get("expenseMinor")).longValue();
        weekendDays++;
      } else {
        weekday += ((Number) row.get("expenseMinor")).longValue();
        weekdayDays++;
      }
    }
    String weekendMean =
        weekendDays == 0
            ? null
            : BigDecimal.valueOf(weekend, 2)
                .divide(BigDecimal.valueOf(weekendDays), 2, RoundingMode.HALF_EVEN)
                .toPlainString();
    String weekdayMean =
        weekdayDays == 0
            ? null
            : BigDecimal.valueOf(weekday, 2)
                .divide(BigDecimal.valueOf(weekdayDays), 2, RoundingMode.HALF_EVEN)
                .toPlainString();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("weekendTotal", BigDecimal.valueOf(weekend, 2).toPlainString());
    result.put("weekdayTotal", BigDecimal.valueOf(weekday, 2).toPlainString());
    result.put("weekendDays", weekendDays);
    result.put("weekdayDays", weekdayDays);
    result.put("weekendDailyMean", weekendMean);
    result.put("weekdayDailyMean", weekdayMean);
    result.put(
        "difference",
        weekendMean == null || weekdayMean == null
            ? null
            : new BigDecimal(weekendMean).subtract(new BigDecimal(weekdayMean)).toPlainString());
    return result;
  }

  public Map<String, Object> verify(Object output, List<Map<String, Object>> data) {
    var expected = expected(data);
    if (!(output instanceof Map<?, ?> actual) || !actual.keySet().equals(expected.keySet()))
      throw new BackendException(502, "analysis_result_mismatch");
    for (var entry : expected.entrySet()) {
      Object value = actual.get(entry.getKey()), known = entry.getValue();
      if (known == null) {
        if (value != null) throw new BackendException(502, "analysis_result_mismatch");
        continue;
      }
      if (known instanceof Integer count) {
        if (!(value instanceof Number n)
            || new BigDecimal(n.toString()).compareTo(BigDecimal.valueOf(count)) != 0)
          throw new BackendException(502, "analysis_result_mismatch");
      } else {
        if (!(value instanceof String text)
            || !text.matches("-?[0-9]{1,15}(?:\\.[0-9]{1,2})?")
            || new BigDecimal(text).compareTo(new BigDecimal(known.toString())) != 0)
          throw new BackendException(502, "analysis_result_mismatch");
      }
    }
    return expected;
  }
}
