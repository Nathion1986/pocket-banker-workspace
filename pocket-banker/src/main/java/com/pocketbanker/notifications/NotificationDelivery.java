package com.pocketbanker.notifications;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.channels.telegram.TelegramClient;
import com.pocketbanker.data.BackendStore;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDelivery {
  private final NotificationService notifications;
  private final BackendStore db;
  private final TelegramClient telegram;
  private final ObjectMapper mapper;
  private final com.pocketbanker.agent.ChannelDeliveryTransactions deliveries;
  private final com.pocketbanker.agent.TelegramDelivery channelDelivery;

  @Scheduled(
      fixedDelayString = "${backend.notification-poll-ms:1000}",
      initialDelayString = "${backend.notification-initial-ms:1000}")
  public void poll() {
    for (var row : notifications.due()) {
      var claim = notifications.claimDelivery(text(row, "id"));
      if (claim == null) continue;
      String user = claim.user();
      var binding =
          db.one(
              "SELECT external_id FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND state='active'",
              user);
      if (!notifications.allowDelivery(claim)) continue;
      boolean delivered =
          true; // Web inbox is durable and available immediately; acknowledged is a separate state.
      if (binding.isPresent())
        try {
          String body = mapper.writeValueAsString(claim.body());
          if (body.length() > 4000) body = body.substring(0, 3900) + "\n完整详情请在 App 查看。";
          String id =
              deliveries.enqueue(
                  user,
                  text(binding.get(), "external_id"),
                  "notification:" + claim.id() + ":" + claim.generation(),
                  "notification",
                  claim.id(),
                  Map.of(
                      "text",
                      body,
                      "notificationGeneration",
                      claim.generation(),
                      "notificationVersion",
                      claim.version()));
          delivered = channelDelivery.deliver(id);
        } catch (Exception e) {
          delivered = false;
        }
      notifications.delivered(claim, delivered);
    }
  }
}
