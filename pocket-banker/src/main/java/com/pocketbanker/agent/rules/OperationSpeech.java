package com.pocketbanker.agent.rules;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Local speech-act boundary before any write proposal, draft replacement or task control. */
public final class OperationSpeech {
  private OperationSpeech() {}

  public record Reading(String text, String blockedReason, boolean clarify) {}

  private static final String WRITE =
      "解除冻结|解冻|解锁|恢复卡|冻结|冻卡|挂失|锁卡|锁住|止付|转账|转(?!述)|汇款|申购|赎回|预留|收款|取消|退订|暂停|恢复任务|继续执行|改期|改到|改成|换成|申请卡|申请信用卡|虚拟卡|额度";
  private static final Pattern WRITES = Pattern.compile(WRITE);
  private static final Pattern NEGATION =
      Pattern.compile(
          "(?:不要|别|勿|不想|不愿|不准|禁止|无需|不需要|不用|不是|并非|没有要求|不打算|不准备|不希望|不允许|暂不)[^，,。；;！？!?]{0,16}(?:"
              + WRITE
              + ")|不\\s*(?:再|要)?\\s*(?:"
              + WRITE
              + ")");
  private static final Pattern QUOTED = Pattern.compile("[“\"‘']([^”\"’']*)[”\"’']");
  private static final Pattern CORRECTION =
      Pattern.compile("(?:^|[，,；;。])\\s*(?:但|不过|现在)?\\s*(?:改为|改用|而是|是|还是|换成)");

  private static final Pattern CONSENT =
      Pattern.compile(
          "(?:等|等待|待|须|必须|需要|先|未经|没有|得到|获得|征得|取得|经过).{0,20}(?:确认|同意|批准|许可|授权)|(?:确认|同意|批准|许可|授权).{0,12}(?:后|之前|才|再执行)");
  private static final Pattern POST_NEGATION =
      Pattern.compile(
          "(?:"
              + WRITE
              + ")[^，,。；;！？!?]{0,40}(?:不允许|不许可|不准|禁止|不可以|不得|不能执行|不可执行|不应执行)|(?:不允许|禁止|不得|不准)\\s*(?:的|执行|操作)?\\s*$");
  private static final Pattern MEMO =
      Pattern.compile("备注\\s*[:：]?\\s*([“\"‘'][^”\"’']*[”\"’']|[^，,。；;！？!?\\r\\n]+)");

  public static Reading inspect(String original) {
    String text =
        Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC).strip();
    if (text.matches("(?s)^(?:请|帮我|替我|麻烦|让你)?\\s*(?:翻译|解释|说明|分析一下|读一下|复述|转述|提醒我).*"))
      return new Reading(text, "explanation_or_quotation", false);
    if (text.matches("(?s).*(?:只是转述|只是在转述|他(?:说|让我)|她(?:说|让我)|别人说|老师说).*"))
      return new Reading(text, "reported_speech", false);

    boolean quotedWrite = false;
    var quotes = QUOTED.matcher(text);
    StringBuffer operative = new StringBuffer();
    while (quotes.find()) {
      boolean action = WRITES.matcher(quotes.group(1)).find();
      quotedWrite |= action;
      quotes.appendReplacement(
          operative, Matcher.quoteReplacement(action ? "引用内容" : quotes.group(1)));
    }
    quotes.appendTail(operative);
    text = operative.toString();
    // A memo ends at its field boundary; later clauses remain operative.
    var memo = MEMO.matcher(text);
    StringBuffer withoutMemo = new StringBuffer();
    while (memo.find()) {
      String value = memo.group(1);
      if (WRITES.matcher(value).find() || CONSENT.matcher(value).find())
        return new Reading(text, "ambiguous_memo_boundary", true);
      memo.appendReplacement(withoutMemo, " ");
    }
    memo.appendTail(withoutMemo);
    text = withoutMemo.toString().strip();
    if (text.isBlank()) return new Reading(text, "description_only", false);
    if (quotedWrite && !WRITES.matcher(text).find())
      return new Reading(text, "quoted_operation", false);

    if (WRITES.matcher(text).find() && CONSENT.matcher(text).find())
      return new Reading(text, "explicit_consent_requires_clarification", true);
    if (POST_NEGATION.matcher(text).find()) return new Reading(text, "negated_operation", false);
    if (text.matches("(?s).*(?:收款人|收款账号|收款账户|付款账户|付款银行|金额|银行)\\s*(?:改为|改成|换成|换为).*"))
      return new Reading(text, "argument_revision_requires_clarification", true);

