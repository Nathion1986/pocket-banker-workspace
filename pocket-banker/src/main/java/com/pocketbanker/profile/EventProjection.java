package com.pocketbanker.profile;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.*;
import com.pocketbanker.notifications.NotificationService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventProjection {
  private final BackendStore db;
  private final ProfileService profile;
  private final ObjectMapper mapper;
  private final NotificationService notifications;
  private final com.pocketbanker.contracts.ConfirmedEventTrigger taskTrigger;

  public void requireCurrent(TimelineEventEntity event) {
    if (event.getSupersededBy() != null)
      throw new com.pocketbanker.contracts.BackendException(409, "event_version_stale");
    if (event.getPayloadJson() == null) return;
    Map<String, Object> payload;
    try {
      payload = mapper.readValue(event.getPayloadJson(), new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("invalid_event_payload", e);
    }
    if (!payload.containsKey("externalHash")) return;
    var alias =
        db.one(
            "SELECT revision,event_id FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            event.getUserId(),
            payload.get("sourceId"),
            payload.get("externalHash"));
    if (alias.isPresent()
        && (!Objects.equals(text(alias.get(), "event_id"), event.getId())
            || number(alias.get(), "revision") != ((Number) payload.get("revision")).longValue()))
      throw new com.pocketbanker.contracts.BackendException(409, "event_version_stale");
  }

  public void confirmed(TimelineEventEntity event) {
    requireCurrent(event);
    if (event.getPayloadJson() != null) {
      Map<String, Object> payload;
      try {
        payload = mapper.readValue(event.getPayloadJson(), new TypeReference<>() {});
      } catch (Exception e) {
        throw new IllegalStateException();
      }
      if (Set.of("monthly_income", "monthly_expense").contains(event.getKind())) {
        String prefix =
            event.getKind().equals("monthly_income") ? "income_schedule" : "expense_schedule";
        profile.fact(
            event.getUserId(),
            new ProfileService.FactInput(
                null,
                prefix + ":" + payload.get("patternHash").toString().substring(0, 16),
                payload,
                "pattern",
                List.of(event.getId()),
                null));
      }
      if (event.getKind().equals("home_city_change"))
        profile.fact(
            event.getUserId(),
            new ProfileService.FactInput(
                null, "home_city", payload.get("city"), "derived", List.of(event.getId()), null));
      if (Set.of("calendar_trip", "flight_trip").contains(event.getKind()))
        profile.fact(
            event.getUserId(),
            new ProfileService.FactInput(
                event.getSubjectId(),
                "travel_window:" + event.getId(),
                payload,
                "static",
                List.of(event.getId()),
                null));
      if (event.getKind().equals("calendar_birthday"))
        profile.fact(
            event.getUserId(),
            new ProfileService.FactInput(
                event.getSubjectId(),
                "birthday",
                payload.get("startDate"),
                "static",
                List.of(event.getId()),
                null));
      if (payload.containsKey("externalHash")) {
        for (var old :
            db.rows(
                "SELECT id,payload_json FROM events WHERE user_id=? AND id<>? AND status='confirmed' AND kind=?",
                event.getUserId(),
                event.getId(),
                event.getKind())) {
          try {
            Map<String, Object> other =
                mapper.readValue(text(old, "payload_json"), new TypeReference<>() {});
            if (Objects.equals(other.get("sourceId"), payload.get("sourceId"))
                && Objects.equals(other.get("externalHash"), payload.get("externalHash"))) {
              db.update(
                  "UPDATE events SET superseded_by=? WHERE id=?", event.getId(), text(old, "id"));
              profile.cleanupSuperseded(event.getUserId(), text(old, "id"));
            }
          } catch (Exception ignored) {
          }
        }
      }
    }
    taskTrigger.confirmedEvent(event.getUserId(), event.getId(), event.getKind());
    notifications.publish(
        event.getUserId(),
        "event-confirmed-" + event.getId(),
        "fact_confirmed",
        Map.of("eventId", event.getId(), "kind", event.getKind()),
        db.now());
  }

  public void stopReminders(String user, String id) {
    notifications.cancelSubject(user, id);
  }

  public void forget(String user, String id) {
    stopReminders(user, id);
    profile.cleanupEvidence(user, id);
  }
}
