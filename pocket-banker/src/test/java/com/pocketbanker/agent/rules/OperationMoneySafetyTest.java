package com.pocketbanker.agent.rules;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Speech acts and complete operands; uses the real project DTO, not the review package's stub. */
class OperationMoneySafetyTest {
  static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
  static final Set<String> WRITES =
      Set.of(
          "freeze_card",
          "stop_payment",
          "unfreeze_card",
          "transfer",
          "task_control",
          "card_limits",
          "wealth_redeem",
          "wealth_purchase",
          "birthday_dag");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "不要冻结A行账户",
        "我不想冻结A行账户",
        "别给妈妈转100元，用A行",
        "冻结A行账户会发生什么？",
        "冻结A行的卡有啥后果？",
        "你会冻结A行账户吗？",
        "如果发现盗刷，再冻结A行账户",
        "只有卡被盗时才止付A行",
        "他说冻结A行账户，我只是转述",
        "请翻译：给妈妈转100元，用A行",
        "帮我解释一下：取消这个任务",
        "别取消这个任务",
        "不要改到明天上午十点",
        "我已经给妈妈转账100元，用A行",
        "我昨天冻结了A行的卡",
        "A行账单里有一笔转账100元，帮我分析一下",
        "不要把金额改成200元",
        "100元够吗？",
        "先查A行余额，再给妈妈转100元"
      })
  void nonCommandsNeverBecomeAnImmediateWrite(String text) {
    var value = new RuleBasedIntent().recognize(text, "safety-message", NOW);
    assertThat(value.getIntent()).as(text).isNotIn(WRITES);
    assertThat(value.getSlots()).doesNotContainKey("taskCommand");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "冻结A行账户|freeze_card", "疑似盗刷，请立即冻结A行账户|freeze_card",
        "不用等了，帮我冻结A行账户|freeze_card", "请帮我冻结A行账户，可以吗？|freeze_card",
        "不要冻结，是解冻A行账户|unfreeze_card", "取消这个任务|task_control",
        "把这个任务改到明天上午十点|task_control", "不要给爸爸转，改为给妈妈转200元，用A行|transfer",
        "给妈妈转100元，用A行|transfer", "用F行给妈妈转一万两千五百元|transfer"
      })
  void affirmativeRequestsAndExplicitCorrectionsRetainTheirActions(String text, String kind) {
    assertThat(new RuleBasedIntent().recognize(text, "positive-message", NOW).getIntent())
        .as(text)
        .isEqualTo(kind);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "给妈妈转1.5万元，用A行|15000.00",
        "给妈妈转2.75千元，用A行|2750.00",
        "给妈妈转一万五千元，用A行|15000.00",
        "给妈妈转一百零五元，用A行|105.00",
        "给妈妈转1,234.50元，用A行|1234.50",
        "给妈妈转0.01元，用A行|0.01",
        "给妈妈转一万亿元，用A行|1000000000000.00",
        "A行余额4200元，给妈妈转100元|100.00",
        "给妈妈转100元，手续费1元，用A行|100.00",
        "给妈妈转100元，用A行，卡尾号1234|100.00",
        "2026年10月9日给妈妈转100元，用A行|100.00"
      })
  void completeAmountsKeepUnitsAndDoNotReadOtherRoles(String text, String amount) {
    assertThat(new RuleBasedIntent().recognize(text, "amount-message", NOW).getSlots())
        .as(text)
        .containsEntry("amount", amount);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "给妈妈转账−100元，用A行", "给妈妈转账-100元，用A行", "给妈妈转账－100元，用A行",
        "给妈妈转1e3元，用A行", "给妈妈转2E4元，用A行", "给妈妈转0元，用A行",
        "给妈妈转1.234元，用A行", "给妈妈转12,34元，用A行", "给妈妈转1..5元，用A行",
        "给妈妈转十点五元，用A行", "给妈妈转账＋100元，用A行", "给妈妈转100元和200元，用A行",
        "给妈妈转10000000000000元，用A行", "单笔额度−100元，用A行", "额度1e3元，用A行"
      })
  void malformedOperandsNeverBecomePositiveSuffixes(String text) {
    var slots = new RuleBasedIntent().recognize(text, "invalid-message", NOW).getSlots();
    assertThat(slots).as(text).doesNotContainKeys("amount", "limit", "dailyLimit");
  }

  @ParameterizedTest
  @ValueSource(strings = {"我不是让你冻结A行账户", "不要执行给妈妈转账100元", "请提醒我解冻A行账户"})
  void negatedGovernorsAndRemindersAreNotBankCommands(String text) {
    assertThat(new RuleBasedIntent().recognize(text, "governor", NOW).getIntent()).isNotIn(WRITES);
  }

  @Test
  void explicitBankCorrectionDoesNotUseTheNegatedAccount() {
    var value = new RuleBasedIntent().recognize("不要用A行，改用B行给妈妈转100元", "bank-correction", NOW);
    assertThat(value.getIntent()).isEqualTo("transfer");
    assertThat(value.getSlots()).containsEntry("bank", "B").containsEntry("amount", "100.00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.5万元", "−100元", "1e3元", "12,34元"})
  void tokenEvidenceCoversTheWholeOperandRatherThanAPositiveSuffix(String operand) {
    String text = "给妈妈转账" + operand;
    var result = MoneyTokens.extract(text);
    assertThat(result.tokens()).hasSize(1);
    var token = result.tokens().get(0);
    assertThat(token.raw()).isEqualTo(operand);
    assertThat(text.substring(token.start(), token.end())).isEqualTo(operand);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"查询A行余额？|query_balance", "帮我分析一下A行本月账单|bill_analysis"})
  void ordinaryReadRequestsKeepTheirExistingCapabilities(String text, String kind) {
    assertThat(new RuleBasedIntent().recognize(text, "read", NOW).getIntent()).isEqualTo(kind);
  }

  @ParameterizedTest
  @ValueSource(strings = {"关于冻结A行账户的操作说明", "我想了解冻结A行账户的影响", "冻结A行账户会怎么样"})
  void AWriteMentionWithoutAGoverningCommandIsNotPermissionToAct(String text) {
    assertThat(new RuleBasedIntent().recognize(text, "mention", NOW).getIntent()).isNotIn(WRITES);
  }

  @Test
  void aRecipientNumeralWithoutMoneySyntaxIsNotAnotherAmount() {
    assertThat(new RuleBasedIntent().recognize("给一万转100元，用A行", "name", NOW).getSlots())
        .containsEntry("amount", "100.00")
        .containsEntry("to_name", "一万");
  }
}