    if (text.matches("(?s).*(?:如果|假如|倘若|只有.{0,30}才|一旦|等.{0,30}(?:到账|发生|批准).{0,12}(?:再|就)).*"))
      return new Reading(text, "unsupported_condition", true);

    // A positive replacement clause supersedes a negated action, not an arbitrary mention of 改成.
    if (text.matches("(?s).*(?:不要|别|不是|不想|不用|并非).*")) {
      var correction = CORRECTION.matcher(text);
      int start = -1;
      while (correction.find()) start = correction.end();
      if (start >= 0 && WRITES.matcher(text.substring(start)).find())
        return inspect(text.substring(start).strip());
    }
    if (text.matches("(?s).*(?:不要|别|不用)(?:用|从|给|向).{0,16}[，,；;].*"))
      return new Reading(text, "negated_argument_requires_clarification", true);
    for (String clause : text.split("[，,。；;]"))
      if (NEGATION.matcher(clause).find()) return new Reading(text, "negated_operation", false);

    if (text.matches("(?s).*(?:冻结了吗|是否冻结|有没有冻结|冻结状态).*")
        && WRITES.matcher(text).results().count() <= 1) return new Reading(text, null, false);
    if (text.matches(
        "(?s).*(?:会发生什么|会怎么样|有什么(?:后果|影响)|有啥(?:后果|影响)|是什么意思|意味着什么|为什么|会不会|手续费.{0,15}(?:多少|吗)|够吗|是否应该|该不该).*"))
      return new Reading(text, "operation_question", false);
    if (text.matches("(?s).*[?？吗么嘛呢]\\s*$")
        && !text.matches("(?s).*(?:请|帮我|替我|麻烦|立即|马上).*(?:" + WRITE + ").*"))
      return new Reading(text, "operation_question", false);
    if (text.matches("(?s).*(?:已经|曾经).{0,16}(?:转账|汇款|冻结|解冻|取消|申购|赎回).*"))
      return new Reading(text, "historical_operation", false);
    if (text.matches("(?s).*(?:已经|曾经|昨天|刚才).{0,16}(?:转(?:账)?了|冻结了|解冻了|取消了|申购了|赎回了).*"))
      return new Reading(text, "historical_operation", false);
    if (text.matches("(?s).*(?:账单|记录|流水).{0,20}(?:里|中|有一笔).{0,12}(?:转账|汇款).*(?:分析|统计|解释).*"))
      return new Reading(text, "transaction_description", false);

    if (text.matches("(?s).*(?:关于|了解|介绍|讨论|举例|指南|说明书|操作说明).*(?:" + WRITE + ").*")
        || text.matches("(?s).*(?:" + WRITE + ").*(?:指南|说明书|操作说明).*"))
      return new Reading(text, "operation_description", false);
    var actions = WRITES.matcher(text);
    int count = 0;
    while (actions.find()) count++;
    if ((count > 1 && text.matches("(?s).*(?:先|然后|接着|同时|再).*(?:" + WRITE + ").*"))
        || (count > 0 && text.matches("(?s).*先.{0,12}(?:查|查询|读取).{0,20}(?:再|然后|接着).*")))
      return new Reading(text, "compound_operation_requires_order", true);
    if (count > 0) {
      String prefix = "(?:请(?:你)?|麻烦(?:你)?|帮我|替我|给我|立即|马上|赶紧|直接|再|现在|发起|来|开|我(?:想|要|需要|打算)|设置|调整|修改|更改)*";
      String qualifier = "(?:用|从|给|向|帮|把|将|明天|后天|今天|下周|下个月|月底|[周星期][一二三四五六日天末]|本周末|[ABCF]行|[0-9]{1,2}月|[0-9]{4}年)";
      boolean command =
          text.matches(
              "(?s).*(?:^|[，,。；;])\\s*"
                  + prefix
                  + "\\s*(?:(?:"
                  + WRITE
                  + ")|"
                  + qualifier
                  + "[^，,。；;]{0,64}(?:"
                  + WRITE
                  + ")).*");
      if (!command) return new Reading(text, "ambiguous_governing_action", true);
    }
    return new Reading(text, null, false);
  }
}
