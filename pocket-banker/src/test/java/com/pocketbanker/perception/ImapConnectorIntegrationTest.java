package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.pocketbanker.agent.PerceptionFacade;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:imap_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "perception.imap-initial-ms=3600000",
      "perception.recovery-ms=3600000"
    })
class ImapConnectorIntegrationTest {
  static final String TOKEN = "test-email-user-token-at-least-32-characters";
  static final GreenMail SERVER =
      new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP));
  static final GreenMailUser MAIL_USER;

  static {
    SERVER.start();
    MAIL_USER = SERVER.setUser("student@example.test", "mail-user", "test-mail-password");
  }

  @Autowired PerceptionFacade facade;
  @Autowired JdbcTemplate jdbc;
  @SpyBean MailCheckpoint checkpoint;

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("perception.users-json", () -> "{\"alpha\":\"" + TOKEN + "\"}");
    r.add(
        "perception.imap-accounts-json",
        () -> {
          try {
            return new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "test-mail",
                        Map.of(
                            "userId",
                            "alpha",
                            "host",
                            "127.0.0.1",
                            "port",
                            SERVER.getImap().getPort(),
                            "username",
                            "mail-user",
                            "password",
                            "test-mail-password",
                            "mailbox",
                            "INBOX",
                            "ssl",
                            false)));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @BeforeEach
  void resetData() throws Exception {
    SERVER.purgeEmailFromAllMailboxes();
    for (String table :
        List.of(
            "location_challenges",
            "event_decisions",
            "events",
            "mail_cursors",
            "perception_log",
            "perception_sources")) jdbc.update("DELETE FROM " + table);
  }

  @AfterAll
  static void stopServer() {
    SERVER.stop();
  }

  SourceService.CreatedSource source() {
    return facade.createSource(
        "Bearer " + TOKEN,
        new PerceptionRequests.CreateSource(
            "EMAIL",
            null,
            "consent-mail",
            true,
            List.of("school@example.test"),
            "test-mail",
            Instant.now().plusSeconds(86400)));
  }

  void deliver(String from, String text) throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress(from));
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("student@example.test"));
    message.setSubject("学校通知", "UTF-8");
    message.setSentDate(new Date());
    message.setText(text, "UTF-8");
    message.saveChanges();
    MAIL_USER.deliver(message);
  }

  ImapConnector.SyncState sync(String id) {
    facade.sync("Bearer " + TOKEN, id);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                Set.of("completed", "retry_required")
                    .contains(facade.syncState("Bearer " + TOKEN, id).status()));
    return facade.syncState("Bearer " + TOKEN, id);
  }

  @Test
  void oversizedMailIsQuarantinedWithoutBlockingLaterValidMail() throws Exception {
    var source = source();
    deliver("school@example.test", "私人正文".repeat(40000));
    deliver("school@example.test", "学费20000元,2026年10月15日前缴纳");
    assertThat(sync(source.source().id()).status()).isEqualTo("completed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM perception_log WHERE status='needs_review' AND reason='mail_content_limit'",
                Integer.class))
        .isEqualTo(1);
    assertThat(sync(source.source().id()).processed()).isZero();
  }

  @Test
  void readsIncrementallyWithoutMarkingReadAndStoresNoMailRaw() throws Exception {
    var source = source();
    deliver("school@example.test", "学费20000元,2026年10月15日前缴纳。原文秘密标记_MAIL");
    assertThat(sync(source.source().id()).status()).isEqualTo("completed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    long uid = jdbc.queryForObject("SELECT last_uid FROM mail_cursors", Long.class);
    assertThat(sync(source.source().id()).processed()).isZero();
    deliver("school@example.test", "学费21000元,2026年11月15日前缴纳");
    assertThat(sync(source.source().id()).processed()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT last_uid FROM mail_cursors", Long.class))
        .isGreaterThan(uid);
    try (Store store = Session.getInstance(new Properties()).getStore("imap")) {
      store.connect("127.0.0.1", SERVER.getImap().getPort(), "mail-user", "test-mail-password");
      try (Folder folder = store.getFolder("INBOX")) {
        folder.open(Folder.READ_ONLY);
        for (Message message : folder.getMessages())
          assertThat(message.isSet(Flags.Flag.SEEN)).isFalse();
      }
    }
    String script =
        String.join("\n", jdbc.queryForList("SCRIPT NOPASSWORDS NOSETTINGS", String.class));
    assertThat(script).doesNotContain("原文秘密标记_MAIL", "test-mail-password");
  }

  @Test
  void checkpointFailureReplaysWithoutDuplicateAndUidValidityResetResyncs() throws Exception {
    var source = source();
    deliver("school@example.test", "学费20000元,2026年10月15日前缴纳");
    doThrow(new IllegalStateException("simulated crash before checkpoint"))
        .doCallRealMethod()
        .when(checkpoint)
        .advance(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong());
    assertThat(sync(source.source().id()).status()).isEqualTo("retry_required");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_cursors", Integer.class)).isZero();
    assertThat(sync(source.source().id()).status()).isEqualTo("completed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    jdbc.update("UPDATE mail_cursors SET uid_validity=1");
    assertThat(sync(source.source().id()).status()).isEqualTo("completed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT uid_validity FROM mail_cursors", Long.class))
        .isNotEqualTo(1L);
  }

  @Test
  void unapprovedSendersAreSkippedAndRevocationStopsPolling() throws Exception {
    var source = source();
    deliver("spam@example.test", "学费20000元,2026年10月15日前缴纳");
    assertThat(sync(source.source().id()).status()).isEqualTo("completed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perception_log", Integer.class)).isZero();
    facade.revoke("Bearer " + TOKEN, source.source().id());
    assertThatThrownBy(() -> facade.sync("Bearer " + TOKEN, source.source().id()))
        .isInstanceOf(PerceptionException.class);
  }

  @Test
  void mailboxBindingMustBeConfiguredForAuthenticatedUser() {
    assertThatThrownBy(
            () ->
                facade.createSource(
                    "Bearer " + TOKEN,
                    new PerceptionRequests.CreateSource(
                        "EMAIL",
                        null,
                        "c",
                        true,
                        List.of("school@example.test"),
                        "unknown-account",
                        Instant.now().plusSeconds(86400))))
        .isInstanceOf(PerceptionException.class)
        .hasMessage("imap_account_not_configured_for_user");
  }
}
