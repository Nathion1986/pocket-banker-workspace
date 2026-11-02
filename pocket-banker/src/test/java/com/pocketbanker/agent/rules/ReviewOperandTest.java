package com.pocketbanker.agent.rules;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ReviewOperandTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"负100元", "负一百元", "负 100元", "负两千元", "一万二元", "两千五元", "三百五元", "九万八元", "负一百", "两千五"})
  void wholeUnsafeOperandsHaveNoPositiveSuffix(String operand) {
    var result = MoneyTokens.extract("改成" + operand);
    assertThat(result.amount()).as(operand).isNull();
    assertThat(result.error()).isNotNull();
    assertThat(result.tokens()).hasSize(1);
    assertThat(result.tokens().get(0).raw()).isEqualTo(operand);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "一万零二元|10002.00",
        "两千零五元|2005.00",
        "一万两千元|12000.00",
        "一百零五元|105.00",
        "12,000.50元|12000.50",
        "1.5万元|15000.00",
        "二亿元|200000000.00",
        "五百|500.00"
      })
  void explicitNumbersRetainTheirExactValues(String operand, String amount) {
    var result = MoneyTokens.extract("改成" + operand);
    assertThat(result.error()).isNull();
    assertThat(result.amount()).isEqualByComparingTo(amount);
  }
}
