package com.pocketbanker.agent.rules;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ReviewSpeechTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "冻结A行账户前必须征得我同意",
        "冻结A行账户要等我确认",
        "冻结A行账户是不允许的",
        "冻结A行账户是禁止的",
        "给妈妈转账100元，备注生活费，等我确认后再执行",
        "给妈妈转账100元，备注生活费，收款人改为爸爸",
        "挂失A行之前先得到我的许可",
        "用A行转100元，备注生活费；确认之后才执行",
        "给妈妈转100元，备注生活费等我确认后再执行"
      })
  void limitsRemainVisibleToRealIntentDTO(String text) {
    var speech = OperationSpeech.inspect(text);
    assertThat(speech.blockedReason()).as(text).isNotNull();
    var dto = new RuleBasedIntent().recognize(text);
    assertThat(dto.getSlots()).containsKey("operationIntent");
    assertThat(dto.getIntent()).isIn("chat", "clarification");
  }

  @Test
  void memoEndsAtItsOwnFieldAndDoesNotSupplyAnotherAmount() {
    var dto = new RuleBasedIntent().recognize("给妈妈转100元,备注生活费,用A行");
    assertThat(dto.getIntent()).isEqualTo("transfer");
    assertThat(dto.getSlots())
        .containsEntry("amount", "100.00")
        .containsEntry("bank", "A")
        .containsEntry("memo", "生活费");
  }
}
