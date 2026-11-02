package com.pocketbanker.agent;

import java.util.*;

/** A channel-neutral reply; task state and transport completion are separate facts. */
public record ReplyEvent(
    String type, String requestId, String taskId, String state, Map<String, Object> payload) {
  public static Map<String, Object> envelope(String request, Map<String, Object> result) {
    var response = new LinkedHashMap<>(result);
    response.put("replyEvent", from(request, result));
    return response;
  }

  public static ReplyEvent from(String request, Map<String, Object> result) {
    String state =
        Objects.toString(result.get("state"), Objects.toString(result.get("kind"), "completed"));
    String type =
        result.containsKey("error")
            ? "error"
            : "WAITING_VERIFICATION".equals(state)
                ? "verify_request"
                : "WAITING_CONFIRMATION".equals(state) ? "confirm_request" : "text_stream";
    String task = Objects.toString(result.getOrDefault("taskId", result.get("id")), null);
    return new ReplyEvent(
        type, request, task, state, Collections.unmodifiableMap(new LinkedHashMap<>(result)));
  }

  public String text() {
    String fallback =
        switch (state) {
          case "WAITING_CONFIRMATION" -> "请确认此任务及参数。";
          case "WAITING_VERIFICATION" -> "此操作需要两项强验证。";
          case "SUCCEEDED" -> "任务已完成，结果已核验。";
          case "CANCELLED" -> "任务已取消。";
          case "COMPENSATED" -> "操作未完成，补偿结果已核验。";
          case "MANUAL_REQUIRED", "FAILED" -> "任务未完成，请在 App 查看原因及处理选项。";
          case "RECONCILING" -> "正在核对银行处理结果，请等待确认。";
          default -> "任务已登记，请在 App 查看当前进度。";
        };
    String text =
        Objects.toString(
            payload.get("reply"), "error".equals(type) ? "请求未获通过，请在 App 核对任务状态。" : fallback);
    if (Set.of("confirm_request", "verify_request").contains(type)) {
      var preview = preview();
      text += preview.text();
      if (!preview.complete()) text += "\n方案参数未完整显示，请在 App 查看并确认。";
    }
    if ("verify_request".equals(type))
      text +=
          " 请在 App 完成两项验证；或发送 /verify " + taskId + " " + payload.get("version") + " <验证码> <第二因子>。";
    if (text.codePointCount(0, text.length()) > 4000)
      text = text.substring(0, text.offsetByCodePoints(0, 3900)) + "\n完整详情请在 App 查看。";
    return text;
  }

  public Map<String, Object> markup() {
    if (!"confirm_request".equals(type) || !preview().complete()) return null;
    String version = Objects.toString(payload.get("version"));
    return Map.of(
        "inline_keyboard",
        List.of(
            List.of(
                Map.of("text", "确认任务", "callback_data", "confirm:" + taskId + ":" + version),
                Map.of("text", "取消任务", "callback_data", "cancel:" + taskId + ":" + version))));
  }

  private record Preview(String text, boolean complete) {}

  private Preview preview() {
    if (!(payload.get("nodes") instanceof List<?> nodes)) return new Preview("", true);
    StringBuilder detail = new StringBuilder("\n任务：" + taskId + "，版本：" + payload.get("version"));
    var labels =
        Map.of(
            "transfer",
            "转账",
            "freeze",
            "冻结",
            "unfreeze",
            "解冻",
            "wealth_redeem",
            "赎回",
            "wealth_subscribe",
            "申购",
            "reserve",
            "预留资金");
    try {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      for (var value : nodes) {
        if (!(value instanceof Map<?, ?> node) || !(node.get("input") instanceof Map<?, ?> input))
          return new Preview(detail.toString(), false);
        String action = Objects.toString(node.get("action_id"), "操作");
        detail
            .append("\n")
            .append(labels.getOrDefault(action, action))
            .append("：")
            .append(json.writeValueAsString(input));
      }
      return new Preview(detail.toString(), detail.codePointCount(0, detail.length()) <= 3000);
    } catch (Exception invalid) {
      return new Preview(detail.toString(), false);
    }
  }
}
