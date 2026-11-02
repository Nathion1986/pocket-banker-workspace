package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.PocketBankerApplication;
import com.pocketbanker.agent.PerceptionFacade;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the standalone pinned Radicale process provided by integration-perception. */
class CalDavAcceptance {
  @TempDir Path temp;
  final String token = "radicale-user-token-at-least-32-characters";
  final String url = System.getenv("E_CALDAV_URL") + "alpha/calendar/";
  final LocalDate first = LocalDate.now(ZoneOffset.UTC).plusDays(7);
  final HttpClient http = HttpClient.newHttpClient();

  int remote(String path, String method, String body) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(url + path))
                .header(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(
                                ("alpha:" + System.getenv("E_CALDAV_PASSWORD"))
                                    .getBytes(StandardCharsets.UTF_8)))
                .header(
                    "Content-Type",
                    method.equals("PUT") ? "text/calendar; charset=utf-8" : "application/xml")
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  void collection() throws Exception {
    assertThat(
            remote(
                "",
                "MKCALENDAR",
                """
        <?xml version="1.0" encoding="utf-8"?>
        <c:mkcalendar xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:d="DAV:"><d:set><d:prop>
        <d:displayname>synthetic acceptance calendar</d:displayname>
        </d:prop></d:set></c:mkcalendar>
        """))
        .isIn(201, 200);
  }

  String ics(String amount) {
    return ("""
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//PocketBanker//IndependentAcceptance//CN
        BEGIN:VEVENT
        UID:independent-payment
        DTSTAMP:20261001T000000Z
        DTSTART;VALUE=DATE:%s
        RRULE:FREQ=DAILY;COUNT=3
        EXDATE;VALUE=DATE:%s
        X-PB-KIND:payment
        X-PB-AMOUNT:%s
        X-PB-CURRENCY:CNY
        DESCRIPTION:PRIVATE_RADICALE_ORIGINAL
        END:VEVENT
        END:VCALENDAR
        """)
        .formatted(
            first.format(DateTimeFormatter.BASIC_ISO_DATE),
            first.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE),
            amount)
        .replace("\n", "\r\n");
  }

  ConfigurableApplicationContext start() throws Exception {
    String config =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "independent-calendar",
                    Map.of(
                        "userId",
                        "alpha",
                        "url",
                        url,
                        "username",
                        "alpha",
                        "password",
                        System.getenv("E_CALDAV_PASSWORD"),
                        "timezone",
                        "Asia/Shanghai",
                        "allowLocalHttp",
                        true)));
    return new SpringApplicationBuilder(PocketBankerApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url=jdbc:h2:file:" + temp.resolve("calendar") + ";MODE=PostgreSQL",
            "--perception.users-json={\"alpha\":\"" + token + "\"}",
            "--backend.calendars-json=" + config,
            "--perception.caldav-initial-ms=3600000",
            "--perception.imap-initial-ms=3600000",
            "--backend.notification-initial-ms=3600000",
            "--llm.glm.api-key=",
            "--logging.level.root=WARN");
  }

  ImapConnector.SyncState sync(PerceptionFacade facade, String id) {
    facade.sync("Bearer " + token, id);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                Set.of("completed", "retry_required")
                    .contains(facade.syncState("Bearer " + token, id).status()));
    return facade.syncState("Bearer " + token, id);
  }

  @Test
  void editsDeletionRecreationAndRestartPreserveConfirmedFactBoundaries() throws Exception {
    collection();
    assertThat(remote("bill.ics", "PUT", ics("120.50"))).isEqualTo(201);
    String id;
    try (var context = start()) {
      var facade = context.getBean(PerceptionFacade.class);
      var jdbc = context.getBean(JdbcTemplate.class);
      id =
          facade
              .createSource(
                  "Bearer " + token,
                  new PerceptionRequests.CreateSource(
                      "CALENDAR",
                      null,
                      "calendar-consent",
                      true,
                      List.of(),
                      "independent-calendar",
                      Instant.now().plusSeconds(86400)))
              .source()
              .id();
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      var candidates = facade.events("Bearer " + token, "candidate", 0).getContent();
      assertThat(candidates).hasSize(2);
      assertThat(candidates.get(0).getPayloadJson())
          .contains("\"timezone\":\"Asia/Shanghai\"", "\"allDay\":true", "\"startsAt\"");
      facade.decide("Bearer " + token, candidates.get(0).getId(), true);
      assertThat(sync(facade, id).processed()).isZero();
      // Only move the sync cursor's age; source contents and confirmed events stay unchanged.
      jdbc.update(
          "UPDATE backend_caldav_resources SET updated_at=?",
          Instant.now().minusSeconds(2 * 86400));
      assertThat(sync(facade, id).processed()).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT revision FROM backend_caldav_resources", Long.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM events WHERE id=?", String.class, candidates.get(0).getId()))
          .isEqualTo("confirmed");
      assertThat(remote("bill.ics", "PUT", ics("240.75"))).isEqualTo(204);
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM events WHERE superseded_by IS NULL AND amount=240.75",
                  Integer.class))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM events WHERE superseded_by IS NOT NULL", Integer.class))
          .isEqualTo(2);
      assertThatThrownBy(() -> facade.decide("Bearer " + token, candidates.get(1).getId(), true))
          .hasMessage("event_version_stale");
      assertThatThrownBy(() -> facade.decide("Bearer " + token, candidates.get(0).getId(), true))
          .hasMessage("event_version_stale");
      // Collection inaccessible is not a successful empty snapshot: no event may be deleted.
      assertThat(remote("", "DELETE", null)).isEqualTo(200);
      assertThat(sync(facade, id).status()).isEqualTo("retry_required");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM events WHERE status='expired' AND superseded_by IS NULL",
                  Integer.class))
          .isZero();
      collection();
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM events WHERE status='expired' AND superseded_by IS NULL",
                  Integer.class))
          .isEqualTo(2);
      assertThat(remote("bill.ics", "PUT", ics("240.75"))).isEqualTo(201);
      assertThat(sync(facade, id).status()).isEqualTo("completed");
      assertThat(jdbc.queryForObject("SELECT revision FROM backend_caldav_resources", Long.class))
          .isEqualTo(4);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(6);
      String script =
          String.join("\n", jdbc.queryForList("SCRIPT NOPASSWORDS NOSETTINGS", String.class));
      assertThat(script)
          .doesNotContain(
              "PRIVATE_RADICALE_ORIGINAL", System.getenv("E_CALDAV_PASSWORD"), "bill.ics");
    }
    try (var context = start()) {
      var facade = context.getBean(PerceptionFacade.class);
      assertThat(sync(facade, id).processed()).isZero();
      assertThat(
              context
                  .getBean(JdbcTemplate.class)
                  .queryForObject("SELECT COUNT(*) FROM events", Integer.class))
          .isEqualTo(6);
      facade.revoke("Bearer " + token, id);
      assertThatThrownBy(() -> facade.sync("Bearer " + token, id)).hasMessage("source_inactive");
    }
  }
}
