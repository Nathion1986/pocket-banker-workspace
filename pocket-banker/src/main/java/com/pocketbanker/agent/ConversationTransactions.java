package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationTransactions {
  private final BackendStore db;
  private final VaultCodec vault;
  private final BackendAudit audit;

  public record Started(
      Map<String, Object> replay, String recoveredTask, Map<String, Object> context) {}

  public Map<String, Object> cached(String user, String session, String message, String text) {
    var row =
        db.one(
                "SELECT input_hash,reply_json FROM backend_messages WHERE user_id=? AND id=?",
                user,
                message)
            .orElseThrow(() -> new BackendException(409, "stream_cursor_without_reply"));
    if (!PerceptionAccess.hash(session + "\n" + text).equals(BackendStore.text(row, "input_hash")))
      throw new BackendException(409, "message_id_content_conflict");
    if (BackendStore.text(row, "reply_json") == null)
      throw new BackendException(409, "stream_reply_not_ready");
    return vault.map(user, BackendStore.text(row, "reply_json"));
  }

  @Transactional
  public Started begin(String user, String session, String message, String text) {
    if (session == null
        || !session.matches("[A-Za-z0-9_-]{1,100}")
        || message == null
        || !message.matches("[A-Za-z0-9_.:-]{1,160}")
        || text == null
        || text.isBlank()
        || text.length() > 8192) throw new BackendException(400, "invalid_message");
    db.lockUser(user);
    var active = db.one("SELECT * FROM backend_sessions WHERE user_id=? AND id=?", user, session);
    String hash = PerceptionAccess.hash(session + "\n" + text);
    var existing = db.one("SELECT * FROM backend_messages WHERE user_id=? AND id=?", user, message);
    if (active.isPresent()
        && text(active.get(), "active_message") != null
        && !message.equals(text(active.get(), "active_message"))
        && time(active.get(), "active_lease_until") != null
        && time(active.get(), "active_lease_until").isAfter(db.now())
        && (existing.isEmpty() || text(existing.get(), "reply_json") == null))
      throw new BackendException(409, "session_message_in_progress");
    if (existing.isPresent()) {
      var row = existing.get();
      if (!hash.equals(BackendStore.text(row, "input_hash")))
        throw new BackendException(409, "message_id_content_conflict");
      if (BackendStore.text(row, "reply_json") != null)
        return new Started(
            vault.map(user, BackendStore.text(row, "reply_json")), null, context(user, session));
      var task =
          db.one(
              "SELECT id FROM backend_tasks WHERE user_id=? AND request_key=?",
              user,
              "message:" + message);
      if (task.isPresent())
        return new Started(
            null, BackendStore.text(task.get(), "id"), lease(user, session, message));
      if (time(row, "created_at").plusSeconds(30).isAfter(db.now()))
        throw new BackendException(409, "message_processing_retry_same_id");
      db.update(
          "UPDATE backend_messages SET created_at=? WHERE user_id=? AND id=?",
          db.now(),
          user,
          message);
    } else
      db.update(
          "INSERT INTO backend_messages VALUES(?,?,?,?,?,?,?)",
          message,
          user,
          session,
          hash,
          null,
          "processing",
          db.now());
    return new Started(null, null, lease(user, session, message));
  }

  private Map<String, Object> lease(String user, String session, String message) {
    Map<String, Object> snapshot = new LinkedHashMap<>(context(user, session));
    String token = UUID.randomUUID().toString();
    if (db.one("SELECT id FROM backend_sessions WHERE user_id=? AND id=?", user, session).isEmpty())
      db.update(
          "INSERT INTO backend_sessions(id,user_id,context_cipher,expires_at,version) VALUES(?,?,?,?,0)",
          session,
          user,
          vault.seal(user, Map.of()),
          db.now().plusSeconds(1800));
    db.update(
        "UPDATE backend_sessions SET active_message=?,active_token=?,active_lease_until=? WHERE user_id=? AND id=?",
        message,
        token,
        db.now().plusSeconds(30),
        user,
        session);
    snapshot.put("_leaseToken", token);
    return snapshot;
  }

  public void requireCurrent(
      String user, String session, String message, Map<String, Object> context) {
    var active =
        db.one(
                "SELECT active_message,active_token FROM backend_sessions WHERE user_id=? AND id=?",
                user,
                session)
            .orElseThrow();
    if (!message.equals(text(active, "active_message"))
        || !Objects.equals(context.get("_leaseToken"), text(active, "active_token")))
      throw new BackendException(409, "message_lease_lost");
  }

  @Transactional
  public Map<String, Object> complete(
      String user,
      String session,
      String message,
      Map<String, Object> response,
      Map<String, Object> context) {
    db.lockUser(user);
    requireCurrent(user, session, message, context);
    Map<String, Object> saved = new LinkedHashMap<>(context);
    saved.remove("_leaseToken");
    db.update(
        "UPDATE backend_messages SET reply_json=?,state='completed' WHERE user_id=? AND id=?",
        vault.seal(user, response),
        user,
        message);
    db.update(
        "UPDATE backend_sessions SET context_cipher=?,expires_at=?,version=version+1,active_message=NULL,active_token=NULL,active_lease_until=NULL WHERE user_id=? AND id=?",
        vault.seal(user, saved),
        db.now().plusSeconds(1800),
        user,
        session);
    return response;
  }

  private Map<String, Object> context(String user, String session) {
    return db.one("SELECT * FROM backend_sessions WHERE user_id=? AND id=?", user, session)
        .filter(r -> time(r, "expires_at").isAfter(db.now()))
        .map(r -> vault.map(user, text(r, "context_cipher")))
        .orElseGet(LinkedHashMap::new);
  }

  @Transactional
  public void injectionFailure(String user) {
    var row = db.lockUser(user);
    long fails = number(row, "failures") + 1;
    db.update(
        "UPDATE backend_users SET failures=?,locked_until=?,updated_at=? WHERE user_id=?",
        fails,
        fails >= 5 ? db.now().plusSeconds(60) : null,
        db.now(),
        user);
    audit.write(user, null, null, "injection_blocked", Map.of("reason", "untrusted_instruction"));
  }
}
