package com.pocketbanker.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramClient {
  private final String token, base;
  private final ObjectMapper mapper;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public TelegramClient(
      ObjectMapper mapper,
      @Value("${backend.telegram-token:}") String token,
      @Value("${backend.telegram-api-url:https://api.telegram.org}") String base) {
    this.mapper = mapper;
    this.token = token;
    this.base = base;
  }

  public boolean configured() {
    return !token.isBlank();
  }

  public boolean send(String chat, String text, Map<String, Object> markup) {
    return sendReceipt(chat, text, markup).accepted();
  }

  public record Receipt(boolean accepted, String messageId, String error, long retryAfter) {}

  public Receipt sendReceipt(String chat, String text, Map<String, Object> markup) {
    if (token.isBlank()) return new Receipt(false, null, "telegram_unconfigured", 60);
    if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 4096)
      return new Receipt(false, null, "telegram_text_invalid", 0);
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("chat_id", chat);
      body.put("text", text);
      if (markup != null) body.put("reply_markup", markup);
      var request =
          HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/sendMessage"))
              .timeout(Duration.ofSeconds(3))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
              .build();
      var result = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (result.body().length > 131072)
        return new Receipt(false, null, "telegram_reply_invalid", 0);
      var response = mapper.readTree(result.body());
      if (result.statusCode() == 200
          && response.path("ok").asBoolean(false)
          && response.path("result").path("message_id").canConvertToLong())
        return new Receipt(true, response.path("result").path("message_id").asText(), null, 0);
      return new Receipt(
          false,
          null,
          result.statusCode() == 429 ? "telegram_rate_limited" : "telegram_send_rejected",
          Math.max(0, Math.min(86400, response.path("parameters").path("retry_after").asLong(0))));
    } catch (Exception e) {
      return new Receipt(false, null, "telegram_result_unknown", 0);
    }
  }

  public void answerCallback(String id) {
    if (token.isBlank()) return;
    try {
      http.send(
          HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/answerCallbackQuery"))
              .timeout(Duration.ofSeconds(3))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofByteArray(
                      mapper.writeValueAsBytes(Map.of("callback_query_id", id))))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (Exception ignored) {
      /* Message delivery still has a separate durable receipt. */
    }
  }

  public String health() {
    if (!configured()) return "disabled";
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/getMe"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      return response.statusCode() == 200
              && response.body().length <= 131072
              && mapper.readTree(response.body()).path("ok").asBoolean(false)
          ? "up"
          : "unreachable";
    } catch (Exception e) {
      return "unreachable";
    }
  }
}
