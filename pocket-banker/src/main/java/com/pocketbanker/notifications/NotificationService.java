package com.pocketbanker.notifications;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.VaultCodec;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {
  private final BackendStore db;
  private final VaultCodec vault;
  private final com.pocketbanker.agent.ChannelDeliveryTransactions deliveries;

  @Transactional
  public String publish(
      String user, String key, String kind, Map<String, Object> content, Instant due) {
    db.lockUser(user);
    if (Set.of("morning", "candidate_summary", "liquidity_gap", "birthday", "subscription_renewal")
        .contains(kind)) return advice(user, key, kind, content, due);
    var prior =
        db.one(
            "SELECT id FROM backend_notifications WHERE user_id=? AND delivery_key=?", user, key);
    if (prior.isPresent()) return text(prior.get(), "id");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_notifications(id,user_id,delivery_key,kind,content_cipher,state,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        key,
        kind,
        vault.seal(user, content),
        "pending",
        due,
        db.now());
    return id;
  }

  private String advice(
      String user, String key, String kind, Map<String, Object> content, Instant due) {
    var seen =
        db.one(
            "SELECT notification_id FROM backend_advice_items WHERE user_id=? AND delivery_key=?",
            user,
            key);
    if (seen.isPresent()) return text(seen.get(), "notification_id");
    LocalDate day = due.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    String daily = null;
    Optional<Map<String, Object>> current = Optional.empty();
    for (int offset = 0; offset <= 366; offset++) {
      daily = "advice:" + com.pocketbanker.security.PerceptionAccess.hash(user) + ":" + day;
      current =
          db.one(
              "SELECT * FROM backend_notifications WHERE user_id=? AND delivery_key=?",
              user,
              daily);
      boolean leased =
          current.isPresent()
              && time(current.get(), "delivery_lease_until") != null
              && time(current.get(), "delivery_lease_until").isAfter(db.now());
      if (current.isEmpty()
          || "pending".equals(text(current.get(), "state"))
              && !leased
              && content(current.get()).size() < 100) break;
      if (offset == 366) throw new BackendException(503, "advice_queue_full");
      day = day.plusDays(1);
      due = day.atTime(8, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
    String id = current.map(r -> text(r, "id")).orElseGet(() -> UUID.randomUUID().toString());
    Map<String, Object> sections =
        current.map(this::content).map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
    // One current contribution per business key; bound the digest independently of source volume.
    if (sections.size() < 100) sections.put(key, Map.of("kind", kind, "content", content));
    if (current.isPresent())
      db.update(
          "UPDATE backend_notifications SET content_cipher=?,content_version=content_version+1,delivery_generation=delivery_generation+1,delivery_lease_until=NULL WHERE id=?",
          vault.seal(user, sections),
          id);
    else
      db.update(
          "INSERT INTO backend_notifications(id,user_id,delivery_key,kind,content_cipher,state,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?)",
          id,
          user,
          daily,
          "daily_summary",
          vault.seal(user, sections),
          "pending",
          due,
          db.now());
    db.update("INSERT INTO backend_advice_items VALUES(?,?,?)", user, key, id);
    return id;
  }

  public List<Map<String, Object>> list(String user, int page) {
    return db
        .rows(
            "SELECT * FROM backend_notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 50 OFFSET ?",
            user,
            page * 50)
        .stream()
        .map(
            n -> {
              Map<String, Object> result = new LinkedHashMap<>();
              for (String key :
                  List.of("id", "kind", "state", "attempts", "created_at", "acknowledged_at"))
                result.put(key, n.get(key));
              result.put("content", vault.map(user, text(n, "content_cipher")));
              result.put("webInboxState", "available");
              result.put("channelDeliveries", deliveries.list(user, "notification", text(n, "id")));
              return result;
            })
        .toList();
  }

  @Transactional
  public void acknowledge(String user, String id) {
    db.lockUser(user);
    if (db.update(
            "UPDATE backend_notifications SET state='acknowledged',delivery_generation=delivery_generation+1,delivery_lease_until=NULL,acknowledged_at=? WHERE user_id=? AND id=?",
            db.now(),
            user,
            id)
        == 0) throw new BackendException(404, "notification_not_found");
    deliveries.cancelNotification(user, id);
  }

  @Transactional
  public void cancel(String user, String key) {
    db.lockUser(user);
    for (var row :
        db.rows(
            "SELECT id FROM backend_notifications WHERE user_id=? AND delivery_key=?", user, key))
      deliveries.cancelNotification(user, text(row, "id"));
    db.update(
        "UPDATE backend_notifications SET state='cancelled',delivery_generation=delivery_generation+1,delivery_lease_until=NULL WHERE user_id=? AND delivery_key=? AND state IN ('pending','delivered')",
        user,
        key);
  }

  @Transactional
  public void cancelSubject(String user, String event) {
    db.lockUser(user);
    for (var row : db.rows("SELECT * FROM backend_notifications WHERE user_id=?", user)) {
      Map<String, Object> body = new LinkedHashMap<>(content(row));
      if ("daily_summary".equals(text(row, "kind"))) {
        int previous = body.size();
        body.entrySet().removeIf(e -> references(e.getValue(), event));
        if (previous == body.size()) continue;
        db.update(
            "UPDATE backend_notifications SET content_cipher=?,state=?,content_version=content_version+1,delivery_generation=delivery_generation+1,delivery_lease_until=NULL WHERE id=?",
            vault.seal(user, body),
            body.isEmpty() ? "cancelled" : text(row, "state"),
            text(row, "id"));
        deliveries.cancelNotification(user, text(row, "id"));
      } else if (references(body, event)) {
        db.update(
            "UPDATE backend_notifications SET state='cancelled',delivery_generation=delivery_generation+1,delivery_lease_until=NULL WHERE id=?",
            text(row, "id"));
        deliveries.cancelNotification(user, text(row, "id"));
      }
    }
  }

  private boolean references(Object value, String event) {
    if (value instanceof Map<?, ?> map) {
      if (event.equals(map.get("eventId")) || event.equals(map.get("id"))) return true;
      return map.values().stream().anyMatch(v -> references(v, event));
    }
    if (value instanceof String text) return event.equals(text);
    return value instanceof List<?> list && list.stream().anyMatch(v -> references(v, event));
  }

  public List<Map<String, Object>> due() {
    return db.rows(
        "SELECT * FROM backend_notifications WHERE state='pending' AND next_attempt_at<=? AND (delivery_lease_until IS NULL OR delivery_lease_until<=?) ORDER BY next_attempt_at LIMIT 100",
        db.now(),
        db.now());
  }

  public Map<String, Object> content(Map<String, Object> row) {
    return vault.map(text(row, "user_id"), text(row, "content_cipher"));
  }

  public record DeliveryClaim(
      String id,
      String user,
      long generation,
      long version,
      String kind,
      Map<String, Object> body) {}

  @Transactional
  public DeliveryClaim claimDelivery(String id) {
    var initial = db.one("SELECT user_id FROM backend_notifications WHERE id=?", id);
    if (initial.isEmpty()) return null;
    String user = text(initial.get(), "user_id");
    db.lockUser(user);
    var row = db.one("SELECT * FROM backend_notifications WHERE id=? FOR UPDATE", id).orElseThrow();
    if (!"pending".equals(text(row, "state"))
        || time(row, "next_attempt_at").isAfter(db.now())
        || time(row, "delivery_lease_until") != null
            && time(row, "delivery_lease_until").isAfter(db.now())) return null;
    long generation = number(row, "delivery_generation") + 1;
    db.update(
        "UPDATE backend_notifications SET delivery_generation=?,delivery_lease_until=? WHERE id=?",
        generation,
        db.now().plusSeconds(30),
        id);
    return new DeliveryClaim(
        id, user, generation, number(row, "content_version"), text(row, "kind"), content(row));
  }

  @Transactional
  public boolean allowDelivery(DeliveryClaim claim) {
    db.lockUser(claim.user());
    var row =
        db.one(
            "SELECT * FROM backend_notifications WHERE id=? AND user_id=? FOR UPDATE",
            claim.id(),
            claim.user());
    return row.isPresent()
        && "pending".equals(text(row.get(), "state"))
        && number(row.get(), "delivery_generation") == claim.generation()
        && number(row.get(), "content_version") == claim.version()
        && time(row.get(), "delivery_lease_until") != null
        && time(row.get(), "delivery_lease_until").isAfter(db.now());
  }

  @Transactional
  public void delivered(DeliveryClaim claim, boolean success) {
    if (!allowDelivery(claim)) return;
    finishDelivery(
        claim.id(), success, Set.of("urgent_risk", "candidate_urgent").contains(claim.kind()));
  }

  @Transactional
  public void delivered(String id, boolean success, boolean urgent) {
    var initial =
        db.one("SELECT user_id,delivery_lease_until FROM backend_notifications WHERE id=?", id)
            .orElseThrow();
    db.lockUser(text(initial, "user_id"));
    if (time(initial, "delivery_lease_until") != null
        && time(initial, "delivery_lease_until").isAfter(db.now())) return;
    finishDelivery(id, success, urgent);
  }

  private void finishDelivery(String id, boolean success, boolean urgent) {
    var row = db.one("SELECT * FROM backend_notifications WHERE id=? FOR UPDATE", id).orElseThrow();
    if (!"pending".equals(text(row, "state"))) return;
    int attempts = (int) number(row, "attempts") + 1;
    String state =
        success
            ? (urgent && attempts < 3 ? "pending" : "delivered")
            : attempts >= 5 ? "failed" : "pending";
    db.update(
        "UPDATE backend_notifications SET state=?,attempts=?,next_attempt_at=?,delivery_lease_until=NULL WHERE id=?",
        state,
        attempts,
        db.now().plusSeconds(urgent ? 60 : Math.min(300, 1L << attempts)),
        id);
  }
}
