package com.pocketbanker.agent.rules;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Dates and clock times are independent slots; no implicit payment hour is invented. */
public final class ExecutionTimeSlots {
  private ExecutionTimeSlots() {}

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Pattern HOUR =
      Pattern.compile(
          "(上午|下午|晚上|凌晨|中午)?\\s*([0-9]{1,2}|[零一二三四五六七八九十两]{1,3})[点时:：](?:(半)|([0-9]{1,2})分?)?");
  private static final Pattern DATE =
      Pattern.compile("(20[0-9]{2})[年/-]([0-9]{1,2})[月/-]([0-9]{1,2})日?");

  public static Map<String, Object> extract(String text, Instant now) {
    return extract(text, now, null);
  }

  public static Map<String, Object> extract(String text, Instant now, LocalDate defaultDate) {
    Map<String, Object> result = new LinkedHashMap<>();
    LocalDate day = defaultDate;
    if (text.contains("后天")) day = now.atZone(ZONE).toLocalDate().plusDays(2);
    else if (text.contains("明天")) day = now.atZone(ZONE).toLocalDate().plusDays(1);
    else if (text.contains("今天")) day = now.atZone(ZONE).toLocalDate();
    var date = DATE.matcher(text);
    if (date.find()) {
      try {
        day =
            LocalDate.of(
                Integer.parseInt(date.group(1)),
                Integer.parseInt(date.group(2)),
                Integer.parseInt(date.group(3)));
      } catch (DateTimeException e) {
        return Map.of("executionTimeInvalid", true);
      }
    }
    var time = HOUR.matcher(text);
    if (!time.find()) {
      if (day != null) {
        result.put("executionDate", day.toString());
        result.put("executionTimeMissing", true);
      }
      return result;
    }
    try {
      int hour = number(time.group(2));
      String period = Objects.toString(time.group(1), "");
      if (Set.of("下午", "晚上").contains(period) && hour < 12) hour += 12;
      if (period.equals("中午") && hour < 11) hour += 12;
      if (period.equals("凌晨") && hour == 12) hour = 0;
      int minute =
          time.group(3) != null ? 30 : time.group(4) == null ? 0 : Integer.parseInt(time.group(4));
      LocalTime at = LocalTime.of(hour, minute);
      if (day == null) day = now.atZone(ZONE).toLocalDate();
      if (text.contains("每月")) {
        var monthDay = Pattern.compile("每月\\s*([0-9]{1,2})[日号]").matcher(text);
        if (!monthDay.find()) return Map.of("executionTimeInvalid", true);
        int dom = Integer.parseInt(monthDay.group(1));
        if (dom < 1 || dom > 31) return Map.of("executionTimeInvalid", true);
        result.put("triggerType", "recurring");
        result.put("triggerValue", "0 " + minute + " " + hour + " " + dom + " * *");
        result.put("misfirePolicy", "skip_missed_rounds");
      } else {
        Instant instant = day.atTime(at).atZone(ZONE).toInstant();
        if (!instant.isAfter(now)) return Map.of("executionTimeInvalid", true);
        result.put("triggerType", "at");
        result.put("triggerValue", instant.toString());
      }
      result.put("executionDate", day.toString());
      result.put("executionTimeMissing", false);
      result.put("timeZone", "Asia/Shanghai");
      result.put("timePrecision", "minute");
      return result;
    } catch (RuntimeException e) {
      return Map.of("executionTimeInvalid", true);
    }
  }

  private static int number(String word) {
    if (word.matches("[0-9]+")) return Integer.parseInt(word);
    int value = 0, current = 0;
    for (char c : word.toCharArray()) {
      if (c == '十') {
        value += (current == 0 ? 1 : current) * 10;
        current = 0;
      } else current = c == '两' ? 2 : "零一二三四五六七八九".indexOf(c);
    }
    return value + current;
  }
}
