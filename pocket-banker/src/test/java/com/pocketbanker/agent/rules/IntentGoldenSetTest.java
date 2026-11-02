package com.pocketbanker.agent.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketbanker.orchestration.IntentResult;
import com.pocketbanker.orchestration.RiskLevel;
import org.junit.jupiter.api.Test;

/** 意图识别 Golden Set——30+ 条典型话术 → 期望意图/槽位断言。 改 prompt/换模型后必须全绿（纪律：prompt 的 bug 同样必须转用例）。 */
class IntentGoldenSetTest {

  private final RuleBasedIntent intent = new RuleBasedIntent();

  // ------------------------------------------------------------------
  // 查余额（多种表达）
  // ------------------------------------------------------------------

  @Test
  void testBalanceQueries() {
    String[] queries = {"查余额", "我还有多少钱", "账户余额", "剩多少钱", "看看账户"};
    for (String q : queries) {
      IntentResult r = intent.recognize(q);
      assertEquals("query_balance", r.getIntent(), "Failed for: " + q);
      assertEquals(RiskLevel.GREEN, r.getRiskLevel());
    }
  }

  // ------------------------------------------------------------------
  // 查流水
  // ------------------------------------------------------------------

  @Test
  void testTransactionQueries() {
    String[] queries = {"查流水", "最近流水", "账单", "这个月花了什么钱", "消费记录", "最近交易"};
    for (String q : queries) {
      assertEquals("query_transactions", intent.recognize(q).getIntent(), "Failed for: " + q);
    }
  }

  // ------------------------------------------------------------------
  // 转账（金额+收款人+备注提取）
  // ------------------------------------------------------------------

  @Test
  void testTransfer500() {
    IntentResult r = intent.recognize("给妈妈转500元");
    assertEquals("transfer", r.getIntent());
    assertEquals(RiskLevel.YELLOW, r.getRiskLevel());
    assertEquals(
        500.0, new java.math.BigDecimal(r.getSlots().get("amount").toString()).doubleValue());
    assertEquals("妈妈", r.getSlots().get("to_name"));
  }

  @Test
  void testTransfer5000() {
    IntentResult r = intent.recognize("转5000元");
    assertEquals("transfer", r.getIntent());
    assertEquals(RiskLevel.RED, r.getRiskLevel());
    assertEquals(
        5000.0, new java.math.BigDecimal(r.getSlots().get("amount").toString()).doubleValue());
  }

  @Test
  void testTransferWithMemo() {
    IntentResult r = intent.recognize("给妈妈转300元，备注买菜钱");
    assertEquals("transfer", r.getIntent());
    assertEquals(
        300.0, new java.math.BigDecimal(r.getSlots().get("amount").toString()).doubleValue());
    assertEquals("妈妈", r.getSlots().get("to_name"));
    assertEquals("买菜钱", r.getSlots().get("memo"));
  }

  @Test
  void testTransferBoundary999() {
    IntentResult r = intent.recognize("转999元");
    assertEquals(RiskLevel.YELLOW, r.getRiskLevel());
  }

  @Test
  void testTransferBoundary1000() {
    // 1000 恰好在边界上：规则是 > 1000 才红色，1000 本身是黄色
    IntentResult r = intent.recognize("转1000元");
    assertEquals(RiskLevel.YELLOW, r.getRiskLevel());
  }

  @Test
  void testTransferBoundary1001() {
    IntentResult r = intent.recognize("转1001元");
    assertEquals(RiskLevel.RED, r.getRiskLevel());
  }

  @Test
  void testTransferToLandlord() {
    IntentResult r = intent.recognize("给房东转4500元");
    assertEquals("transfer", r.getIntent());
    assertEquals("房东", r.getSlots().get("to_name"));
    assertEquals(
        4500.0, new java.math.BigDecimal(r.getSlots().get("amount").toString()).doubleValue());
  }

  // ------------------------------------------------------------------
  // 冻结（保护性动作 → 绿色）
  // ------------------------------------------------------------------

  @Test
  void testFreeze() {
    IntentResult r = intent.recognize("冻结我的卡");
    assertEquals("freeze_card", r.getIntent());
    assertEquals(RiskLevel.GREEN, r.getRiskLevel());
  }

  @Test
  void testReportLoss() {
    assertEquals("freeze_card", intent.recognize("挂失").getIntent());
  }

  // ------------------------------------------------------------------
  // 学费规划（场景 F）
  // ------------------------------------------------------------------

  @Test
  void testTuition() {
    IntentResult r = intent.recognize("孩子9月要交2万学费，钱够吗？");
    // 规则版可能不识别学费（这需要 LLM），但不应误判为转账
    assertNotEquals("transfer", r.getIntent(), "学费不应误判为转账");
  }

  // ------------------------------------------------------------------
  // 聊天兜底
  // ------------------------------------------------------------------

  @Test
  void testChat() {
    String[] queries = {"你好", "今天天气怎么样", "你是谁", "谢谢"};
    for (String q : queries) {
      assertEquals("chat", intent.recognize(q).getIntent(), "Failed for: " + q);
    }
  }

  // ------------------------------------------------------------------
  // 金额提取边界
  // ------------------------------------------------------------------

  @Test
  void testDecimalAmount() {
    IntentResult r = intent.recognize("转99.99元");
    assertEquals(
        99.99, new java.math.BigDecimal(r.getSlots().get("amount").toString()).doubleValue(), 0.01);
  }

  @Test
  void testNoAmount() {
    IntentResult r = intent.recognize("转账给妈妈");
    assertEquals("transfer", r.getIntent());
    assertNull(r.getSlots().get("amount"));
  }

  @Test
  void tuitionMonthDoesNotBecomeAmountAndFullDateIsPreserved() {
    var result = intent.recognize("孩子9月交2万学费，2026年10月15日前缴纳");
    assertEquals("tuition_planning", result.getIntent());
    assertEquals("20000.00", result.getSlots().get("amount"));
    assertEquals("2026-10-15", result.getSlots().get("due_date"));
  }

  @Test
  void negativeAndMalformedAmountsAreNotPartiallyParsed() {
    for (String text : new String[] {"转-35元", "转35.201元", "转1,23元"})
      assertNull(intent.recognize(text).getSlots().get("amount"), text);
  }

  @Test
  void restoreAndWealthRedemptionAreNotMisroutedAsProtectiveOrQuery() {
    assertEquals("unfreeze_card", intent.recognize("解除冻结").getIntent());
    assertEquals("wealth_redeem", intent.recognize("赎回理财1000元").getIntent());
  }
}
