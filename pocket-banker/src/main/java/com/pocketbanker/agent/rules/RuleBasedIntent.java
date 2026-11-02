package com.pocketbanker.agent.rules;

import com.pocketbanker.orchestration.IntentResult;
import com.pocketbanker.orchestration.RiskLevel;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Deterministic slot extraction; dates/ages/account suffixes never become amounts. */
public class RuleBasedIntent {
  public IntentResult recognize(String original) {
    return recognize(original, "rule", Instant.now());
  }

  public IntentResult recognize(String original, String messageId, Instant now) {
    var speech = OperationSpeech.inspect(original);
    String text = speech.text();
    Map<String, Object> slots = new LinkedHashMap<>();
    var monetary = MoneyTokens.extract(text);
    BigDecimal amount = monetary.amount();
    if (amount != null) slots.put("amount", amount.toPlainString());
    if (monetary.error() != null) {
      slots.put("amountInvalid", true);
      slots.put("amountError", monetary.error());
    }
    if (speech.blockedReason() != null) {
      slots.put("operationIntent", speech.blockedReason());
      slots.put("operationIntentClarify", speech.clarify());
    }
    slots.putAll(ExecutionTimeSlots.extract(text, now));
    Matcher bank =
        Pattern.compile("(?:Bank\\s*|银行\\s*|用\\s*)([ABCFabcf])(?:行|银行)?|([ABCFabcf])行")
            .matcher(text);
    if (bank.find())
      slots.put(
          "bank", (bank.group(1) == null ? bank.group(2) : bank.group(1)).toUpperCase(Locale.ROOT));
    Matcher name =
        Pattern.compile("(?:给|向|帮)([\\p{IsHan}A-Za-z0-9+]{1,30}?)(?=(?:转|汇|的生日|订|买|送|安排|过生日|[,，。！？、]|$))")
        .matcher(text);
    if (name.find()) slots.put("to_name", name.group(1));
    else {
      var after = Pattern.compile("转账给([\\p{IsHan}A-Za-z0-9+]{1,30})").matcher(text);
      if (after.find()) slots.put("to_name", after.group(1));
    }
    Matcher memo =
        Pattern.compile("备注\\s*[:：]?\\s*([“\"‘'][^”\"’']*[”\"’']|[^，,。；;！？!?\\r\\n]+)")
            .matcher(Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC));
    if (memo.find()) slots.put("memo", memo.group(1).strip());
    Matcher due = Pattern.compile("(20\\d{2})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").matcher(text);
    if (due.find())
      try {
        slots.put(
            "due_date",
            LocalDate.of(
                    Integer.parseInt(due.group(1)),
                    Integer.parseInt(due.group(2)),
                    Integer.parseInt(due.group(3)))
                .toString());
      } catch (Exception ignored) {
      }
    Matcher month = Pattern.compile("(\\d{1,2})月").matcher(text);
    if (month.find()) slots.put("month", Integer.parseInt(month.group(1)));
    if (text.contains("本月") || text.contains("这个月")) {
      slots.put("month", now.atZone(java.time.ZoneId.of("Asia/Shanghai")).getMonthValue());
      slots.put("year", now.atZone(java.time.ZoneId.of("Asia/Shanghai")).getYear());
    }
    Matcher year = Pattern.compile("(20\\d{2})年").matcher(text);
    if (year.find()) slots.put("year", Integer.parseInt(year.group(1)));
    String command =
        text.contains("改到") || text.contains("改期")
            ? "reschedule"
            : any(text, "暂停这个任务", "暂停任务", "暂停执行")
                ? "pause"
                : any(text, "继续这个任务", "恢复任务", "继续执行")
                    ? "resume"
                    : any(text, "人工接管", "交给我处理")
                        ? "manual"
                        : any(text, "取消这个任务", "取消任务", "算了") ? "cancel" : null;
    if (command != null) slots.put("taskCommand", command);
    String instruction = text;
    if (slots.get("to_name") instanceof String recipient)
      instruction = instruction.replace(recipient, "对象");
    if (instruction.contains("备注"))
      instruction = instruction.substring(0, instruction.indexOf("备注"));
    if (instruction.matches("(?i).*(美元|美金|USD|\\$).*")) slots.put("currency", "USD");
    else if (instruction.matches("(?i).*(欧元|EUR|€).*")) slots.put("currency", "EUR");
    else if (instruction.matches("(?i).*(港币|港元|HKD).*")) slots.put("currency", "HKD");
    String intent;
    if (command != null) intent = "task_control";
    else if (any(instruction, "什么是", "是什么意思", "解释一下", "解释", "如何", "怎么")
        && any(instruction, "冻结", "解冻", "权限", "影子", "幂等", "T+1", "清算", "理财")) intent = "chat";
    else if (any(instruction, "冻结了吗", "是否冻结", "有没有冻结", "冻结状态")) intent = "query_balance";
    else if (any(instruction, "转账", "汇款")
        || instruction.matches("(?s).*转\\s*[\\d一二三四五六七八九十百千万两].*")
        || instruction.contains("再转")) intent = "transfer";
    else if (any(instruction, "解冻", "解锁", "解除冻结", "恢复卡")) intent = "unfreeze_card";
    else if (any(instruction, "冻结", "冻卡", "挂失", "锁卡", "锁住", "止付"))
      intent = instruction.contains("止付") ? "stop_payment" : "freeze_card";
    else if (instruction.contains("取消鲜花")) intent = "cancel_flower";
    else if (instruction.contains("取消蛋糕")) intent = "cancel_cake";
    else if (any(instruction, "取消订阅", "取消代扣", "退订")) intent = "cancel_subscription";
    else if (any(instruction, "预留", "留出", "存一笔", "冻结一笔")) intent = "reserve";
    else if (any(instruction, "房租", "水电费", "物业费", "给房东")) intent = "transfer";
    else if (any(instruction, "银行卡", "我的卡", "卡片状态", "卡状态", "挂失")) intent = "card_query";
    else if (any(instruction, "任务列表", "定时任务列表")) intent = "task_list";
    else if (any(instruction, "学费", "开学", "教育费")) intent = "tuition_planning";
    else if (any(instruction, "生日", "鲜花", "蛋糕", "纪念日")) intent = "birthday_dag";
    else if (any(instruction, "AA", "aa", "凑钱", "拼单", "收款")) intent = "aa_collect";
    else if (any(instruction, "赎回", "取出理财")) intent = "wealth_redeem";
    else if (instruction.contains("申购")) intent = "wealth_purchase";
    else if (any(instruction, "虚拟卡")) intent = "virtual_card";
    else if (any(instruction, "申请卡", "申请信用卡")) intent = "apply_card";
    else if (any(instruction, "额度", "交易限制")) intent = "card_limits";
    else if (any(instruction, "转账", "汇款")
        || instruction.matches("(?s).*转\\s*[\\d一二三四五六七八九十百千万两].*")
        || instruction.contains("再转")) intent = "transfer";
    else if (any(instruction, "异常", "可疑", "盗刷", "被盗")) intent = "anomaly_detect";
    else if (instruction.contains("周末")
        && any(instruction, "工作日", "平日")
        && any(instruction, "比", "相差", "差多少")) {
      intent = "sandbox_analysis";
      slots.put("analysisType", "weekend_daily_spend");
    } else if (any(instruction, "分析", "统计", "分类", "花在哪")) intent = "bill_analysis";
    else if (any(instruction, "订阅", "代扣", "自动续费", "会员费")) intent = "subscription_detect";
    else if (any(instruction, "晨报", "日报", "早报", "今天情况")) intent = "morning_report";
    else if (any(instruction, "理财", "产品", "收益率")) intent = "wealth";
    else if (any(instruction, "余额", "多少钱", "账户", "剩多少")) intent = "query_balance";
    else if (any(instruction, "流水", "账单", "花了", "消费记录", "最近交易")) intent = "query_transactions";
    else intent = "chat";
    RoleSlots.enrich(intent, text, slots, value -> MoneyTokens.extract(value).amount());
    if (Boolean.TRUE.equals(slots.get("amountInvalid")))
      for (String field : List.of("amount", "limit", "dailyLimit", "flowerAmount", "cakeAmount"))
        slots.remove(field);
    if (speech.blockedReason() != null) {
      boolean read =
          Set.of(
                  "query_balance",
                  "query_transactions",
                  "bill_analysis",
                  "wealth",
                  "subscription_detect",
                  "morning_report",
                  "anomaly_detect",
                  "task_list",
                  "sandbox_analysis")
              .contains(intent);
      if (read
          && Set.of("operation_question", "explanation_or_quotation", "transaction_description")
              .contains(speech.blockedReason())) {
        slots.remove("operationIntent");
        slots.remove("operationIntentClarify");
        slots.remove("amountInvalid");
        slots.remove("amountError");
      } else {
        if (speech.blockedReason().equals("negated_operation")) slots.put("negatedIntent", intent);
        intent = "chat";
        slots.remove("taskCommand");
      }
    }
    amount = slots.containsKey("amount") ? new BigDecimal(slots.get("amount").toString()) : null;
    if (intent.equals("tuition_planning")) {
      slots.remove("executionTimeMissing");
      slots.remove("executionTimeInvalid");
      slots.remove("executionDate");
      slots.remove("triggerType");
      slots.remove("triggerValue");
    }
    RiskLevel risk =
        Set.of("unfreeze_card", "apply_card", "card_limits").contains(intent)
            ? RiskLevel.RED
            : Set.of(
                        "transfer",
                        "wealth_purchase",
                        "wealth_redeem",
                        "birthday_dag",
                        "aa_collect",
                        "virtual_card",
                        "cancel_subscription")
                    .contains(intent)
                ? (amount != null && amount.compareTo(new BigDecimal("1000")) > 0
                    ? RiskLevel.RED
                    : RiskLevel.YELLOW)
                : RiskLevel.GREEN;
    Map<String, IntentResult.Slot> detail = new LinkedHashMap<>();
    slots.forEach(
        (key, value) ->
            detail.put(
                key,
                new IntentResult.Slot(
                    value,
                    key.equals("amount")
                        ? "money"
                        : value instanceof Number
                            ? "integer"
                            : value instanceof Boolean ? "boolean" : "string",
                    messageId,
                    1.0,
                    "explicit")));
    return IntentResult.builder()
        .intent(intent)
        .slots(slots)
        .slotDetails(Map.copyOf(detail))
        .riskLevel(risk)
        .consensus(true)
        .build();
  }

  private boolean any(String text, String... words) {
    for (String word : words) if (text.contains(word)) return true;
    return false;
  }
}
