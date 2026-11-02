package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.VaultCodec;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable transport work, independent of task execution. No raw incoming text is retained. */
@Service
@RequiredArgsConstructor
public class ChannelDeliveryTransactions {
  private final BackendStore db;
  private final VaultCodec vault;

  public record Claim(
      String id, String user, String chat, long generation, Map<String, Object> body) {}

  @Transactional
  public String enqueue(
      String user,
      String chat,
      String key,
      String source,
      String sourceId,
      Map<String, Object> body) {
    db.lockUser(user);
    var prior =
        db.one(
            "SELECT id FROM backend_channel_deliveries WHERE user_id=? AND channel='telegram' AND delivery_key=?",
            user,
            key);
    if (prior.isPresent()) return text(prior.get(), "id");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_channel_deliveries(id,user_id,channel,external_id,delivery_key,source_type,source_id,body_cipher,next_attempt_at,created_at,updated_at) VALUES(?,?,'telegram',?,?,?,?,?,?,?,?)",
        id,
        user,
        chat,
        key,
        source,
        sourceId,
        vault.seal(user, body),
        db.now(),
        db.now(),
        db.now());
    return id;
  }

  public List<Map<String, Object>> due() {
    return db.rows(
        "SELECT id FROM backend_channel_deliveries WHERE source_type='reply' AND state='pending' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at LIMIT 100",
        db.now(),
        db.now());
  }

  @Transactional
  public Claim claim(String id) {
    var initial = db.one("SELECT user_id FROM backend_channel_deliveries WHERE id=?", id);
    if (initial.isEmpty()) return null;
    String user = text(initial.get(), "user_id");
    db.lockUser(user);
    var row =
        db.one("SELECT * FROM backend_channel_deliveries WHERE id=? FOR UPDATE", id).orElseThrow();
    if (!"pending".equals(text(row, "state"))
        || time(row, "next_attempt_at").isAfter(db.now())
        || time(row, "lease_until") != null && time(row, "lease_until").isAfter(db.now()))
      return null;
    var body = vault.map(user, text(row, "body_cipher"));
    if (!sourceCurrent(row, body)) {
      cancelNotification(user, text(row, "source_id"));
      return null;
    }
    if (db.one(
            "SELECT user_id FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND external_id=? AND state='active'",
            user,
            text(row, "external_id"))
        .isEmpty()) {
      db.update(
          "UPDATE backend_channel_deliveries SET state='cancelled',generation=generation+1,lease_until=NULL,error_code='channel_unbound',updated_at=? WHERE id=?",
          db.now(),
          id);
      return null;
    }
    long generation = number(row, "generation") + 1;
    db.update(
        "UPDATE backend_channel_deliveries SET generation=?,lease_until=?,updated_at=? WHERE id=?",
        generation,
        db.now().plusSeconds(30),
        db.now(),
        id);
    return new Claim(id, user, text(row, "external_id"), generation, body);
  }

  @Transactional
  public boolean current(Claim claim) {
    db.lockUser(claim.user());
    return db.one(
            "SELECT d.* FROM backend_channel_deliveries d WHERE d.id=? AND d.user_id=? AND d.state='pending' AND d.generation=? AND d.lease_until>? AND EXISTS (SELECT 1 FROM backend_channel_bindings b WHERE b.user_id=d.user_id AND b.external_id=d.external_id AND b.channel=d.channel AND b.state='active')",
            claim.id(),
            claim.user(),
            claim.generation(),
            db.now())
        .filter(row -> sourceCurrent(row, claim.body()))
        .isPresent();
  }

  private boolean sourceCurrent(Map<String, Object> row, Map<String, Object> body) {
    if (!"notification".equals(text(row, "source_type"))) return true;
    if (!(body.get("notificationGeneration") instanceof Number generation)
        || !(body.get("notificationVersion") instanceof Number version)) return false;
    return db.one(
            "SELECT id FROM backend_notifications WHERE id=? AND user_id=? AND state='pending' AND delivery_generation=? AND content_version=? AND delivery_lease_until>?",
            text(row, "source_id"),
            text(row, "user_id"),
            generation.longValue(),
            version.longValue(),
            db.now())
        .isPresent();
  }

  @Transactional
  public void cancelNotification(String user, String id) {
    db.lockUser(user);
    db.update(
        "UPDATE backend_channel_deliveries SET state='cancelled',generation=generation+1,lease_until=NULL,error_code='notification_changed',updated_at=? WHERE user_id=? AND source_type='notification' AND source_id=? AND state='pending'",
        db.now(),
        user,
        id);
  }

  @Transactional
  public void finish(
      Claim claim, boolean success, String messageId, String error, long retryAfter) {
    if (!current(claim)) return;
    var row =
        db.one("SELECT attempts,source_type FROM backend_channel_deliveries WHERE id=?", claim.id())
            .orElseThrow();
    int attempts = (int) number(row, "attempts") + 1;
    db.update(
        "UPDATE backend_channel_deliveries SET state=?,attempts=?,lease_until=NULL,next_attempt_at=?,external_message_id=?,error_code=?,updated_at=? WHERE id=? AND generation=?",
        success
            ? "delivered"
            : attempts >= 5 || "notification".equals(text(row, "source_type"))
                ? "failed"
                : "pending",
        attempts,
        db.now()
            .plusSeconds(
                Math.max(Math.min(86400, Math.max(0, retryAfter)), Math.min(300, 1L << attempts))),
        messageId,
        error,
        db.now(),
        claim.id(),
        claim.generation());
  }

  public List<Map<String, Object>> list(String user, String source, String id) {
    return db.rows(
        "SELECT id,channel,state,attempts,error_code,external_message_id,created_at,updated_at,next_attempt_at FROM backend_channel_deliveries WHERE user_id=? AND source_type=? AND source_id=? ORDER BY created_at,id",
        user,
        source,
        id);
  }

  public List<Map<String, Object>> replies(String user, int page) {
    return db.rows(
        "SELECT id,source_id,state,attempts,error_code,external_message_id,next_attempt_at,created_at,updated_at FROM backend_channel_deliveries WHERE user_id=? AND source_type='reply' ORDER BY created_at DESC,id LIMIT 50 OFFSET ?",
        user,
        page * 50);
  }
}
