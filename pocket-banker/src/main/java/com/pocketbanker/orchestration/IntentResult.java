package com.pocketbanker.orchestration;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 意图识别结果——编排引擎的典型输出。 */
@Data
@Builder
public class IntentResult {
  private String intent; // transfer | query_balance | chat ...
  private Map<String, Object> slots; // {"amount": 500, "to_name": "妈妈"}

  public record Slot(Object value, String type, String source, double confidence, String status) {}

  private Map<String, Slot> slotDetails;
  private RiskLevel riskLevel;
  private boolean consensus; // 多模型是否达成一致
  private String disagreementDetail;
}
