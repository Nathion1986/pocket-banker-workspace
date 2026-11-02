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
public class AppEventJournal {
  private final BackendStore db;
  private final VaultCodec vault;

  public static void validSession(String session) {
    if (session == null || !session.matches("[A-Za-z0-9_-]{1,100}"))
      throw new BackendException(400, "invalid_session_id");
  }

  @Transactional
  public void ensure(String user, String session) {
    validSession(session);
    db.lockUser(user);
    if (db.one(
            "SELECT session_id FROM app_sessions WHERE user_id=? AND session_id=?", user, session)
        .isEmpty()) db.update("INSERT INTO app_sessions VALUES(?,?,?)", user, session, db.now());
  }

  @Transactional
  public void append(
      String user,
      String session,
      String key,
      String kind,
      String channel,
      Map<String, Object> payload) {
    ensure(user, session);
    if (db.one("SELECT id FROM app_events WHERE user_id=? AND source_key=?", user, key).isEmpty())
      db.update(
          "INSERT INTO app_events(user_id,session_id,source_key,kind,channel,payload_cipher,created_at) VALUES(?,?,?,?,?,?,?)",
          user,
          session,
          key,
          kind,
          channel,
          vault.seal(user, payload),
          db.now());
  }

  @Transactional
  public Map<String, Object> input(
      String user, String session, String id, String text, String channel) {
    ensure(user, session);
    if (id == null
        || !id.matches("[A-Za-z0-9_.:-]{1,160}")
        || text == null
        || text.isBlank()
        || text.length() > 8192) throw new BackendException(400, "invalid_message");
    String hash = PerceptionAccess.hash(session + "\n" + text);
    var old = db.one("SELECT * FROM app_inputs WHERE user_id=? AND request_id=?", user, id);
    if (old.isPresent()) {
      if (!hash.equals(BackendStore.text(old.get(), "input_hash")))
        throw new BackendException(409, "message_id_content_conflict");
      if (BackendStore.text(old.get(), "result_cipher") != null)
        return vault.map(user, BackendStore.text(old.get(), "result_cipher"));
    } else {
      db.update(
          "INSERT INTO app_inputs(user_id,request_id,session_id,input_hash) VALUES(?,?,?,?)",
          user,
          id,
          session,
          hash);
      append(user, session, "input:" + id, "user_message", channel, Map.of("text", text));
    }
    return null;
  }

  @Transactional
  public void finish(
      String user, String session, String id, String channel, Map<String, Object> reply) {
    ensure(user, session);
    Object task = reply.get("id");
    if (task != null && reply.containsKey("state")) {
      if (db.one("SELECT task_id FROM app_task_refs WHERE task_id=?", task).isEmpty())
        db.update(
            "INSERT INTO app_task_refs(task_id,user_id,session_id) VALUES(?,?,?)",
            task,
            user,
            session);
    }
    db.update(
        "UPDATE app_inputs SET result_cipher=? WHERE user_id=? AND request_id=?",
        vault.seal(user, reply),
        user,
        id);
    append(
        user, session, "reply:" + id, Objects.toString(reply.get("kind"), "reply"), channel, reply);
  }

  public List<Map<String, Object>> refs(String user, String session) {
    return db.rows(
        "SELECT task_id,snapshot_hash FROM app_task_refs WHERE user_id=? AND session_id=? ORDER BY task_id",
        user,
        session);
  }

  public void requireTask(String user, String session, String id) {
    if (db.one(
            "SELECT task_id FROM app_task_refs WHERE user_id=? AND session_id=? AND task_id=?",
            user,
            session,
            id)
        .isEmpty()) throw new BackendException(404, "task_not_found");
  }

  @Transactional
  public void snapshot(
      String user, String session, String task, String hash, Map<String, Object> result) {
    ensure(user, session);
    var row =
        db.one(
                "SELECT snapshot_hash FROM app_task_refs WHERE task_id=? AND user_id=? AND session_id=?",
                task,
                user,
                session)
            .orElseThrow();
    if (!hash.equals(text(row, "snapshot_hash"))) {
      append(user, session, "state:" + task + ":" + hash, "task_state", "system", result);
      db.update("UPDATE app_task_refs SET snapshot_hash=? WHERE task_id=?", hash, task);
    }
  }

  @Transactional
  public Map<String, Object> events(String user, String session, long since, int limit) {
    ensure(user, session);
    if (since < 0 || limit < 1 || limit > 100)
      throw new BackendException(400, "invalid_event_cursor");
    var rows =
        db.rows(
            "SELECT * FROM app_events WHERE user_id=? AND session_id=? AND id>? ORDER BY id LIMIT ?",
            user,
            session,
            since,
            limit + 1);
    boolean more = rows.size() > limit;
    List<Map<String, Object>> result = new ArrayList<>();
    long cursor = since;
    for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
      cursor = number(row, "id");
      var event = new LinkedHashMap<String, Object>();
      event.put("id", cursor);
      event.put("kind", text(row, "kind"));
      event.put("channel", text(row, "channel"));
      event.put("payload", vault.map(user, text(row, "payload_cipher")));
      event.put("createdAt", time(row, "created_at").toString());
      result.add(event);
    }
    return Map.of("events", result, "nextCursor", cursor, "hasMore", more);
  }

  @Transactional
  public Map<String, Object> command(String user, String key, String hash) {
    db.lockUser(user);
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,160}"))
      throw new BackendException(400, "idempotency_key_required");
    var old = db.one("SELECT * FROM app_commands WHERE user_id=? AND id_key=?", user, key);
    if (old.isPresent()) {
      if (!hash.equals(text(old.get(), "input_hash")))
        throw new BackendException(409, "idempotency_content_conflict");
      String value = text(old.get(), "result_cipher");
      return value == null ? null : vault.map(user, value);
    }
    db.update("INSERT INTO app_commands(user_id,id_key,input_hash) VALUES(?,?,?)", user, key, hash);
    return null;
  }

  @Transactional
  public void commandResult(String user, String key, Map<String, Object> result) {
    db.update(
        "UPDATE app_commands SET result_cipher=? WHERE user_id=? AND id_key=?",
        vault.seal(user, result),
        user,
        key);
  }
}
