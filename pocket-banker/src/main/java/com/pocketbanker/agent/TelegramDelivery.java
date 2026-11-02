package com.pocketbanker.agent;

import com.pocketbanker.channels.telegram.TelegramClient;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TelegramDelivery {
  private final ChannelDeliveryTransactions deliveries;
  private final TelegramClient telegram;

  @Scheduled(
      fixedDelayString = "${backend.channel-poll-ms:1000}",
      initialDelayString = "${backend.notification-initial-ms:1000}")
  public void poll() {
    for (var row : deliveries.due()) deliver(row.get("id").toString());
  }

  @SuppressWarnings("unchecked")
  public boolean deliver(String id) {
    var claim = deliveries.claim(id);
    if (claim == null || !deliveries.current(claim)) return false;
    if (claim.body().get("callbackQueryId") instanceof String callback)
      telegram.answerCallback(callback);
    var result =
        telegram.sendReceipt(
            claim.chat(),
            Objects.toString(claim.body().get("text")),
            (Map<String, Object>) claim.body().get("markup"));
    deliveries.finish(
        claim, result.accepted(), result.messageId(), result.error(), result.retryAfter());
    return result.accepted();
  }
}
