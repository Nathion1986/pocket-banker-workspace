package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.agent.*;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:app_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "backend.workflow-initial-ms=3600000",
      "backend.proactive-initial-ms=3600000",
      "backend.notification-initial-ms=3600000",
      "perception.imap-initial-ms=3600000",
      "perception.structured-initial-ms=3600000",
      "backend.auth-bootstrap-user=alpha",
      "backend.auth-bootstrap-key=fixture-setup-key-at-least-32-characters",
      "backend.auth-mac-key=fixture-mac-key-at-least-32-characters"
    })
@AutoConfigureMockMvc
@Import(BackendIntegrationTest.ClockConfig.class)
class AppAuthenticationIntegrationTest {
  static final String PASSWORD = "fixture-passphrase-2026";
  static final String SETUP = "fixture-setup-key-at-least-32-characters";
  static final NativeFixture WORLD = new NativeFixture();
  @Autowired AppAuthentication auth;
  @Autowired AppGatewayFacade app;
  @Autowired AppOverviewFacade overview;
  @Autowired BackendStore db;
  @Autowired JdbcTemplate jdbc;
  @Autowired BackendIntegrationTest.MutableClock clock;
  @Autowired BankBindingService bindings;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired VaultCodec vault;
  @Autowired WorkflowTransactions workflows;
  Map<String, Object> tokens;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("bank.bank-a.base-url", () -> WORLD.url("A"));
    r.add("bank.bank-b.base-url", () -> WORLD.url("B"));
    r.add("bank.bank-c.base-url", () -> WORLD.url("C"));
    r.add("backend.flower-url", () -> WORLD.url("flower"));
    r.add("backend.cake-url", () -> WORLD.url("cake"));
  }

  @BeforeEach
  void reset() {
    WORLD.reset();
    clock.value.set(BackendIntegrationTest.NOW);
    jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
    for (String t :
        jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'",
            String.class))
      if (!t.equalsIgnoreCase("flyway_schema_history")) jdbc.update("DELETE FROM \"" + t + "\"");
    jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    tokens = auth.setup(SETUP, "Frank.Test", PASSWORD);
    bearer = "Bearer " + tokens.get("accessToken");
    for (String bank : List.of("A", "B", "C")) {
      var account = bindings.discover("alpha", bank).get(0);
      bindings.bind("alpha", bank, account.get("id").toString(), "fixture-consent");
    }
  }

  @AfterAll
  static void close() {
    WORLD.close();
  }

  String enableMfa() {
    String secret = auth.enroll(bearer, PASSWORD).get("secret").toString();
    auth.confirmMfa(bearer, code(secret));
    clock.value.set(clock.instant().plusSeconds(30));
    return secret;
  }

  String code(String secret) {
    return TotpCodes.at(secret, clock.instant().getEpochSecond() / 30);
  }

  Map<String, Object> invariant() {
    return Map.of(
        "tasks",
        db.rows("SELECT * FROM backend_tasks ORDER BY id"),
        "nodes",
        db.rows("SELECT * FROM backend_nodes ORDER BY task_id,id"),
        "quota",
        db.rows("SELECT * FROM quota_reservations ORDER BY operation_key"),
        "ledgers",
        WORLD.ledgerSnapshot());
  }

  String red() {
    return app.message(bearer, "phone", UUID.randomUUID().toString(), "解冻A行账户")
        .get("id")
        .toString();
  }

  String challenge(String task) {
    return app.authChallenge(bearer, "phone", task, 1).get("challengeId").toString();
  }

  @Test
  void setupIsOneUseAndStoresOnlyArgonAndOpaqueTokenHashes() {
    assertThatThrownBy(() -> auth.setup(SETUP, "another", PASSWORD))
        .hasMessage("account_already_initialized");
    assertThatThrownBy(() -> auth.setup("wrong-key", "another", PASSWORD))
        .hasMessage("account_setup_unavailable");
    assertThat(db.rows("SELECT * FROM auth_accounts").toString())
        .contains("$argon2id$")
        .doesNotContain(PASSWORD);
    assertThat(db.rows("SELECT * FROM auth_sessions").toString())
        .doesNotContain(tokens.get("accessToken").toString());
    assertThat(db.rows("SELECT * FROM auth_refresh_tokens").toString())
        .doesNotContain(tokens.get("refreshToken").toString());
    assertThat(auth.identity(bearer).user()).isEqualTo("alpha");
  }

  @Test
  void totpMatchesRfc6238AndRejectsReplay() {
    // RFC SHA1 secret 12345678901234567890, 8 digit 94287082 -> six digit suffix.
    assertThat(TotpCodes.at("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59 / 30)).isEqualTo("287082");
    String secret = enableMfa();
    var login = auth.login("frank.test", PASSWORD);
    assertThat(login).containsEntry("mfaRequired", true).doesNotContainKey("accessToken");
    var logged = auth.loginMfa(login.get("challengeId").toString(), code(secret));
    assertThat(logged).containsKey("accessToken");
    var second = auth.login("frank.test", PASSWORD);
    assertThatThrownBy(() -> auth.loginMfa(second.get("challengeId").toString(), code(secret)))
        .hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.loginMfa(login.get("challengeId").toString(), code(secret)))
        .hasMessage("invalid_credentials");
  }

  @Test
  void pendingEnrollmentDoesNotClaimMfaAndExpires() {
    auth.enroll(bearer, PASSWORD);
    assertThat(auth.me(bearer)).containsEntry("mfaEnabled", false);
    clock.value.set(clock.instant().plusSeconds(301));
    assertThatThrownBy(() -> auth.confirmMfa(bearer, "000000")).hasMessage("invalid_credentials");
    assertThat(auth.me(bearer)).containsEntry("mfaEnabled", false);
  }

  @Test
  void failedLoginPersistsAttemptLockAndChangesNoBusinessState() {
    var before = invariant();
    for (int i = 0; i < 5; i++)
      assertThatThrownBy(() -> auth.login("frank.test", "wrong")).hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.login("frank.test", PASSWORD))
        .hasMessage("account_temporarily_locked");
    assertThat(invariant()).isEqualTo(before);
    clock.value.set(clock.instant().plusSeconds(61));
    assertThat(auth.login("frank.test", PASSWORD)).containsKey("accessToken");
  }

  @Test
  void refreshRotatesExpiresAndReuseRevokesNewAccessAndRefresh() {
    var next = auth.refresh(tokens.get("refreshToken").toString());
    assertThatThrownBy(() -> auth.identity(bearer)).hasMessage("invalid_credentials");
    assertThat(auth.identity("Bearer " + next.get("accessToken")).user()).isEqualTo("alpha");
    assertThatThrownBy(() -> auth.refresh(tokens.get("refreshToken").toString()))
        .hasMessage("refresh_reuse_session_revoked");
    assertThatThrownBy(() -> auth.identity("Bearer " + next.get("accessToken")))
        .hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.refresh(next.get("refreshToken").toString()))
        .hasMessage("invalid_credentials");
  }

  @Test
  void oldAccessStillLogsOutFamilyAfterConcurrentRefresh() {
    var next = auth.refresh(tokens.get("refreshToken").toString());
    auth.logout(bearer);
    auth.logout(bearer);
    assertThatThrownBy(() -> auth.identity("Bearer " + next.get("accessToken")))
        .hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.refresh(next.get("refreshToken").toString()))
        .hasMessage("invalid_credentials");
  }

  @Test
  void simultaneousRefreshDetectsReuseAndRevokesFamily() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      var start = new CountDownLatch(1);
      Callable<Object> call =
          () -> {
            start.await();
            try {
              return auth.refresh(tokens.get("refreshToken").toString());
            } catch (BackendException e) {
              return e.getMessage();
            }
          };
      var a = pool.submit(call);
      var b = pool.submit(call);
      start.countDown();
      var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(Map.class::isInstance).count()).isEqualTo(1);
      assertThat(results).contains("refresh_reuse_session_revoked");
      assertThat(
              db.one("SELECT state FROM auth_sessions WHERE id=?", tokens.get("sessionId"))
                  .orElseThrow())
          .containsEntry("state", "revoked");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentLogoutAndRefreshCannotLeaveAnActiveSession() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var refresh =
          pool.submit(
              () -> {
                start.await();
                try {
                  return auth.refresh(tokens.get("refreshToken").toString());
                } catch (BackendException e) {
                  return Map.of("error", e.getMessage());
                }
              });
      var logout =
          pool.submit(
              () -> {
                start.await();
                auth.logout(bearer);
                return true;
              });
      start.countDown();
      var result = refresh.get(10, TimeUnit.SECONDS);
      assertThat(logout.get(10, TimeUnit.SECONDS)).isTrue();
      if (result.containsKey("accessToken")) {
        assertThatThrownBy(() -> auth.identity("Bearer " + result.get("accessToken")))
            .hasMessage("invalid_credentials");
        assertThatThrownBy(() -> auth.refresh(result.get("refreshToken").toString()))
            .hasMessage("invalid_credentials");
      }
      assertThat(
              db.one("SELECT state FROM auth_sessions WHERE id=?", tokens.get("sessionId"))
                  .orElseThrow())
          .containsEntry("state", "revoked");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void accessExpiryDoesNotExtendAbsoluteRefreshExpiry() {
    clock.value.set(clock.instant().plusSeconds(901));
    assertThatThrownBy(() -> auth.identity(bearer)).hasMessage("invalid_credentials");
    var next = auth.refresh(tokens.get("refreshToken").toString());
    assertThat(next.get("refreshExpiresAt")).isEqualTo(tokens.get("refreshExpiresAt"));
    clock.value.set(BackendIntegrationTest.NOW.plusSeconds(604801));
    assertThatThrownBy(() -> auth.refresh(next.get("refreshToken").toString()))
        .hasMessage("invalid_credentials");
  }

  @Test
  void passwordChangeNeedsBothFactorsAndRevokesAllOldCredentials() {
    String secret = enableMfa();
    var before = invariant();
    assertThatThrownBy(
            () -> auth.changePassword(bearer, PASSWORD, "000000", "new-synthetic-passphrase"))
        .hasMessage("invalid_credentials");
    assertThat(auth.identity(bearer).user()).isEqualTo("alpha");
    auth.changePassword(bearer, PASSWORD, code(secret), "new-synthetic-passphrase");
    assertThatThrownBy(() -> auth.identity(bearer)).hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.refresh(tokens.get("refreshToken").toString()))
        .hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.login("frank.test", PASSWORD)).hasMessage("invalid_credentials");
    assertThat(auth.login("frank.test", "new-synthetic-passphrase"))
        .containsEntry("mfaRequired", true);
    assertThat(invariant()).isEqualTo(before);
  }

  @Test
  void accountModeRejectsDemoBearerAndBothLegacyVerificationEntrypoints() {
    var formal =
        new PerceptionAccess(
            "{\"alpha\":\"fixture-demo-token-at-least-32-characters\"}", mapper, auth, "account");
    assertThatThrownBy(() -> formal.requireUser("Bearer fixture-demo-token-at-least-32-characters"))
        .hasMessageContaining("account_login_required");
    assertThat(formal.requireUser(bearer)).isEqualTo("alpha");
    String task = red();
    var before = invariant();
    org.springframework.test.util.ReflectionTestUtils.setField(workflows, "mode", "account");
    try {
      assertThatThrownBy(() -> workflows.demoChallenge("alpha", task))
          .hasMessage("demo_verification_disabled");
      assertThatThrownBy(() -> workflows.verify("alpha", task, "old-code", "old-factor", 1))
          .hasMessage("demo_verification_disabled");
      assertThat(invariant()).isEqualTo(before);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(workflows, "mode", "local-demo");
    }
  }

  @Test
  void newSessionCannotConsumeOldSessionsOperationProofAndCodeCannotAuthorizeTwoTasks() {
    String secret = enableMfa(),
        first = red(),
        c1 = challenge(first),
        second = red(),
        c2 = challenge(second);
    clock.value.set(clock.instant().plusSeconds(30));
    var attempt = auth.login("frank.test", PASSWORD);
    var other = auth.loginMfa(attempt.get("challengeId").toString(), code(secret));
    String otherBearer = "Bearer " + other.get("accessToken");
    clock.value.set(clock.instant().plusSeconds(30));
    var before = invariant();
    assertThatThrownBy(
            () ->
                app.authVerify(
                    otherBearer, "phone", first, c1, 1, PASSWORD, code(secret), "wrong-session"))
        .hasMessage("verification_challenge_inactive");
    assertThat(invariant()).isEqualTo(before);
    app.authVerify(bearer, "phone", first, c1, 1, PASSWORD, code(secret), "first-proof");
    var afterFirst = invariant();
    assertThat(
            app.authVerify(bearer, "phone", second, c2, 1, PASSWORD, code(secret), "replay-step"))
        .containsKey("error");
    assertThat(invariant()).isEqualTo(afterFirst);
  }

  @Test
  void recoveryIsOneUseRevokesSessionsAndRequiresFreshEnrollment() {
    String secret = auth.enroll(bearer, PASSWORD).get("secret").toString();
    var result = auth.confirmMfa(bearer, code(secret));
    String recovery = ((List<?>) result.get("recoveryCodes")).get(0).toString();
    var before = invariant();
    auth.recover("frank.test", PASSWORD, recovery);
    assertThatThrownBy(() -> auth.identity(bearer)).hasMessage("invalid_credentials");
    assertThatThrownBy(() -> auth.recover("frank.test", PASSWORD, recovery))
        .hasMessage("invalid_credentials");
    assertThat(auth.login("frank.test", PASSWORD)).containsEntry("mfaEnabled", false);
    assertThat(invariant()).isEqualTo(before);
  }

  @Test
  void missingWrongExpiredAndForeignOperationProofNeverAuthorizeOrChangeLedgers() {
    String secret = enableMfa(), task = red(), challenge = challenge(task);
    var before = invariant();
    assertThat(app.authVerify(bearer, "phone", task, challenge, 1, "wrong", code(secret), "wrong"))
        .containsKey("error");
    assertThat(app.authVerify(bearer, "phone", task, challenge, 1, PASSWORD, "000000", "bad-code"))
        .containsKey("error");
    assertThatThrownBy(
            () ->
                app.authVerify(
                    bearer, "other", task, challenge, 1, PASSWORD, code(secret), "foreign"))
        .isInstanceOf(BackendException.class);
    assertThatThrownBy(
            () ->
                app.authVerify(
                    bearer, "phone", task, challenge, 2, PASSWORD, code(secret), "old-version"))
        .hasMessage("task_snapshot_changed");
    clock.value.set(clock.instant().plusSeconds(121));
    assertThatThrownBy(
            () ->
                app.authVerify(
                    bearer, "phone", task, challenge, 1, PASSWORD, code(secret), "expired"))
        .hasMessage("verification_challenge_inactive");
    assertThat(invariant()).isEqualTo(before);
  }

  @Test
  void sameSessionProofIsOneUseAndIdempotencyUsesMacWithoutSecretStorage() {
    String secret = enableMfa(), task = red(), challenge = challenge(task);
    String code = code(secret);
    var before = WORLD.ledgerSnapshot();
    var verified = app.authVerify(bearer, "phone", task, challenge, 1, PASSWORD, code, "verified");
    assertThat(verified).containsEntry("state", "READY");
    assertThat(
            mapper
                .valueToTree(
                    app.authVerify(bearer, "phone", task, challenge, 1, PASSWORD, code, "verified"))
                .toString())
        .isEqualTo(mapper.valueToTree(verified).toString());
    assertThatThrownBy(
            () -> app.authVerify(bearer, "phone", task, challenge, 1, PASSWORD, code, "another"))
        .hasMessage("task_not_awaiting_verification");
    assertThat(
            db.one("SELECT passed_factors,approved_risk FROM backend_tasks WHERE id=?", task)
                .orElseThrow())
        .containsEntry("passed_factors", 2)
        .containsEntry("approved_risk", "RED");
    assertThat(db.rows("SELECT * FROM app_commands").toString()).doesNotContain(PASSWORD);
    assertThat(WORLD.ledgerSnapshot()).isEqualTo(before);
  }

  @Test
  void fullParameterReplacementInvalidatesOperationDigest() {
    String secret = enableMfa(), task = red(), challenge = challenge(task);
    db.update(
        "UPDATE backend_nodes SET input_cipher=? WHERE task_id=?",
        vault.seal("alpha", Map.of("bank", "B", "accountId", "B-alpha")),
        task);
    var before = invariant();
    assertThatThrownBy(
            () ->
                app.authVerify(
                    bearer, "phone", task, challenge, 1, PASSWORD, code(secret), "changed"))
        .hasMessage("verification_challenge_inactive");
    assertThat(invariant()).isEqualTo(before);
  }

  @Test
  void logoutAndAccountDisableRejectBusinessAndPreserveOriginalState() {
    String secret = enableMfa(), task = red(), challenge = challenge(task);
    var before = invariant();
    auth.logout(bearer);
    assertThatThrownBy(
            () ->
                app.authVerify(
                    bearer, "phone", task, challenge, 1, PASSWORD, code(secret), "logout"))
        .hasMessage("invalid_credentials");
    assertThat(invariant()).isEqualTo(before);
    db.update("UPDATE auth_accounts SET state='disabled' WHERE user_id='alpha'");
    assertThatThrownBy(() -> auth.requireAccountActive("alpha")).hasMessage("account_inactive");
  }

  @Test
  void overviewMatchesIndependentAccountsAndNeverWritesFinancialState() {
    var before = invariant();
    var view = overview.overview(bearer, "phone", 30);
    assertThat(view).containsEntry("complete", true).containsEntry("userId", "alpha");
    var accounts = (List<?>) view.get("accounts");
    assertThat(accounts).hasSize(3);
    var total = (Map<?, ?>) view.get("totals");
    var ledger = WORLD.ledgerSnapshot();
    java.math.BigDecimal oracle = java.math.BigDecimal.ZERO;
    for (String bank : List.of("A", "B", "C")) {
      var bankLedger = (Map<?, ?>) ledger.get(bank);
      for (Object a : (List<?>) bankLedger.get("accounts")) {
        var account = (Map<?, ?>) a;
        if ("alpha".equals(account.get("owner")))
          oracle =
              oracle.add(
                  new java.math.BigDecimal(account.get("balance").toString()).movePointLeft(2));
      }
    }
    assertThat(new java.math.BigDecimal(total.get("cash").toString())).isEqualByComparingTo(oracle);
    assertThat(new java.math.BigDecimal(total.get("availableCash").toString()))
        .isEqualByComparingTo(oracle);
    assertThat(invariant()).isEqualTo(before);
    for (String bank : List.of("A", "B", "C")) bindings.unbind("alpha", bank, bank + "-alpha");
    assertThat(overview.overview(bearer, "phone", 30)).containsEntry("complete", false);
  }

  @Test
  void httpDtosBoundInputNoStoreAndMissingFactorHasNoAuthority() throws Exception {
    var before = invariant();
    var r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of("username", "frank.test", "password", PASSWORD))))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(
            mvc.perform(
                    post("/api/proposals/" + UUID.randomUUID() + "/auth-verify")
                        .header("Authorization", bearer)
                        .header("X-Demo-Session", "phone")
                        .contentType("application/json")
                        .content(
                            "{\"version\":1,\"password\":\""
                                + PASSWORD
                                + "\",\"challengeId\":\"fixture\"}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(invariant()).isEqualTo(before);
  }
}
