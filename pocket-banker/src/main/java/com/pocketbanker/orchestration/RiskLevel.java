package com.pocketbanker.orchestration;

/** 风险级别——权限引擎和编排引擎共用。 */
public enum RiskLevel {
  GREEN("绿色", "自动执行"),
  YELLOW("黄色", "需确认卡"),
  RED("红色", "需强验证");

  private final String displayName;
  private final String policy;

  RiskLevel(String displayName, String policy) {
    this.displayName = displayName;
    this.policy = policy;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getPolicy() {
    return policy;
  }
}
