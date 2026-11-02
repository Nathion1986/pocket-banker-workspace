package com.pocketbanker.agent.rules;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlannerSlotsTest {
  @Test
  void chineseMoneyAndFourthBankAreRecognizedWithExactDecimalUnits() {
    var slots = new RuleBasedIntent().recognize("用F行给妈妈转账一万两千五百元").getSlots();
    assertThat(slots).containsEntry("bank", "F").containsEntry("amount", "12500.00");
    assertThat(new RuleBasedIntent().recognize("10月账单").getSlots()).doesNotContainKey("amount");
    assertThat(new RuleBasedIntent().recognize("转账100元和200元").getSlots())
        .doesNotContainKey("amount");
  }

  @Test
  void tuitionDeadlineIsNotSilentlyReinterpretedAsExecutionSchedule() {
    assertThat(new RuleBasedIntent().recognize("2026年10月15日前交2万学费").getSlots())
        .doesNotContainKeys("executionTimeMissing", "executionTimeInvalid");
  }

  @Test
  void collectionDeadlineHasSeparateRoleFromTaskExecutionTime() {
    var result = new RuleBasedIntent().recognize("AA收款100元，参与人妈妈、同事，明天上午10点截止");
    assertThat(result.getSlots())
        .containsKey("dueAt")
        .containsKey("participantNames")
        .doesNotContainKey("triggerType");
  }

  @Test
  void accountProtectionStatusQuestionIsReadOnly() {
    assertThat(new RuleBasedIntent().recognize("我的账户被冻结了吗").getIntent()).isEqualTo("query_balance");
    assertThat(new RuleBasedIntent().recognize("帮我冻结A行的卡").getIntent()).isEqualTo("freeze_card");
  }

  @Test
  void recipientNameAndMemoDoNotOverrideExplicitTransferVerb() {
    var nlu = new RuleBasedIntent();
    assertThat(nlu.recognize("用C行给C收款人转账20元").getIntent()).isEqualTo("transfer");
    assertThat(nlu.recognize("给妈妈转账20元，备注生日红包").getIntent()).isEqualTo("transfer");
  }
}
