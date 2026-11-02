package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.channels.telegram.TelegramClient;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.PerceptionAccess;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TelegramFacade {
  @org.springframework.beans.factory.annotation.Autowired private AppEventJournal appEvents;
  private final BackendFacade backend;
  private final BackendStore db;
  private final PerceptionAccess access;
  private final TelegramClient telegram;
  private final ObjectMapper mapper;
  private final com.pocketbanker.security.VaultCodec vault;
  private final ChannelDeliveryTransactions deliveries;

  @Value("${backend.telegram-webhook-key:}")
  private String webhookKey;

  @Transactional
  public Map<String, Object> link(String auth) {
    return link(auth, "shared");
  }

  @Transactional
  public Map<String, Object> link(String auth, String session) {
    if (session == null || !session.matches("[A-Za-z0-9_-]{1,100}"))
      throw new BackendException(400, "invalid_session_id");
    String user = access.requireUser(auth);
    db.lockUser(user);
    String id = UUID.randomUUID().toString(), token = PerceptionAccess.newToken();
    db.update(
        "INSERT INTO backend_channel_links(id,user_id,token_hash,expires_at,consumed,session_id) VALUES(?,?,?,?,?,?)",
        id,
        user,
        PerceptionAccess.hash(token),
        db.now().plusSeconds(300),
        false,
        session);
    return Map.of(
        "sessionId",
        session,
        "linkId",
        id,
        "token",
        token,
        "expiresAt",
        db.now().plusSeconds(300).toString(),
        "command",
        "/start " + token);
  }

  @Transactional
  public Map<String, Object> receive(String key, Map<String, Object> update) {
    if (webhookKey.isBlank() || key == null || !PerceptionAccess.equal(webhookKey, key))
      throw new BackendException(401, "invalid_telegram_webhook_secret");
    long id;
    try {
      String value = Objects.toString(update.get("update_id"), "");
      if (!value.matches("[0-9]{1,18}")) throw new NumberFormatException();
      id = Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new BackendException(400, "invalid_telegram_update_id");
    }
    String hash;
    try {
      Object canonical = mapper.convertValue(update, Object.class);
      hash = PerceptionAccess.hash(canonicalJson(canonical));
    } catch (Exception invalid) {
      throw new BackendException(400, "invalid_telegram_update");
    }
    var prior = db.one("SELECT * FROM backend_telegram_updates WHERE update_id=?", id);
    if (prior.isPresent()) {
      String previous = text(prior.get(), "input_hash");
      if (previous != null && !previous.equals(hash))
        throw new BackendException(409, "telegram_update_content_conflict");
      return Map.of("ok", true, "duplicate", true);
    }
    var callback = update.get("callback_query") instanceof Map<?, ?> c ? c : null;
    var message =
        callback != null && callback.get("message") instanceof Map<?, ?> m
            ? m
            : update.get("message") instanceof Map<?, ?> m ? m : null;
    if (message == null
        || !(message.get("chat") instanceof Map<?, ?> chat)
        || !"private".equals(chat.get("type"))) return Map.of("ok", true, "ignored", true);
    String chatId = Objects.toString(chat.get("id"), "");
    if (!chatId.matches("[1-9][0-9]{0,18}"))
      throw new BackendException(400, "invalid_telegram_chat");
    var actor = callback != null ? callback.get("from") : message.get("from");
    if ((callback != null && !(actor instanceof Map<?, ?>))
        || actor instanceof Map<?, ?> from && !chatId.equals(Objects.toString(from.get("id"), "")))
      throw new BackendException(403, "telegram_actor_mismatch");
    String input = Objects.toString(message.get("text"), "");
    if (callback == null && (input.startsWith("/start ") || input.startsWith("/link "))) {
      String token = input.substring(input.indexOf(' ') + 1).strip();
      var link =
          db.one(
                  "SELECT user_id FROM backend_channel_links WHERE token_hash=?",
                  PerceptionAccess.hash(token))
              .orElseThrow(() -> new BackendException(403, "invalid_channel_link"));
      db.lockUser(text(link, "user_id"));
      var duplicate = duplicate(id, hash);
      if (duplicate != null) return duplicate;
      bind(chatId, token);
      var binding = binding(chatId);
      String user = text(binding, "user_id");
      Map<String, Object> result = Map.of("ok", true, "sessionId", text(binding, "session_id"));
      persist(id, user, hash, result);
      deliveries.enqueue(
          user,
          chatId,
          "telegram-update:" + id,
          "reply",
          Long.toString(id),
          Map.of("text", "已绑定口袋银行家，可在 App 接续同一会话。"));
      return result;
    }
    var binding = binding(chatId);
    String user = text(binding, "user_id");
    db.lockUser(user);
    // Unlink commits under this same user lock. The pre-lock snapshot is not authority.
    var currentBinding = binding(chatId);
    if (!user.equals(text(currentBinding, "user_id"))
        || number(binding, "generation") != number(currentBinding, "generation")
        || !Objects.equals(binding.get("session_id"), currentBinding.get("session_id")))
      throw new BackendException(403, "telegram_user_not_bound");
    binding = currentBinding;
    var duplicate = duplicate(id, hash);
    if (duplicate != null) return duplicate;
    Map<String, Object> result;
    boolean appMode = Boolean.TRUE.equals(binding.get("app_mode"));
    if (appMode && (callback != null || input.startsWith("/verify")))
      throw new BackendException(409, "app_confirmation_required");
    if (appMode)
      appEvents.input(user, text(binding, "session_id"), "telegram:" + id, input, "telegram");
    if (callback != null) {
      String callbackId = Objects.toString(callback.get("id"), "");
      if (callbackId.isBlank() || callbackId.length() > 160)
        throw new BackendException(400, "invalid_telegram_callback_id");
      String[] fields = Objects.toString(callback.get("data"), "").split(":", -1);
      if (fields.length != 3
          || !Set.of("confirm", "cancel").contains(fields[0])
          || !fields[1].matches("[a-fA-F0-9-]{36}"))
        throw new BackendException(400, "invalid_telegram_callback");
      long version = version(fields[2]);
      result = backend.confirmOwned(user, fields[1], fields[0].equals("confirm"), version);
    } else if (input.startsWith("/verify")) {
      String[] fields = input.strip().split("\\s+");
      if (fields.length != 5 || !fields[0].equals("/verify"))
        throw new BackendException(400, "telegram_verification_requires_two_factors");
      result = backend.verifyOwned(user, fields[1], fields[3], fields[4], version(fields[2]));
    } else
      result =
          backend.handle(
              IncomingMessage.builder()
                  .userId(user)
                  .channel("telegram")
                  .msgId("telegram:" + id)
                  .text(input)
                  .meta(Map.of("sessionId", text(binding, "session_id")))
                  .build());
    if (appMode)
      appEvents.finish(
          user,
          text(binding, "session_id"),
          "telegram:" + id,
          "telegram",
          AppGatewayFacade.project(result));
    persist(id, user, hash, result);
    ReplyEvent event = ReplyEvent.from("telegram:" + id, result);
    Map<String, Object> body = new LinkedHashMap<>();
    String replyText = event.text();
    if (appMode) {
      int alternative = replyText.indexOf("；或发送 /verify");
      if (alternative >= 0) replyText = replyText.substring(0, alternative) + "。请在App完成本地测试验证。";
    }
    body.put("text", replyText);
    if (!appMode && event.markup() != null) body.put("markup", event.markup());
    if (callback != null) {
      String callbackId = Objects.toString(callback.get("id"), "");
      if (callbackId.isBlank() || callbackId.length() > 160)
        throw new BackendException(400, "invalid_telegram_callback_id");
      body.put("callbackQueryId", callbackId);
    }
    String delivery =
        deliveries.enqueue(user, chatId, "telegram-update:" + id, "reply", Long.toString(id), body);
    return Map.of(
        "ok",
        true,
        "result",
        result,
        "replyEvent",
        event,
        "deliveryId",
        delivery,
        "sessionId",
        text(binding, "session_id"));
  }

  private Map<String, Object> duplicate(long id, String hash) {
    var prior = db.one("SELECT input_hash FROM backend_telegram_updates WHERE update_id=?", id);
    if (prior.isEmpty()) return null;
    if (text(prior.get(), "input_hash") != null && !hash.equals(text(prior.get(), "input_hash")))
      throw new BackendException(409, "telegram_update_content_conflict");
    return Map.of("ok", true, "duplicate", true);
  }

  private Map<String, Object> binding(String chat) {
    return db.one(
            "SELECT * FROM backend_channel_bindings WHERE channel='telegram' AND external_id=? AND state='active'",
            chat)
        .orElseThrow(() -> new BackendException(403, "telegram_user_not_bound"));
  }

  private long version(String value) {
    try {
      if (!value.matches("[1-9][0-9]{0,17}")) throw new NumberFormatException();
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new BackendException(400, "invalid_task_version");
    }
  }

  private String canonicalJson(Object value) throws java.io.IOException {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((k, v) -> sorted.put(k.toString(), v));
      List<String> entries = new ArrayList<>();
      for (var e : sorted.entrySet())
        entries.add(mapper.writeValueAsString(e.getKey()) + ":" + canonicalJson(e.getValue()));
      return "{" + String.join(",", entries) + "}";
    }
    if (value instanceof List<?> list) {
      List<String> items = new ArrayList<>();
      for (Object item : list) items.add(canonicalJson(item));
      return "[" + String.join(",", items) + "]";
    }
    return mapper.writeValueAsString(value);
  }

  private void persist(long id, String user, String hash, Map<String, Object> result) {
    db.update(
        "INSERT INTO backend_telegram_updates(update_id,user_id,state,created_at,input_hash,result_cipher) VALUES(?,?,'processed',?,?,?)",
        id,
        user,
        db.now(),
        hash,
        vault.seal(user, result));
  }

  public Map<String, Object> status(String auth) {
    String user = access.requireUser(auth);
    var binding =
        db.one(
            "SELECT session_id,created_at FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND state='active'",
            user);
    return binding
        .<Map<String, Object>>map(
            row -> Map.of("bound", true, "sessionId", text(row, "session_id")))
        .orElseGet(() -> Map.of("bound", false));
  }

  public List<Map<String, Object>> deliveries(String auth, int page) {
    if (page < 0 || page > 1000000) throw new BackendException(400, "invalid_page");
    return deliveries.replies(access.requireUser(auth), page);
  }

  @Transactional
  public void unlink(String auth) {
    String user = access.requireUser(auth);
    db.lockUser(user);
    db.update(
        "UPDATE backend_channel_bindings SET state='revoked',generation=generation+1 WHERE user_id=? AND channel='telegram'",
        user);
    db.update(
        "UPDATE backend_channel_links SET consumed=TRUE WHERE user_id=? AND consumed=FALSE", user);
    db.update(
        "UPDATE backend_channel_deliveries SET state='cancelled',generation=generation+1,lease_until=NULL,error_code='channel_unbound',updated_at=? WHERE user_id=? AND state='pending'",
        db.now(),
        user);
  }

  @Transactional
  public void bind(String chat, String token) {
    var found =
        db.one(
                "SELECT * FROM backend_channel_links WHERE token_hash=? AND consumed=FALSE",
                PerceptionAccess.hash(token))
            .orElseThrow(() -> new BackendException(403, "invalid_channel_link"));
    String user = text(found, "user_id");
    db.lockUser(user);
    var locked =
        db.one("SELECT * FROM backend_channel_links WHERE id=? FOR UPDATE", text(found, "id"))
            .orElseThrow();
    if (Boolean.TRUE.equals(locked.get("consumed"))
        || !time(locked, "expires_at").isAfter(db.now()))
      throw new BackendException(403, "channel_link_expired");
    var prior =
        db.one(
            "SELECT user_id FROM backend_channel_bindings WHERE channel='telegram' AND external_id=?",
            chat);
    if (prior.isPresent() && !user.equals(text(prior.get(), "user_id")))
      throw new BackendException(409, "chat_already_bound");
    var other =
        db.one(
            "SELECT external_id FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND state='active' AND external_id<>?",
            user,
            chat);
    if (other.isPresent()) throw new BackendException(409, "user_already_bound");
    if (prior.isEmpty()) {
      try {
        db.update(
            "INSERT INTO backend_channel_bindings(user_id,channel,external_id,state,created_at,session_id) VALUES(?,?,?,?,?,?)",
            user,
            "telegram",
            chat,
            "active",
            db.now(),
            text(locked, "session_id"));
      } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
        throw new BackendException(409, "chat_already_bound");
      }
    } else if (db.update(
            "UPDATE backend_channel_bindings SET state='active',generation=generation+1,created_at=?,session_id=? WHERE channel='telegram' AND external_id=? AND user_id=?",
            db.now(),
            text(locked, "session_id"),
            chat,
            user)
        != 1) {
      throw new BackendException(409, "chat_already_bound");
    }
    boolean appMode = Boolean.TRUE.equals(locked.get("app_mode"));
    db.update(
        "UPDATE backend_channel_bindings SET app_mode=? WHERE user_id=? AND channel='telegram' AND external_id=?",
        appMode,
        user,
        chat);
    if (appMode)
      appEvents.append(
          user,
          text(locked, "session_id"),
          "linked:" + text(found, "id"),
          "linked",
          "telegram",
          Map.of("linked", true));
    db.update("UPDATE backend_channel_links SET consumed=TRUE WHERE id=?", text(found, "id"));
  }

  @Transactional
  public void record(long id, String user) {
    if (db.one("SELECT update_id FROM backend_telegram_updates WHERE update_id=?", id).isEmpty())
      db.update(
          "INSERT INTO backend_telegram_updates(update_id,user_id,state,created_at) VALUES(?,?,?,?)",
          id,
          user,
          "processed",
          db.now());
  }
}
