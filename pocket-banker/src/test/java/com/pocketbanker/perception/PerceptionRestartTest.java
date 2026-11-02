package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.pocketbanker.PocketBankerApplication;
import com.pocketbanker.agent.PerceptionFacade;
import com.pocketbanker.data.SignalReceiptRepository;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PerceptionRestartTest {
  @TempDir Path temp;

  @Test
  void v10DatabaseUpgradePreservesExistingSourceAndDoesNotInventDeviceReadiness() throws Exception {
    String token = "migration-test-user-token-at-least-32-characters";
    String sourceToken = "migration-test-source-token-at-least-32-characters";
    String url = "jdbc:h2:file:" + temp.resolve("upgrade") + ";MODE=PostgreSQL";
    org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").target("10").load().migrate();
    try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
        var statement =
            connection.prepareStatement(
                "INSERT INTO perception_sources (id,user_id,kind,device_id,consent_id,purpose,"
                    + "token_hash,allowed_senders_json,status,created_at,expires_at) "
                    + "VALUES ('legacy','alpha','SMS','device','consent','finance_events',"
                    + "?,'[\"95500\"]','active',?,?)")) {
      statement.setString(1, com.pocketbanker.security.PerceptionAccess.hash(sourceToken));
      statement.setObject(2, java.time.OffsetDateTime.now());
      statement.setObject(3, java.time.OffsetDateTime.now().plusDays(1));
      statement.executeUpdate();
    }
    try (var app = start(url, token)) {
      var facade = app.getBean(PerceptionFacade.class);
      assertThat(facade.collector("Bearer " + token, "legacy").collectorStatus())
          .isEqualTo("unknown");
      assertThat(facade.sources("Bearer " + token, 0)).hasSize(1);
      var receipt =
          facade.ingest(
              "Bearer " + sourceToken,
              "SMS",
              new PerceptionRequests.TextSignal(
                  "legacy", "upgrade-message", Instant.now(), "95500", null, "支出35元"));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(facade.receipt("Bearer " + token, receipt.id()).status())
                      .isEqualTo("completed"));
    }
  }

  @Test
  void fileDatabaseRetainsSourcesFactsAndRecoverableReceiptsAcrossFullContextRestart() {
    String token = "restart-test-user-token-at-least-32-characters";
    String url = "jdbc:h2:file:" + temp.resolve("banker") + ";MODE=PostgreSQL";
    String event, receipt, crashed, sourceToken, sourceId;
    Instant occurred = Instant.now().minusSeconds(10);
    try (var first = start(url, token)) {
      try (var connection = first.getBean(JdbcTemplate.class).getDataSource().getConnection()) {
        var session =
            (org.h2.engine.SessionLocal)
                connection.unwrap(org.h2.jdbc.JdbcConnection.class).getSession();
        assertThat(session.getDatabase().getWriteDelay()).isZero();
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
      var facade = first.getBean(PerceptionFacade.class);
      var source =
          facade.createSource(
              "Bearer " + token,
              new PerceptionRequests.CreateSource(
                  "SMS",
                  "device",
                  "c",
                  true,
                  List.of("95500"),
                  null,
                  Instant.now().plusSeconds(86400)));
      sourceToken = source.sourceToken();
      sourceId = source.source().id();
      facade.reportCollector(
          "Bearer " + sourceToken,
          sourceId,
          new PerceptionRequests.CollectorReport(
              1,
              "device",
              "android",
              "test-version",
              0L,
              7L,
              Instant.now(),
              "automatic",
              "granted",
              "allowed",
              1));
      receipt =
          facade
              .ingest(
                  "Bearer " + sourceToken,
                  "SMS",
                  new PerceptionRequests.TextSignal(
                      sourceId, "retained", occurred, "95500", null, "支出35元"))
              .id();
      var tx = first.getBean(SignalTransactions.class);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(tx.viewById(receipt).status()).isEqualTo("completed"));
      event = tx.viewById(receipt).eventId();
      facade.decide("Bearer " + token, event, true);
      crashed =
          tx.accept(
                  "Bearer " + sourceToken,
                  "SMS",
                  new PerceptionRequests.TextSignal(
                      sourceId, "unfinished", occurred, "95500", null, "支出36元"))
              .id();
      first
          .getBean(JdbcTemplate.class)
          .update(
              "UPDATE perception_log SET lease_until=? WHERE id=?",
              Instant.now().minusSeconds(1),
              crashed);
    }
    assertThat(temp.resolve("banker.mv.db")).exists();
    try (var second = start(url, token)) {
      var facade = second.getBean(PerceptionFacade.class);
      var collector = facade.collector("Bearer " + token, sourceId);
      assertThat(collector.report().sequence()).isEqualTo(7);
      assertThat(collector.collectorStatus()).isEqualTo("backlog_reported");
      assertThat(collector.evidence()).isEqualTo("self_reported");
      assertThat(facade.events("Bearer " + token, "confirmed", 0).getContent()).hasSize(1);
      assertThat(facade.events("Bearer " + token, "confirmed", 0).getContent().get(0).getId())
          .isEqualTo(event);
      var tx = second.getBean(SignalTransactions.class);
      tx.recoverExpired();
      assertThat(tx.viewById(crashed).status()).isEqualTo("retry_required");
      assertThat(
              facade
                  .ingest(
                      "Bearer " + sourceToken,
                      "SMS",
                      new PerceptionRequests.TextSignal(
                          sourceId, "unfinished", occurred, "95500", null, "支出36元"))
                  .id())
          .isEqualTo(crashed);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(tx.viewById(crashed).status()).isEqualTo("completed"));
      assertThat(facade.events("Bearer " + token, "candidate", 0).getTotalElements()).isEqualTo(1);
      assertThat(second.getBean(SignalReceiptRepository.class).count()).isEqualTo(2);
    }
  }

  private ConfigurableApplicationContext start(String url, String token) {
    return new SpringApplicationBuilder(PocketBankerApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url=" + url,
            "--perception.users-json={\"alpha\":\"" + token + "\"}",
            "--perception.recovery-ms=3600000",
            "--perception.imap-initial-ms=3600000",
            "--llm.glm.api-key=",
            "--logging.level.root=WARN",
            "--logging.level.com.pocketbanker=WARN");
  }
}
