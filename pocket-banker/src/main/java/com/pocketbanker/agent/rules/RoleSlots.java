package com.pocketbanker.agent.rules;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** One sentence can contain budget, component prices, limits and deadlines with distinct roles. */
public final class RoleSlots {
  private RoleSlots() {}

  private static final String NUMBER =
      "([+\\-−]?(?:[0-9][0-9A-Za-z.,+\\-−]*|[零一二三四五六七八九十百千万亿两点]+)(?:\\s*(?:万亿|亿|万|千|百))?\\s*(?:元|块)?)";

  public static void enrich(
      String kind, String text, Map<String, Object> slots, Function<String, BigDecimal> money) {
    if (kind.equals("birthday_dag")) {
      putMoney(text, "预算|预留|总额|一共", "amount", slots, money);
      putMoney(text, "鲜花|花束", "flowerAmount", slots, money);
      putMoney(text, "蛋糕", "cakeAmount", slots, money);
      if (!slots.containsKey("amount")
          && slots.containsKey("flowerAmount")
          && slots.containsKey("cakeAmount"))
        slots.put(
            "amount",
            new BigDecimal(slots.get("flowerAmount").toString())
                .add(new BigDecimal(slots.get("cakeAmount").toString()))
                .toPlainString());
    }
    if (Set.of("apply_card", "virtual_card", "card_limits").contains(kind)) {
      putMoney(text, "单笔额度|卡额度|额度", "limit", slots, money);
      putMoney(text, "日累计额度|日额度", "dailyLimit", slots, money);
    }
    if (Set.of("wealth_purchase", "wealth_redeem").contains(kind)) {
      var product = Pattern.compile("([A-Z]-[A-Za-z0-9_-]{1,40})").matcher(text);
      if (product.find()) slots.put("productId", product.group(1));
    }
    if (kind.equals("aa_collect")) {
      var participants = Pattern.compile("参与人[:：]?\\s*([^，。；]{1,150})").matcher(text);
      if (participants.find()) {
        var names =
            Arrays.stream(participants.group(1).split("[、,和与]+"))
                .map(String::strip)
                .filter(n -> !n.isBlank())
                .toList();
        if (names.size() <= 30) slots.put("participantNames", names);
      }
      if (slots.containsKey("triggerValue")) slots.put("dueAt", slots.get("triggerValue"));
      slots.remove("triggerType");
      slots.remove("triggerValue");
      slots.remove("executionTimeMissing");
    }
  }

  private static void putMoney(
      String text,
      String labels,
      String key,
      Map<String, Object> slots,
      Function<String, BigDecimal> money) {
    var matcher =
        Pattern.compile(
                "(?:" + labels + ")[^0-9零一二三四五六七八九十百千万亿两点+\\-−]{0,5}" + NUMBER + "(?![0-9.,年月日岁])")
            .matcher(text);
    if (matcher.find()) {
      String raw = matcher.group(1).strip();
      if (raw.matches("[零一二三四五六七八九十百千万亿两]+")) raw += "元";
      BigDecimal amount = money.apply("预算" + raw);
      if (amount != null) slots.put(key, amount.toPlainString());
    }
  }
}
