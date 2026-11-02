package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.PerceptionAccess;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppGatewayFacade {
  private final BackendFacade backend;
  private final AppEventJournal journal;
  private final TelegramFacade telegram;
  private final BackendStore db;
  private final ObjectMapper mapper;
  private final ChannelDeliveryTransactions deliveries;
  private final com.pocketbanker.security.AppAuthentication authentication;
  private final WorkflowTransactions workflow;

  private String user(String auth, String session) {
    String user = backend.user(auth);
    journal.ensure(user, session);
    return user;
  }

  public Map<String, Object> status(String auth, String session) {
    String user = user(auth, session);
    var row =
        db.one(
            "SELECT session_id FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND state='active'",
            user);
    return Map.of(
        "linked",
        row.isPresent() && session.equals(text(row.get(), "session_id")),
        "channel",
        "telegram",
        "conversationId",
        session,
        "userId",
        user,
        "protocolVersion",
        "0.4",
        "environment",
        "local_synthetic");
  }

  @Transactional
  public Map<String, Object> link(String auth, String session) {
    lockAccountSession(auth);
    String user = user(auth, session);
    var result = telegram.link(auth, session);
    db.update(
        "UPDATE backend_channel_links SET app_mode=TRUE WHERE id=? AND user_id=?",
        result.get("linkId"),
        user);
    return Map.of(
        "code",
        result.get("token"),
        "expires_in",
        300,
        "command",
        "/link " + result.get("token"),
        "channel",
        "telegram");
  }

  @Transactional
  public void unlink(String auth, String session) {
    lockAccountSession(auth);
    String user = user(auth, session);
    var old =
        db.one(
            "SELECT session_id,generation FROM backend_channel_bindings WHERE user_id=? AND channel='telegram' AND state='active'",
            user);
    telegram.unlink(auth);
    if (old.isPresent())
      journal.append(
          user,
          text(old.get(), "session_id"),
          "unlink:" + number(old.get(), "generation"),
          "unlinked",
          "system",
          Map.of("linked", false));
  }

  public Map<String, Object> message(String auth, String session, String id, String text) {
    String user = user(auth, session);
    String request = "app:" + PerceptionAccess.hash(session + "\n" + Objects.toString(id, ""));
    var cached = journal.input(user, session, request, text, "app");
    if (cached != null) return cached;
    var raw = backend.chatOwned(user, session, request, text);
    var reply = project(raw);
    journal.finish(user, session, request, "app", reply);
    return reply;
  }

  public Map<String, Object> task(String auth, String session, String id) {
    String user = user(auth, session);
    journal.requireTask(user, session, id);
    return project(backend.task(auth, id));
  }

  public Map<String, Object> events(String auth, String session, long since, int limit) {
    String user = user(auth, session);
    for (var ref : journal.refs(user, session)) {
      String id = text(ref, "task_id");
      var value = project(backend.task(auth, id));
      // 快照哈希只取稳定字段：剔除时间戳，否则每次轮询 hash 都变，同一状态被反复 append
      var stable = new java.util.LinkedHashMap<String, Object>(value);
      stable.remove("updated_at");
      stable.remove("created_at");
      stable.remove("asOf");
      String hash = hash(stable);
      journal.snapshot(user, session, id, hash, value);
    }
    return journal.events(user, session, since, limit);
  }

  @Transactional
  public Map<String, Object> decide(
      String auth, String session, String id, String decision, long version, String key) {
    lockAccountSession(auth);
    String user = user(auth, session);
    journal.requireTask(user, session, id);
    if (!Set.of("confirm", "cancel").contains(decision))
      throw new BackendException(400, "invalid_decision");
    String hash = PerceptionAccess.hash(session + "|" + id + "|" + decision + "|" + version);
    var cached = journal.command(user, key, hash);
    if (cached != null) return cached;
    var raw = backend.confirm(auth, id, decision.equals("confirm"), version);
    var result = project(raw);
    journal.commandResult(user, key, result);
    journal.append(user, session, "decision:" + key, "decision", "app", result);
    notifyIm(user, session, id, key, result);
    return result;
  }

  @Transactional
  public Map<String, Object> verify(
      String auth,
      String session,
      String id,
      String code,
      String factor,
      long version,
      String key) {
    lockAccountSession(auth);
    String user = user(auth, session);
    journal.requireTask(user, session, id);
    String hash =
        PerceptionAccess.hash(
            session + "|" + id + "|verify|" + version + "|" + code + "|" + factor);
    var cached = journal.command(user, key, hash);
    if (cached != null) return cached;
    var raw = backend.verify(auth, id, code, factor, version);
    var result = project(raw);
    journal.commandResult(user, key, result);
    journal.append(user, session, "verify:" + key, "decision", "app", result);
    notifyIm(user, session, id, key, result);
    return result;
  }

  @Transactional
  public Map<String, Object> authChallenge(String auth, String session, String id, long version) {
    var who = authentication.lockIdentity(auth);
    user(auth, session);
    journal.requireTask(who.user(), session, id);
    var task = workflow.requireApplicationVerification(who.user(), id, version);
    if (!Boolean.TRUE.equals(authentication.me(auth).get("mfaEnabled")))
      throw new BackendException(409, "mfa_enrollment_required");
    String challenge = UUID.randomUUID().toString();
    db.update(
        "UPDATE auth_operation_challenges SET state='revoked' WHERE user_id=? AND session_id=? AND task_id=? AND state='pending'",
        who.user(),
        who.session(),
        id);
    db.update(
        "INSERT INTO auth_operation_challenges(id,user_id,session_id,conversation_id,task_id,task_version,operation_digest,expires_at) VALUES(?,?,?,?,?,?,?,?)",
        challenge,
        who.user(),
        who.session(),
        session,
        id,
        version,
        operationDigest(task),
        db.now().plusSeconds(120));
    return Map.of(
        "challengeId",
        challenge,
        "version",
        version,
        "expiresIn",
        120,
        "factors",
        List.of("password", "totp"),
        "proposal",
        project(workflow.view(who.user(), id)));
  }

  @Transactional
  public Map<String, Object> authVerify(
      String auth,
      String session,
      String id,
      String challenge,
      long version,
      String password,
      String totp,
      String key) {
    var who = authentication.lockIdentity(auth);
    user(auth, session);
    journal.requireTask(who.user(), session, id);
    String fingerprint =
        authentication.fingerprint(
            mapperValue(List.of(who.session(), session, id, challenge, version, password, totp)));
    var cached = journal.command(who.user(), key, fingerprint);
    if (cached != null) return cached;
    var task = workflow.requireApplicationVerification(who.user(), id, version);
    var c =
        db.one(
                "SELECT * FROM auth_operation_challenges WHERE id=? AND user_id=? FOR UPDATE",
                challenge,
                who.user())
            .orElseThrow(() -> new BackendException(404, "verification_challenge_not_found"));
    if (!who.session().equals(text(c, "session_id"))
        || !session.equals(text(c, "conversation_id"))
        || !id.equals(text(c, "task_id"))
        || number(c, "task_version") != version
        || !operationDigest(task).equals(text(c, "operation_digest"))
        || !"pending".equals(text(c, "state"))
        || !time(c, "expires_at").isAfter(db.now())
        || number(c, "failure_count") >= 5)
      throw new BackendException(409, "verification_challenge_inactive");
    if (!authentication.verifyFactors(who, password, totp)) {
      db.update(
          "UPDATE auth_operation_challenges SET failure_count=failure_count+1 WHERE id=?",
          challenge);
      var failure =
          Map.<String, Object>of(
              "error", "verification_failed", "taskId", id, "state", "WAITING_VERIFICATION");
      journal.commandResult(who.user(), key, failure);
      return failure;
    }
    db.update("UPDATE auth_operation_challenges SET state='used' WHERE id=?", challenge);
    var result = project(workflow.verifyApplication(who.user(), id, version));
    journal.commandResult(who.user(), key, result);
    journal.append(who.user(), session, "auth-verify:" + key, "decision", "app", result);
    notifyIm(who.user(), session, id, key, result);
    return result;
  }

  private String operationDigest(Map<String, Object> task) {
    // Include encrypted full plan and each complete node input; any parameter replacement
    // invalidates it.
    return PerceptionAccess.hash(
        mapperValue(
            List.of(
                task.get("id"),
                task.get("version"),
                task.get("required_risk"),
                task.get("plan_cipher"),
                db.rows(
                    "SELECT id,action_id,input_cipher FROM backend_nodes WHERE task_id=? ORDER BY id",
                    task.get("id")))));
  }

  private String mapperValue(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("auth_content_invalid");
    }
  }

  private void notifyIm(
      String user, String session, String id, String key, Map<String, Object> reply) {
    var row =
        db.one(
            "SELECT external_id FROM backend_channel_bindings WHERE user_id=? AND session_id=? AND state='active' AND channel='telegram'",
            user,
            session);
    if (row.isPresent())
      deliveries.enqueue(
          user,
          text(row.get(), "external_id"),
          "app-decision:" + key,
          "reply",
          id,
          Map.of("text", reply.get("text")));
  }

  private void lockAccountSession(String authorization) {
    if (PerceptionAccess.bearer(authorization).startsWith("at_"))
      authentication.lockIdentity(authorization);
  }

  private String hash(Object value) {
    try {
      return PerceptionAccess.hash(mapper.writeValueAsString(value));
    } catch (Exception e) {
      throw new IllegalStateException("app_projection_invalid");
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> project(Map<String, Object> raw) {
    var result = new LinkedHashMap<String, Object>();
    String id = Objects.toString(raw.getOrDefault("taskId", raw.get("id")), null);
    String state = Objects.toString(raw.get("state"), "");
    boolean pending = Set.of("WAITING_CONFIRMATION", "WAITING_VERIFICATION").contains(state);
    result.put(
        "kind", pending ? "proposal" : id != null && !state.isBlank() ? "task_state" : "reply");
    result.put("text", ReplyEvent.from("app", raw).text());
    if (raw.get("error") != null) result.put("error", raw.get("error"));
    if (id != null && !state.isBlank()) {
      result.put("id", id);
      result.put("version", raw.get("version"));
      result.put("state", state);
      result.put("status", pending ? "pending" : state.toLowerCase(Locale.ROOT));
      result.put(
          "risk", Objects.toString(raw.get("required_risk"), "GREEN").toLowerCase(Locale.ROOT));
      result.put("nodes", raw.getOrDefault("nodes", List.of()));
      Map<String, Object> input = Map.of();
      String action = "任务";
      if (raw.get("nodes") instanceof List<?> nodes
          && !nodes.isEmpty()
          && nodes.get(0) instanceof Map<?, ?> node) {
        if (node.get("input") instanceof Map<?, ?> values) input = (Map<String, Object>) values;
        action = Objects.toString(node.get("action_id"), "任务");
      }
      result.put("actionTitle", action);
      result.put("amount", Objects.toString(input.get("amount"), "0.00"));
      result.put("currency", Objects.toString(input.get("currency"), "CNY"));
      result.put(
          "payee",
          Objects.toString(
              input.get("toName"), Objects.toString(input.get("toAccountId"), "本人账户")));
      result.put("memo", Objects.toString(input.get("memo"), ""));
      result.put("reason", pending ? "请核对服务器任务参数；确认不等于银行到账。" : "以核验后的任务状态为准。");
    }
    return result;
  }
}
