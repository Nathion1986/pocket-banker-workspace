package com.pocketbanker.agent.rules;

import java.math.*;
import java.util.*;
import java.util.regex.*;

/**
 * One non-overlapping lexer: signs, malformed numeric tails and units belong to the same operand.
 */
public final class MoneyTokens {
  private MoneyTokens() {}

  private static final String HAN = "零一二三四五六七八九十百千万亿两点";
  private static final Pattern TOKEN =
      Pattern.compile(
          "(?<![0-9A-Za-z.,+\\-−])((?:[+\\-−]|负\\s*)?(?:[0-9](?:[0-9A-Za-z.+\\-−]|,(?=[0-9]))*|["
              + HAN
              + "]+))\\s*(万亿|亿|万|千|百)?\\s*(元|块)?");
  private static final Pattern LABEL =
      Pattern.compile(
          "转账?|汇款|赎回|申购|预留|预算|总额|一共|锁定|收款|改成|换成|单笔额度|日累计额度|日额度|卡额度|额度|鲜花|花束|蛋糕|手续费|余额|结余|我有|尾号|年龄");
  private static final Set<String> OTHER = Set.of("手续费", "余额", "结余", "我有", "尾号", "年龄");
  private static final Set<String> COMPONENT =
      Set.of("鲜花", "花束", "蛋糕", "单笔额度", "日累计额度", "日额度", "卡额度", "额度");
  private static final BigDecimal MAX = new BigDecimal("9999999999999");

  public record Token(
      String raw, int start, int end, String role, BigDecimal value, String error) {}

  public record Result(BigDecimal amount, String error, List<Token> tokens) {}

  public static Result extract(String text) {
    List<Token> tokens = new ArrayList<>();
    var matcher = TOKEN.matcher(text);
    while (matcher.find()) {
      String number = matcher.group(1), unit = matcher.group(2), currency = matcher.group(3);
      String role = "";
      String prefix = text.substring(Math.max(0, matcher.start() - 32), matcher.start());
      int punctuation =
          Math.max(
              Math.max(prefix.lastIndexOf('，'), prefix.lastIndexOf(',')),
              Math.max(prefix.lastIndexOf('；'), prefix.lastIndexOf(';')));
      var labels = LABEL.matcher(prefix.substring(punctuation + 1));
      while (labels.find()) role = labels.group();
      boolean bareReply = text.strip().equals(matcher.group().strip());
      boolean arabic = number.matches("(?:[+\\-−]|负\\s*)?[0-9].*");
      boolean countSuffix =
          matcher.end() < text.length() && "年月日岁号个笔点时分秒:小".indexOf(text.charAt(matcher.end())) >= 0;
      boolean magnitude = unit != null || number.matches(".*[万亿].*");
      if (countSuffix || (currency == null && !magnitude && !bareReply && role.isBlank())) continue;
      if (!arabic && currency == null && role.isBlank() && !bareReply) continue;
      BigDecimal value = null;
      String error = null;
      try {
        if (number.startsWith("-") || number.startsWith("−") || number.startsWith("负"))
          throw new IllegalArgumentException("negative_amount");
        if (number.startsWith("+") || number.indexOf('点') >= 0)
          throw new IllegalArgumentException("unsupported_number_format");
        if (arabic) {
          if (!number.matches("(?:[0-9]{1,13}|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("invalid_complete_number");
          value = new BigDecimal(number.replace(",", ""));
        } else value = new BigDecimal(chineseInteger(number));
        if (unit != null)
          value =
              value.multiply(
                  new BigDecimal(
                      switch (unit) {
                        case "百" -> "100";
                        case "千" -> "1000";
                        case "万" -> "10000";
                        case "亿" -> "100000000";
                        case "万亿" -> "1000000000000";
                        default -> throw new IllegalArgumentException();
                      }));
        if (value.signum() <= 0 || value.compareTo(MAX) > 0)
          throw new IllegalArgumentException("amount_out_of_range");
        value = value.setScale(2, RoundingMode.UNNECESSARY);
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        error = invalid.getMessage() == null ? "invalid_amount" : invalid.getMessage();
      }
      tokens.add(
          new Token(
              text.substring(matcher.start(), matcher.end()).strip(),
              matcher.start(),
              matcher.end(),
              role,
              value,
              error));
    }
    List<Token> candidates = tokens.stream().filter(t -> !OTHER.contains(t.role())).toList();
    for (Token token : candidates)
      if (token.error() != null) return new Result(null, token.error(), List.copyOf(tokens));
    List<Token> principal = candidates.stream().filter(t -> !COMPONENT.contains(t.role())).toList();
    if (principal.size() == 1)
      return new Result(principal.get(0).value(), null, List.copyOf(tokens));
    return new Result(null, principal.size() > 1 ? "ambiguous_amount" : null, List.copyOf(tokens));
  }

  private static void requireExplicitRemainder(String remainder) {
    if (!remainder.isEmpty() && !remainder.startsWith("零") && remainder.matches("[一二三四五六七八九两]+"))
      throw new IllegalArgumentException("ambiguous_omitted_place_value");
  }

  private static BigInteger chineseInteger(String word) {
    if (word.isEmpty()) return BigInteger.ZERO;
    int billion = word.indexOf('亿');
    if (billion >= 0) {
      if (word.indexOf('亿', billion + 1) >= 0)
        throw new IllegalArgumentException("invalid_chinese_number");
      requireExplicitRemainder(word.substring(billion + 1));
      BigInteger left = chineseInteger(word.substring(0, billion));
      return (left.signum() == 0 ? BigInteger.ONE : left)
          .multiply(BigInteger.valueOf(100000000))
          .add(chineseInteger(word.substring(billion + 1)));
    }
    int tenThousand = word.indexOf('万');
    if (tenThousand >= 0) {
      if (word.indexOf('万', tenThousand + 1) >= 0)
        throw new IllegalArgumentException("invalid_chinese_number");
      requireExplicitRemainder(word.substring(tenThousand + 1));
      BigInteger left = chineseInteger(word.substring(0, tenThousand));
      return (left.signum() == 0 ? BigInteger.ONE : left)
          .multiply(BigInteger.valueOf(10000))
          .add(chineseInteger(word.substring(tenThousand + 1)));
    }
    long total = 0, digit = 0, previous = 10000;
    boolean pendingDigit = false, explicitZero = false;
    for (char c : word.toCharArray()) {
      int d = c == '两' ? 2 : "零一二三四五六七八九".indexOf(c);
      if (d >= 0) {
        if (pendingDigit && digit != 0)
          throw new IllegalArgumentException("ambiguous_chinese_digits");
        if (d == 0) explicitZero = true;
        digit = d;
        pendingDigit = true;
        continue;
      }
      long unit =
          switch (c) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            default -> throw new IllegalArgumentException("invalid_chinese_number");
          };
      if (unit >= previous) throw new IllegalArgumentException("invalid_chinese_unit_order");
      total += (pendingDigit ? digit : 1) * unit;
      digit = 0;
      pendingDigit = false;
      previous = unit;
      explicitZero = false;
    }
    if (total > 0 && pendingDigit && digit != 0 && previous > 10 && !explicitZero)
      throw new IllegalArgumentException("ambiguous_omitted_place_value");
    return BigInteger.valueOf(total + digit);
  }
}
