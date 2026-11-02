package com.pocketbanker.orchestration;

import com.pocketbanker.profile.CandidateDraft;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** 确定性本地抽取；无需把银行通知原文发给当前尚未接好影子层的 LLM。 */
@Component
public class RuleSignalExtractor {
  private static final Pattern MONEY =
      Pattern.compile(
          "(?<![\\d.,+\\-])(?:(人民币|RMB|CNY|美元|USD|港币|HKD|欧元|EUR|英镑|GBP|[¥￥$£])\\s*)?(\\d{1,15}(?:,\\d{3})*(?:\\.\\d{1,2})?)(?![\\d.,])\\s*(万)?\\s*(元|人民币|美元|港币|欧元|英镑|CNY|USD|HKD|EUR|GBP)?",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE =
      Pattern.compile("(?:(20\\d{2})[年/-])?(\\d{1,2})[月/-](\\d{1,2})日?");
  private static final Pattern MONTHLY = Pattern.compile("每月\\s*(\\d{1,2})[日号]");
  private static final Pattern LAST4 = Pattern.compile("(?:尾号|尾数)\\s*([0-9]{4})(?![0-9])");

  public record Extraction(
      String status, String reason, CandidateDraft candidate, Map<String, Object> evidence) {
    public Extraction(String status, String reason, CandidateDraft candidate) {
      this(status, reason, candidate, Map.of());
    }
  }

  public Extraction extract(String subject, String text, Instant occurredAt) {
    String value =
        Normalizer.normalize((subject == null ? "" : subject + "\n") + text, Normalizer.Form.NFKC);
    if (value.contains("验证码") || value.toLowerCase(Locale.ROOT).contains("one-time password")) {
      return result("filtered", "one_time_code");
    }
    if (Pattern.compile(
            "忽略.{0,12}(指令|规则)|system\\s*prompt|ignore.{0,20}instruction", Pattern.CASE_INSENSITIVE)
        .matcher(value)
        .find()) {
      return result("needs_review", "instruction_like_content");
    }
    if (Pattern.compile("JPY|AUD|CAD|SGD|CHF|BTC|USDT|日元|澳元|加元|新加坡元|比特币", Pattern.CASE_INSENSITIVE)
        .matcher(value)
        .find()) return result("needs_review", "unsupported_currency");
    if (Pattern.compile("已撤销|已取消|交易撤销|交易取消").matcher(value).find())
      return result("needs_review", "cancelled_transaction");
    if (Pattern.compile("机票|航班|行程").matcher(value).find()
        && Pattern.compile("目的地|到达城市|旅行城市").matcher(value).find()) return trip(value);
    boolean alreadyPaid = Pattern.compile("还款成功|缴费成功|已还款|已缴纳|已缴费").matcher(value).find();
    boolean tuition = value.contains("学费") && !alreadyPaid;
    boolean due =
        !alreadyPaid
            && (tuition || Pattern.compile("还款|待还|应还|房贷|月供|缴费|缴纳|到期应还|待付款").matcher(value).find());
    boolean debit =
        alreadyPaid || Pattern.compile("消费|支出|扣款|刷卡|扣费|支付成功|付款成功").matcher(value).find();
    boolean credit = !alreadyPaid && Pattern.compile("入账|收入|到账|存入|收款|收到转账").matcher(value).find();
    boolean risk =
        Pattern.compile("盗刷|可疑交易").matcher(value).find()
            || debit && Pattern.compile("境外|异地").matcher(value).find();
    if (!(due || risk || debit || credit)) return result("needs_review", "unsupported_content");

    Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    int amountOccurrences = 0;
    Map<String, Object> evidence = new LinkedHashMap<>();
    var matcher = MONEY.matcher(value);
    while (matcher.find()) {
      // Naked dates, card suffixes and ages are never amounts.
      if (matcher.group(1) == null && matcher.group(4) == null && matcher.group(3) == null)
        continue;
      String prefix = value.substring(Math.max(0, matcher.start() - 12), matcher.start());
      if (Pattern.compile("(?:余额|额度)[^\\d]{0,8}$").matcher(prefix).find()) continue;
      amountOccurrences++;
      evidence.put("amount", span(value, matcher.start(), matcher.end()));
      String currency = currency(matcher.group(1), matcher.group(4));
      if (currency == null) return result("needs_review", "ambiguous_currency");
      BigDecimal amount = new BigDecimal(matcher.group(2).replace(",", ""));
      if (matcher.group(3) != null) amount = amount.multiply(BigDecimal.valueOf(10000));
      if (amount.signum() <= 0 || amount.precision() - amount.scale() > 15) {
        return result("needs_review", "invalid_amount");
      }
      amount = amount.setScale(2);
      amounts.put(currency + ":" + amount, amount);
    }
    if (amounts.size() != 1 || amountOccurrences != 1)
      return result("needs_review", "ambiguous_amount");
    var amountEntry = amounts.entrySet().iterator().next();
    String currency = amountEntry.getKey().split(":")[0];
    LocalDate date = null;
    String recurrence = null;
    if (due) {
      var monthly = MONTHLY.matcher(value);
      if (monthly.find()) {
        int day = Integer.parseInt(monthly.group(1));
        if (day < 1 || day > 31) return result("needs_review", "invalid_date");
        recurrence = "FREQ=MONTHLY;BYMONTHDAY=" + day;
        evidence.put("recurrence", span(value, monthly.start(), monthly.end()));
        if (monthly.find()) return result("needs_review", "ambiguous_recurrence");
      } else {
        Set<LocalDate> dates = new HashSet<>();
        var dm = DATE.matcher(value);
        LocalDate base = occurredAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        while (dm.find()) {
          try {
            LocalDate found =
                LocalDate.of(
                    dm.group(1) == null ? base.getYear() : Integer.parseInt(dm.group(1)),
                    Integer.parseInt(dm.group(2)),
                    Integer.parseInt(dm.group(3)));
            if (dm.group(1) == null && found.isBefore(base)) found = found.plusYears(1);
            dates.add(found);
            evidence.put("dueDate", span(value, dm.start(), dm.end()));
          } catch (DateTimeException e) {
            return result("needs_review", "invalid_date");
          }
        }
        if (dates.size() != 1) return result("needs_review", "ambiguous_due_date");
        date = dates.iterator().next();
      }
    }
    if (!due && !risk && debit == credit) return result("needs_review", "ambiguous_direction");
    String kind =
        tuition
            ? "tuition_due"
            : due
                ? "payment_due"
                : risk ? "risk_transaction" : debit ? "transaction_debit" : "transaction_credit";
    String label =
        switch (kind) {
          case "tuition_due" -> "学费待缴";
          case "payment_due" -> "待付款提醒";
          case "risk_transaction" -> "待核实风险交易通知";
          case "transaction_debit" -> "支出通知";
          default -> "入账通知";
        };
    var suffix = LAST4.matcher(value);
    String last4 = suffix.find() ? suffix.group(1) : null;
    if (last4 != null) evidence.put("accountLast4", span(value, suffix.start(1), suffix.end(1)));
    return new Extraction(
        "completed",
        "rules_v1",
        new CandidateDraft(
            kind,
            label + " " + currency + " " + amountEntry.getValue().toPlainString(),
            amountEntry.getValue(),
            currency,
            date,
            recurrence,
            last4),
        evidence);
  }

  private Extraction trip(String value) {
    var cities =
        Pattern.compile("(?:目的地|到达城市|旅行城市)[:：\\s]*([\\p{IsHan}A-Za-z· ]{2,40})").matcher(value);
    Set<String> foundCities = new LinkedHashSet<>();
    Map<String, Object> evidence = new LinkedHashMap<>();
    while (cities.find()) {
      foundCities.add(cities.group(1).strip());
      evidence.put("city", span(value, cities.start(1), cities.end(1)));
    }
    if (foundCities.size() != 1) return result("needs_review", "ambiguous_trip_city");
    var dates = DATE.matcher(value);
    List<LocalDate> foundDates = new ArrayList<>();
    try {
      while (dates.find()) {
        if (dates.group(1) == null) return result("needs_review", "explicit_trip_year_required");
        foundDates.add(
            LocalDate.of(
                Integer.parseInt(dates.group(1)),
                Integer.parseInt(dates.group(2)),
                Integer.parseInt(dates.group(3))));
        evidence.put(
            foundDates.size() == 1 ? "dueDate" : "endDate",
            span(value, dates.start(), dates.end()));
      }
    } catch (DateTimeException e) {
      return result("needs_review", "invalid_trip_date");
    }
    if (foundDates.isEmpty() || foundDates.size() > 2)
      return result("needs_review", "ambiguous_trip_dates");
    LocalDate start = foundDates.get(0), end = foundDates.size() == 2 ? foundDates.get(1) : start;
    if (end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366)
      return result("needs_review", "invalid_trip_window");
    return new Extraction(
        "completed",
        "explicit_trip_fields_v1",
        new CandidateDraft(
            "flight_trip",
            "机票行程待确认",
            null,
            null,
            start,
            null,
            null,
            foundCities.iterator().next(),
            end),
        evidence);
  }

  private static Map<String, Object> span(String normalized, int start, int end) {
    return Map.of(
        "start",
        start,
        "end",
        end,
        "hash",
        com.pocketbanker.security.PerceptionAccess.hash(normalized.substring(start, end)),
        "normalization",
        "NFKC-subject-newline-body-v1");
  }

  private static String currency(String prefix, String suffix) {
    String words =
        (Objects.toString(prefix, "") + " " + Objects.toString(suffix, ""))
            .toUpperCase(Locale.ROOT);
    Set<String> currencies = new HashSet<>();
    if (Pattern.compile("美元|USD|\\$").matcher(words).find()) currencies.add("USD");
    if (Pattern.compile("港币|HKD").matcher(words).find()) currencies.add("HKD");
    if (Pattern.compile("欧元|EUR").matcher(words).find()) currencies.add("EUR");
    if (Pattern.compile("英镑|GBP|£").matcher(words).find()) currencies.add("GBP");
    if (Pattern.compile("人民币|RMB|CNY|[¥￥]").matcher(words).find()) currencies.add("CNY");
    if (currencies.size() > 1) return null;
    return currencies.isEmpty() ? "CNY" : currencies.iterator().next();
  }

  private Extraction result(String status, String reason) {
    return new Extraction(status, reason, null);
  }
}
