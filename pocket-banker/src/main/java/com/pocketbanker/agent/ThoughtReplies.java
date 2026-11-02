package com.pocketbanker.agent;

/** Trusted local explanations contain no inferred personal balances or account state. */
public final class ThoughtReplies {
  private ThoughtReplies() {}

  public static String explain(String text) {
    if (text.contains("冻结") || text.contains("解冻"))
      return "冻结会限制账户的后续资金操作。解除冻结需要对应授权，实际是否完成须查询银行状态，解释不会操作账户。";
    if (text.contains("幂等")) return "幂等是同一个业务请求重复提交时仍得到同一个结果。后端保存原业务键，银行也要识别这个键，才能避免重试时重复扣款。";
    if (text.contains("权限")) return "权限控制决定谁可以对哪个账户执行哪些行动。后端会检查身份、绑定授权、风险和确认版本，并在执行前重新核对。";
    if (text.contains("影子")) return "影子数据用于在模型边界前隐藏真实身份和资金信息。真实值由本地保存和核验，模型输出不能自行变成银行操作。";
    if (text.contains("T+1") || text.contains("清算"))
      return "银行受理与最终到账是不同状态。后端保留待清算记录并继续核对，实际到账情况以银行返回为准。";
    if (text.contains("理财")) return "理财查询会读取银行提供的产品、风险等级和赎回规则；申购和赎回属于实际资金行动，需要对应授权。";
    return "我可以查询账户、分析账单、规划付款或创建任务。请说明要处理的事项；执行前会展示必要的确认。";
  }
}
