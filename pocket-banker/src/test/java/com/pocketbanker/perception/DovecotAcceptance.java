package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.PocketBankerApplication;
import com.pocketbanker.agent.PerceptionFacade;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit independent acceptance; default unit test patterns do not silently skip Docker. */
class DovecotAcceptance {
  @TempDir Path temp;
  static final String TOKEN = "dovecot-test-user-token-at-least-32-characters";
  final String user = "fixture-" + UUID.randomUUID() + "@example.test";

  ConfigurableApplicationContext start(String host, String password) throws Exception {
    String accounts =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "independent-mail",
                    Map.of(
                        "userId",
                        "alpha",
                        "host",
                        host,
                        "port",
                        Integer.parseInt(System.getenv("E_MAIL_PORT")),
                        "username",
                        user,
                        "password",
                        password,
                        "mailbox",
                        "INBOX",
                        "ssl",
                        true)));
    return new SpringApplicationBuilder(PocketBankerApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url=jdbc:h2:file:" + temp.resolve("backend") + ";MODE=PostgreSQL",
            "--perception.users-json={\"alpha\":\"" + TOKEN + "\"}",
            "--perception.imap-accounts-json=" + accounts,
            "--perception.imap-initial-ms=3600000",
            "--perception.recovery-ms=3600000",
            "--backend.notification-initial-ms=3600000",
            "--llm.glm.api-key=",
            "--logging.level.root=WARN");
  }

  SourceService.CreatedSource source(PerceptionFacade facade) {
    return facade.createSource(
        "Bearer " + TOKEN,
        new PerceptionRequests.CreateSource(
            "EMAIL",
            null,
            "independent-mail-consent",
            true,
            List.of("school@example.test"),
            "independent-mail",
            Instant.now().plusSeconds(86400)));
  }

  ImapConnector.SyncState sync(PerceptionFacade facade, String id) {
    facade.sync("Bearer " + TOKEN, id);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                Set.of("completed", "retry_required")
                    .contains(facade.syncState("Bearer " + TOKEN, id).status()));
    return facade.syncState("Bearer " + TOKEN, id);
  }

  Store store() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
    properties.setProperty("mail.imaps.timeout", "10000");
    Store store = Session.getInstance(properties).getStore("imaps");
    store.connect(
        "localhost",
        Integer.parseInt(System.getenv("E_MAIL_PORT")),
        user,
        System.getenv("E_MAIL_PASSWORD"));
    return store;
  }

  void append(String sender, String text, boolean multipart) throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress(sender));
    message.setRecipients(Message.RecipientType.TO, user);
    message.setSubject("学校付款通知", "UTF-8");
    message.setSentDate(new Date());
    if (multipart) {
      MimeMultipart alternatives = new MimeMultipart("alternative");
      MimeBodyPart plain = new MimeBodyPart();
      plain.setText(text, "UTF-8");
      alternatives.addBodyPart(plain);
      MimeBodyPart html = new MimeBodyPart();
      html.setContent("<p>" + text + "</p>", "text/html; charset=UTF-8");
      alternatives.addBodyPart(html);
      MimeMultipart mixed = new MimeMultipart("mixed");
      MimeBodyPart body = new MimeBodyPart();
      body.setContent(alternatives);
      mixed.addBodyPart(body);
      MimeBodyPart attachment = new MimeBodyPart();
      attachment.setText("学费999999元 2026年12月30日 附件不可作为正文", "UTF-8");
      attachment.setDisposition(Part.ATTACHMENT);
      attachment.setFileName("invoice.txt");
      mixed.addBodyPart(attachment);
      message.setContent(mixed);
    } else message.setText(text, "UTF-8");
    message.saveChanges();
    try (Store store = store();
        Folder folder = store.getFolder("INBOX")) {
      folder.open(Folder.READ_WRITE);
      folder.appendMessages(new Message[] {message});
    }
  }

  @Test
  void productionConnectorRetainsCursorAcrossRestartAndNeverMarksMailRead() throws Exception {
    append("school@example.test", "正文超限秘密".repeat(40000), false);
    append("unapproved@example.test", "学费88888元 2026年10月15日", false);
    append("school@example.test", "学费20000元,2026年10月15日前缴纳。邮件原文独立秘密", true);
    String id;
    long last;
    try (var first = start("localhost", System.getenv("E_MAIL_PASSWORD"))) {
      var facade = first.getBean(PerceptionFacade.class);
      id = source(facade).source().id();
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      var jdbc = first.getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT amount FROM events", java.math.BigDecimal.class))
          .isEqualByComparingTo("20000");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM perception_log WHERE reason='mail_content_limit'",
                  Integer.class))
          .isEqualTo(1);
      last = jdbc.queryForObject("SELECT last_uid FROM mail_cursors", Long.class);
      assertThat(sync(facade, id).processed()).isZero();
      // Replay a committed delivery by losing only the checkpoint, as after a crash before advance.
      jdbc.update("UPDATE mail_cursors SET last_uid=0");
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
      String script =
          String.join("\n", jdbc.queryForList("SCRIPT NOPASSWORDS NOSETTINGS", String.class));
      assertThat(script).doesNotContain("邮件原文独立秘密", "正文超限秘密", System.getenv("E_MAIL_PASSWORD"));
    }
    append("school@example.test", "学费21000元,2026年11月15日前缴纳", false);
    try (var second = start("localhost", System.getenv("E_MAIL_PASSWORD"))) {
      var facade = second.getBean(PerceptionFacade.class);
      assertThat(sync(facade, id).processed()).isEqualTo(1);
      var jdbc = second.getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("SELECT last_uid FROM mail_cursors", Long.class))
          .isGreaterThan(last);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(2);
      facade.revoke("Bearer " + TOKEN, id);
      assertThatThrownBy(() -> facade.sync("Bearer " + TOKEN, id)).hasMessage("source_inactive");
    }
    try (Store store = store();
        Folder folder = store.getFolder("INBOX")) {
      folder.open(Folder.READ_ONLY);
      assertThat(folder.getMessageCount()).isEqualTo(4);
      for (var message : folder.getMessages()) assertThat(message.isSet(Flags.Flag.SEEN)).isFalse();
    }
  }

  @Test
  void hostnameMismatchDoesNotAdvanceCursorOrStoreMail() throws Exception {
    try (var context = start("127.0.0.1", System.getenv("E_MAIL_PASSWORD"))) {
      var facade = context.getBean(PerceptionFacade.class);
      var id = source(facade).source().id();
      assertThat(sync(facade, id).status()).isEqualTo("retry_required");
      assertThat(
              context
                  .getBean(JdbcTemplate.class)
                  .queryForObject("SELECT COUNT(*) FROM mail_cursors", Integer.class))
          .isZero();
    }
  }

  @Test
  void rejectedCredentialsDoNotAdvanceCursorOrStoreMail() throws Exception {
    try (var context = start("localhost", "incorrect-test-password")) {
      var facade = context.getBean(PerceptionFacade.class);
      var id = source(facade).source().id();
      assertThat(sync(facade, id).status()).isEqualTo("retry_required");
      assertThat(
              context
                  .getBean(JdbcTemplate.class)
                  .queryForObject("SELECT COUNT(*) FROM perception_log", Integer.class))
          .isZero();
    }
  }
}
