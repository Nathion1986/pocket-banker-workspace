package com.pocketbanker.security;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque sessions. Mutations serialize on backend_users then auth_accounts, never on caller IDs.
 */
@Service
@RequiredArgsConstructor
public class AppAuthentication {
  private final BackendStore db;
  private final VaultCodec vault;
  private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final String dummyHash = passwords.encode("synthetic-dummy-password-not-an-account");

  @Value("${backend.auth-bootstrap-user:}")
  private String bootstrapUser;

  @Value("${backend.auth-bootstrap-key:}")
  private String bootstrapKey;

  @Value("${backend.auth-mac-key:}")
  private String macKey;

  @Value("${backend.mode:local-demo}")
  private String mode;

  public record Identity(String user, String session, long epoch, Instant expiresAt) {}

  public Map<String, Object> config() {
    return Map.of(
        "authentication",
        "account",
        "setupAvailable",
        bootstrapKey.length() >= 32
            && !bootstrapUser.isBlank()
            && db.one("SELECT user_id FROM auth_accounts WHERE user_id=?", bootstrapUser).isEmpty(),
        "strongVerification",
        "password_totp",
        "localDemoAllowed",
        "local-demo".equals(mode),
        "accountReady",
        !bootstrapUser.isBlank() && activeFactor(bootstrapUser) && macKey.length() >= 32);
  }

