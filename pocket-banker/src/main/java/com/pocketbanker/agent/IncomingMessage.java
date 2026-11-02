package com.pocketbanker.agent;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 统一消息契约（channels → agent 的唯一接口，§4.6）。 */
@Data
@Builder
public class IncomingMessage {
  private String userId;
  private String channel; // "web" | "telegram"
  private String text;
  private String msgId; // 幂等去重
  private Map<String, Object> meta;
}