  @Transactional
  public Map<String, Object> setup(String key, String username, String password) {
    if (bootstrapKey.length() < 32
        || !bootstrapUser.matches("[A-Za-z0-9_-]{1,100}")
        || key == null
        || !PerceptionAccess.equal(bootstrapKey, key))
      throw new BackendException(404, "account_setup_unavailable");
    validatePassword(password);
    String name = username(username);
    db.lockUser(bootstrapUser);
    if (db.one("SELECT user_id FROM auth_accounts WHERE user_id=?", bootstrapUser).isPresent())
      throw new BackendException(409, "account_already_initialized");
    if (db.one("SELECT user_id FROM auth_accounts WHERE username_normalized=?", name).isPresent())
      throw new BackendException(409, "username_unavailable");
    db.update(
        "INSERT INTO auth_accounts(user_id,username_normalized,password_hash,created_at,updated_at) VALUES(?,?,?,?,?)",
        bootstrapUser,
        name,
        passwords.encode(password),
        db.now(),
        db.now());
    return issue(account(bootstrapUser));
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> login(String username, String password) {
    String name = username(username);
    var candidate = db.one("SELECT user_id FROM auth_accounts WHERE username_normalized=?", name);
    if (candidate.isEmpty()) {
      passwords.matches(Objects.toString(password, ""), dummyHash);
      throw denied();
    }
    var row = lockAccount(text(candidate.get(), "user_id"));
    requireUsable(row);
    if (!passwordMatches(row, password)) {
      fail(row);
      throw denied();
    }
    if (activeFactor(text(row, "user_id"))) {
      String challenge = token("lc_");
      db.update(
          "INSERT INTO auth_login_challenges(id,user_id,auth_epoch,expires_at) VALUES(?,?,?,?)",
          PerceptionAccess.hash(challenge),
          row.get("user_id"),
          row.get("auth_epoch"),
          db.now().plusSeconds(120));
      return Map.of("mfaRequired", true, "challengeId", challenge, "expiresIn", 120);
    }
    clearFailures(row);
    return issue(row);
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> loginMfa(String challenge, String code) {
    var hint =
        db.one("SELECT * FROM auth_login_challenges WHERE id=?", PerceptionAccess.hash(challenge));
    if (hint.isEmpty()) throw denied();
    var row = lockAccount(text(hint.get(), "user_id"));
    requireUsable(row);
    var c =
        db.one(
                "SELECT * FROM auth_login_challenges WHERE id=? FOR UPDATE",
                PerceptionAccess.hash(challenge))
            .orElseThrow();
    if (c.get("used_at") != null
        || !time(c, "expires_at").isAfter(db.now())
        || number(c, "auth_epoch") != number(row, "auth_epoch")
        || number(c, "failures") >= 5) throw denied();
    if (!consumeTotp(text(row, "user_id"), code)) {
      db.update("UPDATE auth_login_challenges SET failures=failures+1 WHERE id=?", c.get("id"));
      fail(row);
      throw denied();
    }
    db.update("UPDATE auth_login_challenges SET used_at=? WHERE id=?", db.now(), c.get("id"));
    clearFailures(row);
    return issue(row);
  }

  public Identity identity(String authorization) {
    String raw = PerceptionAccess.bearer(authorization);
    var row =
        db.one(
                "SELECT s.*,a.state AS account_state,a.auth_epoch AS current_epoch FROM auth_sessions s JOIN auth_accounts a ON a.user_id=s.user_id WHERE s.access_hash=?",
                PerceptionAccess.hash(raw))
            .orElseThrow(AppAuthentication::denied);
    if (!"active".equals(text(row, "state"))
        || !"active".equals(text(row, "account_state"))
        || number(row, "auth_epoch") != number(row, "current_epoch")
        || !time(row, "expires_at").isAfter(db.now())
        || !time(row, "access_expires_at").isAfter(db.now())) throw denied();
    return new Identity(
        text(row, "user_id"),
        text(row, "id"),
        number(row, "auth_epoch"),
        time(row, "access_expires_at"));
  }

  /** Called only inside a transaction before a sensitive mutation. */
  public Identity lockIdentity(String authorization) {
    Identity hint = identity(authorization);
    lockAccount(hint.user());
    db.one("SELECT id FROM auth_sessions WHERE id=? FOR UPDATE", hint.session())
        .orElseThrow(AppAuthentication::denied);
    return identity(authorization);
  }

  public void requireAccountActive(String user) {
    var row = db.one("SELECT state FROM auth_accounts WHERE user_id=?", user);
    if (row.isPresent() && !"active".equals(text(row.get(), "state")))
      throw new BackendException(403, "account_inactive");
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> refresh(String raw) {
    String hash = PerceptionAccess.hash(raw);
    var hint =
        db.one(
                "SELECT s.user_id,r.session_id FROM auth_refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id WHERE token_hash=?",
                hash)
            .orElseThrow(AppAuthentication::denied);
    var account = lockAccount(text(hint, "user_id"));
    var session =
        db.one("SELECT * FROM auth_sessions WHERE id=? FOR UPDATE", hint.get("session_id"))
            .orElseThrow();
    var old =
        db.one("SELECT * FROM auth_refresh_tokens WHERE token_hash=? FOR UPDATE", hash)
            .orElseThrow();
    if (old.get("used_at") != null) {
      revoke(text(session, "id"));
      throw new BackendException(401, "refresh_reuse_session_revoked");
    }
    requireUsable(account);
    if (!validSession(session, account)
        || old.get("revoked_at") != null
        || !time(old, "expires_at").isAfter(db.now())) throw denied();
    String access = token("at_"), next = token("rt_");
    Instant accessUntil = db.now().plusSeconds(900);
    if (accessUntil.isAfter(time(session, "expires_at"))) accessUntil = time(session, "expires_at");
    db.update(
        "UPDATE auth_sessions SET access_hash=?,access_expires_at=? WHERE id=?",
        PerceptionAccess.hash(access),
        accessUntil,
        session.get("id"));
    db.update(
        "INSERT INTO auth_access_history(token_hash,session_id) VALUES(?,?)",
        PerceptionAccess.hash(access),
        session.get("id"));
    db.update(
        "UPDATE auth_refresh_tokens SET used_at=?,replaced_by=? WHERE token_hash=?",
        db.now(),
        PerceptionAccess.hash(next),
        hash);
    db.update(
        "INSERT INTO auth_refresh_tokens(token_hash,session_id,expires_at) VALUES(?,?,?)",
        PerceptionAccess.hash(next),
        session.get("id"),
        old.get("expires_at"));
    return tokens(
        text(account, "user_id"),
        text(session, "id"),
        access,
        next,
        accessUntil,
        time(session, "expires_at"));
  }

  @Transactional
  public void logout(String authorization) {
    // The raw token can identify an expired/revoked session, making repeated logout safe.
    var hint =
        db.one(
            "SELECT s.user_id,s.id FROM auth_sessions s JOIN auth_access_history h ON h.session_id=s.id WHERE h.token_hash=?",
            PerceptionAccess.hash(PerceptionAccess.bearer(authorization)));
    if (hint.isEmpty()) return;
    lockAccount(text(hint.get(), "user_id"));
    revoke(text(hint.get(), "id"));
  }

  public Map<String, Object> me(String authorization) {
    var who = identity(authorization);
    var row = account(who.user());
    return Map.of(
        "userId",
        who.user(),
        "username",
        row.get("username_normalized"),
        "sessionId",
        who.session(),
        "accessExpiresAt",
        who.expiresAt().toString(),
        "mfaEnabled",
        activeFactor(who.user()),
        "mode",
        "account");
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> enroll(String authorization, String password) {
    var who = lockIdentity(authorization);
    var row = account(who.user());
    requireUsable(row);
    if (!passwordMatches(row, password)) {
      fail(row);
      throw denied();
    }
    if (activeFactor(who.user())) throw new BackendException(409, "mfa_already_enabled");
    String secret = TotpCodes.newSecret();
    db.update("DELETE FROM auth_totp_factors WHERE user_id=?", who.user());
    db.update(
        "INSERT INTO auth_totp_factors(user_id,secret_cipher,state,expires_at,updated_at) VALUES(?,?,'pending',?,?)",
        who.user(),
        vault.seal("auth-totp:" + who.user(), Map.of("secret", secret)),
        db.now().plusSeconds(300),
        db.now());
    String label =
        URLEncoder.encode("PocketBanker:" + row.get("username_normalized"), StandardCharsets.UTF_8);
    return Map.of(
        "secret",
        secret,
        "otpauthUri",
        "otpauth://totp/"
            + label
            + "?secret="
            + secret
            + "&issuer=PocketBanker&algorithm=SHA1&digits=6&period=30",
        "expiresIn",
        300);
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> confirmMfa(String authorization, String code) {
    var who = lockIdentity(authorization);
    var row = account(who.user());
    requireUsable(row);
    var f =
        db.one("SELECT * FROM auth_totp_factors WHERE user_id=? FOR UPDATE", who.user())
            .orElseThrow(AppAuthentication::denied);
    if (!"pending".equals(text(f, "state")) || !time(f, "expires_at").isAfter(db.now()))
      throw denied();
    long step = match(f, code);
    if (step < 0) {
      fail(row);
      throw denied();
    }
    db.update(
        "UPDATE auth_totp_factors SET state='active',last_accepted_step=?,expires_at=NULL,updated_at=? WHERE user_id=?",
        step,
        db.now(),
        who.user());
    db.update(
        "UPDATE auth_recovery_codes SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
        db.now(),
        who.user());
    List<String> recovery = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String value = token("rc_");
      recovery.add(value);
      db.update(
          "INSERT INTO auth_recovery_codes(code_hash,user_id,created_at) VALUES(?,?,?)",
          PerceptionAccess.hash(value),
          who.user(),
          db.now());
    }
    // Sessions issued before enrollment cannot bypass the newly activated login factor.
    db.update(
        "UPDATE auth_sessions SET state='revoked' WHERE user_id=? AND id<>?",
        who.user(),
        who.session());
    clearFailures(row);
    return Map.of("mfaEnabled", true, "recoveryCodes", recovery);
  }

  @Transactional(noRollbackFor = BackendException.class)
  public Map<String, Object> recover(String username, String password, String recovery) {
    var hint =
        db.one("SELECT user_id FROM auth_accounts WHERE username_normalized=?", username(username))
            .orElseThrow(AppAuthentication::denied);
    var row = lockAccount(text(hint, "user_id"));
    requireUsable(row);
    var code =
        db.one(
            "SELECT * FROM auth_recovery_codes WHERE code_hash=? AND user_id=? FOR UPDATE",
            PerceptionAccess.hash(recovery),
            row.get("user_id"));
    boolean valid =
        code.isPresent()
            && code.get().get("used_at") == null
            && code.get().get("revoked_at") == null;
    if (!passwordMatches(row, password) || !valid) {
      fail(row);
      throw denied();
    }
    db.update(
        "UPDATE auth_recovery_codes SET used_at=? WHERE code_hash=?",
        db.now(),
        code.get().get("code_hash"));
    db.update(
        "UPDATE auth_recovery_codes SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
        db.now(),
        row.get("user_id"));
    db.update(
        "UPDATE auth_totp_factors SET state='revoked',updated_at=? WHERE user_id=?",
        db.now(),
        row.get("user_id"));
    revokeAll(text(row, "user_id"));
    return Map.of("recovered", true, "loginRequired", true, "mfaEnrollmentRequired", true);
  }

  @Transactional(noRollbackFor = BackendException.class)
  public void changePassword(String authorization, String old, String code, String next) {
    validatePassword(next);
    var who = lockIdentity(authorization);
    var row = account(who.user());
    requireUsable(row);
    if (!passwordMatches(row, old) || !consumeTotp(who.user(), code)) {
      fail(row);
      throw denied();
    }
    db.update(
        "UPDATE auth_accounts SET password_hash=?,updated_at=? WHERE user_id=?",
        passwords.encode(next),
        db.now(),
        who.user());
    revokeAll(who.user());
  }

  /** Locks must already be held by the calling transaction; success consumes the time step once. */
  public boolean verifyFactors(Identity who, String password, String code) {
    var row = account(who.user());
    requireUsable(row);
    if (!passwordMatches(row, password) || !consumeTotp(who.user(), code)) {
      fail(row);
      db.update(
          "UPDATE backend_users SET failures=failures+1,locked_until=CASE WHEN failures+1>=5 THEN ? ELSE locked_until END,updated_at=? WHERE user_id=?",
          db.now().plusSeconds(60),
          db.now(),
          who.user());
      return false;
    }
    clearFailures(row);
    return true;
  }

  public String fingerprint(String value) {
    if (macKey.length() < 32) throw new BackendException(503, "auth_mac_key_not_configured");
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(macKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("auth_mac_failed");
    }
  }

  private Map<String, Object> lockAccount(String user) {
    db.lockUser(user);
    return db.one("SELECT * FROM auth_accounts WHERE user_id=? FOR UPDATE", user)
        .orElseThrow(AppAuthentication::denied);
  }

  private Map<String, Object> account(String user) {
    return db.one("SELECT * FROM auth_accounts WHERE user_id=?", user)
        .orElseThrow(AppAuthentication::denied);
  }

  private boolean activeFactor(String user) {
    return db.one("SELECT user_id FROM auth_totp_factors WHERE user_id=? AND state='active'", user)
        .isPresent();
  }

  private long match(Map<String, Object> factor, String code) {
    String user = text(factor, "user_id");
    String secret =
        vault.map("auth-totp:" + user, text(factor, "secret_cipher")).get("secret").toString();
    return TotpCodes.matchingStep(secret, code, db.now(), number(factor, "last_accepted_step"));
  }

  private boolean consumeTotp(String user, String code) {
    var f =
        db.one(
            "SELECT * FROM auth_totp_factors WHERE user_id=? AND state='active' FOR UPDATE", user);
    if (f.isEmpty()) return false;
    long step = match(f.get(), code);
    if (step < 0) return false;
    db.update(
        "UPDATE auth_totp_factors SET last_accepted_step=?,updated_at=? WHERE user_id=?",
        step,
        db.now(),
        user);
    return true;
  }

  private boolean passwordMatches(Map<String, Object> row, String password) {
    return password != null
        && password.length() <= 128
        && passwords.matches(password, text(row, "password_hash"));
  }

  private void requireUsable(Map<String, Object> row) {
    if (!"active".equals(text(row, "state"))) throw denied();
    Instant until = time(row, "locked_until");
    if (until != null && until.isAfter(db.now()))
      throw new BackendException(429, "account_temporarily_locked");
  }

  private void fail(Map<String, Object> row) {
    db.update(
        "UPDATE auth_accounts SET failed_attempts=failed_attempts+1,locked_until=CASE WHEN failed_attempts+1>=5 THEN ? ELSE locked_until END,updated_at=? WHERE user_id=?",
        db.now().plusSeconds(60),
        db.now(),
        row.get("user_id"));
  }

  private void clearFailures(Map<String, Object> row) {
    db.update(
        "UPDATE auth_accounts SET failed_attempts=0,locked_until=NULL,updated_at=? WHERE user_id=?",
        db.now(),
        row.get("user_id"));
  }

  private boolean validSession(Map<String, Object> session, Map<String, Object> account) {
    return "active".equals(text(session, "state"))
        && time(session, "expires_at").isAfter(db.now())
        && number(session, "auth_epoch") == number(account, "auth_epoch");
  }

  private Map<String, Object> issue(Map<String, Object> account) {
    String id = UUID.randomUUID().toString(), access = token("at_"), refresh = token("rt_");
    Instant until = db.now().plusSeconds(900), expires = db.now().plusSeconds(604800);
    db.update(
        "INSERT INTO auth_sessions(id,user_id,access_hash,access_expires_at,auth_epoch,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",
        id,
        account.get("user_id"),
        PerceptionAccess.hash(access),
        until,
        account.get("auth_epoch"),
        db.now(),
        expires);
    db.update(
        "INSERT INTO auth_access_history(token_hash,session_id) VALUES(?,?)",
        PerceptionAccess.hash(access),
        id);
    db.update(
        "INSERT INTO auth_refresh_tokens(token_hash,session_id,expires_at) VALUES(?,?,?)",
        PerceptionAccess.hash(refresh),
        id,
        expires);
    return tokens(text(account, "user_id"), id, access, refresh, until, expires);
  }

  private Map<String, Object> tokens(
      String user, String session, String access, String refresh, Instant until, Instant expires) {
    return Map.of(
        "userId",
        user,
        "sessionId",
        session,
        "accessToken",
        access,
        "refreshToken",
        refresh,
        "tokenType",
        "Bearer",
        "accessExpiresAt",
        until.toString(),
        "refreshExpiresAt",
        expires.toString(),
        "mfaEnabled",
        activeFactor(user));
  }

  private void revoke(String id) {
    db.update("UPDATE auth_sessions SET state='revoked' WHERE id=?", id);
    db.update(
        "UPDATE auth_refresh_tokens SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL",
        db.now(),
        id);
    db.update(
        "UPDATE auth_operation_challenges SET state='revoked' WHERE session_id=? AND state='pending'",
        id);
  }

  private void revokeAll(String user) {
    db.update(
        "UPDATE auth_accounts SET auth_epoch=auth_epoch+1,failed_attempts=0,locked_until=NULL,updated_at=? WHERE user_id=?",
        db.now(),
        user);
    for (var s : db.rows("SELECT id FROM auth_sessions WHERE user_id=?", user))
      revoke(text(s, "id"));
  }

  private static String token(String prefix) {
    return prefix + PerceptionAccess.newToken().substring(4);
  }

  private static String username(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]{3,64}"))
      throw new BackendException(400, "invalid_username");
    return value.toLowerCase(Locale.ROOT);
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < 12 || password.length() > 128)
      throw new BackendException(400, "password_length_12_to_128_required");
  }

  private static BackendException denied() {
    return new BackendException(401, "invalid_credentials");
  }
}
